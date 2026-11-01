package org.pastalab.fray.instrumentation.base

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Attribute
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.ModuleVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.RecordComponentVisitor
import org.objectweb.asm.Type
import org.objectweb.asm.TypePath

/**
 * A deliberately tiny, unwired admission experiment, NOT a JVM sandbox or coroutine support.
 *
 * It accepts only a final class with no fields/interfaces/annotations, an optional trivial Object
 * constructor, and static methods using primitive values and integer/long Math.abs. It
 * intentionally rejects all coroutine bytecode, references, virtual calls, dynamic code, exception
 * handlers and Kotlin metadata. Any future widening needs explicit provenance and trusted-runtime
 * proofs; callers must still define exactly the verified bytes in a disposable process and impose
 * execution/time/resource limits.
 */
object RestrictedCoroutineAppVerifier {
  private const val MAX_CLASS_BYTES = 1_000_000
  private const val MAX_METHODS = 100
  private const val MAX_FRAME_SLOTS = 256
  private val forbiddenPrefixes =
      listOf(
          "java/",
          "javax/",
          "jdk/",
          "sun/",
          "com/sun/",
          "kotlin/",
          "kotlinx/",
          "org/pastalab/fray/",
      )

  @JvmStatic
  fun verify(expectedName: String, bytes: ByteArray) {
    if (
        expectedName.isEmpty() ||
            expectedName.contains('.') ||
            forbiddenPrefixes.any(expectedName::startsWith) ||
            bytes.size > MAX_CLASS_BYTES
    ) {
      reject("invalid or reserved application class")
    }
    try {
      ClassReader(bytes)
          .accept(
              PrimitiveClassVisitor(expectedName),
              ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
          )
    } catch (e: IllegalArgumentException) {
      throw e
    } catch (e: RuntimeException) {
      throw IllegalArgumentException("invalid application class", e)
    }
  }

  private fun reject(reason: String): Nothing = throw IllegalArgumentException(reason)

  private fun primitive(type: Type, allowVoid: Boolean): Boolean =
      type.sort in Type.BOOLEAN..Type.DOUBLE || (allowVoid && type.sort == Type.VOID)

  private class PrimitiveClassVisitor(private val expectedName: String) :
      ClassVisitor(Opcodes.ASM9) {
    private var seenClass = false
    private var methodCount = 0

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) {
      val major = version and 0xffff
      val minor = version ushr 16
      if (
          seenClass ||
              minor != 0 ||
              major !in 55..69 ||
              name != expectedName ||
              signature != null ||
              superName != "java/lang/Object" ||
              !interfaces.isNullOrEmpty() ||
              access and Opcodes.ACC_FINAL == 0 ||
              access and
                  (Opcodes.ACC_INTERFACE or
                      Opcodes.ACC_ANNOTATION or
                      Opcodes.ACC_ENUM or
                      Opcodes.ACC_MODULE or
                      Opcodes.ACC_ABSTRACT or
                      Opcodes.ACC_RECORD) != 0
      ) {
        reject("unsupported class declaration")
      }
      seenClass = true
    }

    override fun visitModule(name: String?, access: Int, version: String?): ModuleVisitor? =
        reject("modules are unsupported")

    override fun visitNestHost(nestHost: String?) = reject("nest hosts are unsupported")

    override fun visitNestMember(nestMember: String?) = reject("nest members are unsupported")

    override fun visitOuterClass(owner: String?, name: String?, descriptor: String?) =
        reject("outer classes are unsupported")

    override fun visitInnerClass(
        name: String?,
        outerName: String?,
        innerName: String?,
        access: Int,
    ) = reject("inner classes are unsupported")

    override fun visitPermittedSubclass(permittedSubclass: String?) =
        reject("permitted subclasses are unsupported")

    override fun visitRecordComponent(
        name: String?,
        descriptor: String?,
        signature: String?,
    ): RecordComponentVisitor? = reject("records are unsupported")

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? =
        reject("annotations are unsupported")

    override fun visitTypeAnnotation(
        typeRef: Int,
        typePath: TypePath?,
        descriptor: String?,
        visible: Boolean,
    ): AnnotationVisitor? = reject("annotations are unsupported")

    override fun visitAttribute(attribute: Attribute?) = reject("custom attributes are unsupported")

