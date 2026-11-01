package trycatch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.pastalab.fray.instrumentation.base.ApplicationCodeTransformer
import org.pastalab.fray.runtime.Delegate
import org.pastalab.fray.runtime.Runtime

class CoroutineInitializerTransformTest {
  private fun fixture(name: String, fails: Boolean): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
    val method = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
    method.visitCode()
    if (fails) {
      method.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException")
      method.visitInsn(Opcodes.DUP)
      method.visitMethodInsn(
          Opcodes.INVOKESPECIAL,
          "java/lang/IllegalStateException",
          "<init>",
          "()V",
          false,
      )
      method.visitInsn(Opcodes.ATHROW)
      method.visitMaxs(2, 0)
    } else {
      method.visitInsn(Opcodes.RETURN)
      method.visitMaxs(0, 0)
    }
    method.visitEnd()
    writer.visitEnd()
    return writer.toByteArray()
  }

  @Test
  fun explicitCoroutineExclusionReturnsOriginalBytes() {
    val name = "kotlinx/coroutines/guardtest/Excluded"
    val bytes = fixture(name, false)
    val transformer =
        ApplicationCodeTransformer(excludedClassPrefixes = listOf("kotlinx.coroutines.guardtest."))
    assertSame(bytes, transformer.transform(null, name, null, null, bytes))
  }

  @Test
  fun initializerRestoresSchedulingOnNormalAndExceptionalExit() {
    val previous = Runtime.LOCK_DELEGATE
    val events = mutableListOf<String>()
    Runtime.LOCK_DELEGATE =
        object : Delegate() {
          override fun onSkipScheduling(signature: String) {
            events.add("enter")
          }

          override fun onSkipSchedulingDone(signature: String) {
            events.add("exit")
          }
        }
    try {
      for (fails in listOf(false, true)) {
        val suffix = if (fails) "Throwing" else "Normal"
        val name = "kotlinx/coroutines/guardtest/$suffix"
        val bytes = fixture(name, fails)
        val transformed =
            ApplicationCodeTransformer(excludedClassPrefixes = emptyList())
                .transform(null, name, null, null, bytes)
        val loader =
            object : ClassLoader(javaClass.classLoader) {
              fun define(): Class<*> =
                  defineClass(name.replace('/', '.'), transformed, 0, transformed.size)
            }
        events.clear()
        val clazz = loader.define()
        if (fails) {
          assertFailsWith<ExceptionInInitializerError> { Class.forName(clazz.name, true, loader) }
        } else {
          Class.forName(clazz.name, true, loader)
        }
        assertEquals(listOf("enter", "exit"), events)
      }
    } finally {
      Runtime.LOCK_DELEGATE = previous
    }
  }
}
