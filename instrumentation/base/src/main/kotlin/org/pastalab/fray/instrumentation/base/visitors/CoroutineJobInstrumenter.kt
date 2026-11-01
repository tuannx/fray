package org.pastalab.fray.instrumentation.base.visitors

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.AdviceAdapter
import org.objectweb.asm.commons.Method

/** Experimental hook for the pinned JobSupport ABI. Missing hooks fail the transform closed. */
class CoroutineJobInstrumenter(cv: ClassVisitor) : ClassVisitor(Opcodes.ASM9, cv) {
  private val tracker = Type.getObjectType("org/pastalab/fray/runtime/CoroutineJobTracker")
  private var owner = ""
  private var foundConstructor = false
  private var foundParent = false
  private var foundFinalization = false

  override fun visit(
      version: Int,
      access: Int,
      name: String,
      signature: String?,
      superName: String?,
      interfaces: Array<out String>?,
  ) {
    owner = name
    super.visit(version, access, name, signature, superName, interfaces)
  }

  override fun visitMethod(
      access: Int,
      name: String,
      descriptor: String,
      signature: String?,
      exceptions: Array<out String>?,
  ): MethodVisitor {
    val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
    if (owner != "kotlinx/coroutines/JobSupport") return mv
    if (name == "<init>" && descriptor == "(Z)V") {
      foundConstructor = true
      val exit =
          MethodExitVisitor(mv, null, access, name, descriptor, true, false, false, owner) { v, _ ->
            v.invokeStatic(tracker, Method("created", "(Ljava/lang/Object;)V"))
          }
      return object : AdviceAdapter(Opcodes.ASM9, exit, access, name, descriptor) {
        override fun onMethodEnter() {
          loadThis()
          invokeStatic(tracker, Method("constructingEnter", "(Ljava/lang/Object;)V"))
        }
      }
    }
    if (name == "initParentJob" && descriptor == "(Lkotlinx/coroutines/Job;)V") {
      foundParent = true
      return MethodExitVisitor(mv, null, access, name, descriptor, true, true, false, owner) { v, _
        ->
        v.invokeStatic(
            tracker,
            Method("parentInitialized", "(Ljava/lang/Object;Ljava/lang/Object;)V"),
        )
      }
    }
    if (
        name == "completeStateFinalization" &&
            descriptor == "(Lkotlinx/coroutines/Incomplete;Ljava/lang/Object;)V"
    ) {
      foundFinalization = true
      val exit =
          MethodExitVisitor(mv, null, access, name, descriptor, true, false, true, owner) {
              v,
              failed ->
            v.loadArg(1)
            v.push(failed)
            v.invokeStatic(
                tracker,
                Method("finalizationExit", "(Ljava/lang/Object;Ljava/lang/Object;Z)V"),
            )
          }
      return object : AdviceAdapter(Opcodes.ASM9, exit, access, name, descriptor) {
        override fun onMethodEnter() {
          loadThis()
          invokeStatic(tracker, Method("finalizationEnter", "(Ljava/lang/Object;)V"))
        }
      }
    }
    return mv
  }

  override fun visitEnd() {
    if (
        owner == "kotlinx/coroutines/JobSupport" &&
            !(foundConstructor && foundParent && foundFinalization)
    ) {
      throw IllegalStateException("Unsupported kotlinx.coroutines JobSupport ABI")
    }
    super.visitEnd()
  }
}