    override fun visitField(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        value: Any?,
    ): FieldVisitor? = reject("fields are unsupported")

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
      if (
          !seenClass ||
              ++methodCount > MAX_METHODS ||
              signature != null ||
              !exceptions.isNullOrEmpty() ||
              access and (Opcodes.ACC_NATIVE or Opcodes.ACC_ABSTRACT or Opcodes.ACC_SYNCHRONIZED) !=
                  0 ||
              name == "<clinit>"
      ) {
        reject("unsupported method declaration")
      }
      val constructor = name == "<init>"
      if (constructor) {
        if (descriptor != "()V" || access and Opcodes.ACC_STATIC != 0)
            reject("unsupported constructor")
      } else {
        if (access and Opcodes.ACC_STATIC == 0 || name.startsWith("<"))
            reject("only static methods are supported")
        val method = Type.getMethodType(descriptor)
        if (
            !primitive(method.returnType, true) ||
                method.argumentTypes.any { !primitive(it, false) }
        ) {
          reject("reference method signatures are unsupported")
        }
      }
      return PrimitiveMethodVisitor(constructor)
    }

    override fun visitEnd() {
      if (!seenClass || methodCount == 0) reject("empty or invalid application class")
    }
  }

  private class PrimitiveMethodVisitor(private val constructor: Boolean) :
      MethodVisitor(Opcodes.ASM9) {
    private var constructorStep = 0
    private var returnSeen = false

    override fun visitAnnotationDefault(): AnnotationVisitor? =
        reject("annotations are unsupported")

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? =
        reject("annotations are unsupported")

    override fun visitTypeAnnotation(
        typeRef: Int,
        typePath: TypePath?,
        descriptor: String?,
        visible: Boolean,
    ): AnnotationVisitor? = reject("annotations are unsupported")

    override fun visitParameterAnnotation(
        parameter: Int,
        descriptor: String?,
        visible: Boolean,
    ): AnnotationVisitor? = reject("annotations are unsupported")

    override fun visitInsnAnnotation(
        typeRef: Int,
        typePath: TypePath?,
        descriptor: String?,
        visible: Boolean,
    ): AnnotationVisitor? = reject("annotations are unsupported")

    override fun visitTryCatchAnnotation(
        typeRef: Int,
        typePath: TypePath?,
        descriptor: String?,
        visible: Boolean,
    ): AnnotationVisitor? = reject("annotations are unsupported")

    override fun visitLocalVariableAnnotation(
        typeRef: Int,
        typePath: TypePath?,
        start: Array<out Label>?,
        end: Array<out Label>?,
        index: IntArray?,
        descriptor: String?,
        visible: Boolean,
    ): AnnotationVisitor? = reject("annotations are unsupported")

    override fun visitAttribute(attribute: Attribute?) = reject("custom attributes are unsupported")

    override fun visitVarInsn(opcode: Int, variable: Int) {
      if (constructor) {
        if (constructorStep != 0 || opcode != Opcodes.ALOAD || variable != 0)
            reject("unsupported constructor body")
        constructorStep = 1
      } else if (
          variable !in 0 until MAX_FRAME_SLOTS ||
              (opcode !in Opcodes.ILOAD..Opcodes.DLOAD && opcode !in Opcodes.ISTORE..Opcodes.DSTORE)
      ) {
        reject("reference or unsupported local operation")
      }
    }

    override fun visitInsn(opcode: Int) {
      if (constructor) {
        if (constructorStep != 2 || opcode != Opcodes.RETURN) reject("unsupported constructor body")
        constructorStep = 3
        returnSeen = true
        return
      }
      if (
          opcode == Opcodes.NOP ||
              opcode in Opcodes.ICONST_M1..Opcodes.DCONST_1 ||
              opcode in Opcodes.POP..Opcodes.SWAP ||
              opcode in Opcodes.IADD..Opcodes.LXOR ||
              opcode in Opcodes.I2L..Opcodes.DCMPG ||
              opcode in Opcodes.IRETURN..Opcodes.RETURN
      ) {
        if (opcode in Opcodes.IRETURN..Opcodes.RETURN) returnSeen = true
        return
      }
      reject("unsupported opcode $opcode")
    }

    override fun visitIntInsn(opcode: Int, operand: Int) {
      if (constructor || (opcode != Opcodes.BIPUSH && opcode != Opcodes.SIPUSH))
          reject("unsupported integer opcode")
    }

    override fun visitIincInsn(variable: Int, increment: Int) {
      if (constructor || variable !in 0 until MAX_FRAME_SLOTS) reject("unsupported increment")
    }

    override fun visitJumpInsn(opcode: Int, label: Label?) {
      if (constructor || (opcode !in Opcodes.IFEQ..Opcodes.IF_ICMPLE && opcode != Opcodes.GOTO))
          reject("unsupported branch")
    }

    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label?, vararg labels: Label?) {
      if (constructor) reject("unsupported constructor switch")
    }

    override fun visitLookupSwitchInsn(dflt: Label?, keys: IntArray?, labels: Array<out Label>?) {
      if (constructor) reject("unsupported constructor switch")
    }

    override fun visitLdcInsn(value: Any?) {
      if (constructor || value !is Int && value !is Long && value !is Float && value !is Double)
          reject("unsupported constant")
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?,
        isInterface: Boolean,
    ) {
      if (constructor) {
        if (
            constructorStep != 1 ||
                opcode != Opcodes.INVOKESPECIAL ||
                owner != "java/lang/Object" ||
                name != "<init>" ||
                descriptor != "()V" ||
                isInterface
        )
            reject("unsupported constructor call")
        constructorStep = 2
        return
      }
      if (
          opcode != Opcodes.INVOKESTATIC ||
              owner != "java/lang/Math" ||
              name != "abs" ||
              descriptor !in listOf("(I)I", "(J)J") ||
              isInterface
      )
          reject("unsupported call")
    }

    override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String?) =
        reject("field access is unsupported")

    override fun visitTypeInsn(opcode: Int, type: String?) =
        reject("type operations are unsupported")

    override fun visitMultiANewArrayInsn(descriptor: String?, numDimensions: Int) =
        reject("arrays are unsupported")

    override fun visitInvokeDynamicInsn(
        name: String?,
        descriptor: String?,
        bootstrapMethodHandle: Handle?,
        vararg bootstrapMethodArguments: Any?,
    ) = reject("invokedynamic is unsupported")

    override fun visitTryCatchBlock(start: Label?, end: Label?, handler: Label?, type: String?) =
        reject("exception handlers are unsupported")

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
      if (maxStack > MAX_FRAME_SLOTS || maxLocals > MAX_FRAME_SLOTS)
          reject("method frame too large")
    }

    override fun visitEnd() {
      if (!returnSeen || (constructor && constructorStep != 3)) reject("incomplete method")
    }
  }
}
