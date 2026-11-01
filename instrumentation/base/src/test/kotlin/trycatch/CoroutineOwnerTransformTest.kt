package trycatch

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.pastalab.fray.instrumentation.base.ApplicationCodeTransformer
import org.pastalab.fray.runtime.CoroutineInstrumentationOwners

class CoroutineOwnerTransformTest {
  private val name = "kotlinx/coroutines/owned/Example"

  private fun fixture(name: String): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
    val method =
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "park", "()V", null, null)
    method.visitCode()
    method.visitInsn(Opcodes.LCONST_1)
    method.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "java/util/concurrent/locks/LockSupport",
        "parkNanos",
        "(J)V",
        false,
    )
    method.visitInsn(Opcodes.RETURN)
    method.visitMaxs(2, 0)
    method.visitEnd()
    writer.visitEnd()
    return writer.toByteArray()
  }

  private fun calls(bytes: ByteArray): Set<String> {
    val result = mutableSetOf<String>()
    ClassReader(bytes)
        .accept(
            object : ClassVisitor(Opcodes.ASM9) {
              override fun visitMethod(
                  access: Int,
                  name: String,
                  descriptor: String,
                  signature: String?,
                  exceptions: Array<out String>?,
              ): MethodVisitor {
                return object : MethodVisitor(Opcodes.ASM9) {
                  override fun visitMethodInsn(
                      opcode: Int,
                      owner: String,
                      name: String,
                      descriptor: String,
                      isInterface: Boolean,
                  ) {
                    result.add("$owner#$name$descriptor")
                  }
                }
              }
            },
            0,
        )
    return result
  }

  @Test
  fun onlyOwnedLoaderGetsTimedParkRewrite() {
    val owner = object : ClassLoader(javaClass.classLoader) {}
    val host = object : ClassLoader(javaClass.classLoader) {}
    val original = fixture(name)
    val transformer = ApplicationCodeTransformer(excludedClassPrefixes = emptyList())
    CoroutineInstrumentationOwners.register(owner)
    try {
      assertTrue(CoroutineInstrumentationOwners.owns(owner))
      assertFalse(CoroutineInstrumentationOwners.owns(host))
      assertFalse(CoroutineInstrumentationOwners.owns(null))
      val hostCalls = calls(transformer.transform(host, name, null, null, original))
      assertTrue(hostCalls.contains("java/util/concurrent/locks/LockSupport#parkNanos(J)V"))
      assertFalse(hostCalls.contains("org/pastalab/fray/runtime/Runtime#onThreadParkNanos(J)V"))

      val ownedCalls = calls(transformer.transform(owner, name, null, null, original))
      assertFalse(ownedCalls.contains("java/util/concurrent/locks/LockSupport#parkNanos(J)V"))
      assertTrue(ownedCalls.contains("org/pastalab/fray/runtime/Runtime#onThreadParkNanos(J)V"))
      CoroutineInstrumentationOwners.checkHealthy(owner)
      CoroutineInstrumentationOwners.checkTransformed(owner, name)
    } finally {
      CoroutineInstrumentationOwners.unregister(owner)
    }
    assertFalse(CoroutineInstrumentationOwners.owns(owner))
  }

  @Test
  fun competingOwnerAndMismatchedUnregisterAreRejected() {
    val owner = object : ClassLoader(javaClass.classLoader) {}
    val other = object : ClassLoader(javaClass.classLoader) {}
    CoroutineInstrumentationOwners.register(owner)
    try {
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.register(other) }
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.unregister(other) }
      CoroutineInstrumentationOwners.checkHealthy(owner)
    } finally {
      CoroutineInstrumentationOwners.unregister(owner)
    }
    CoroutineInstrumentationOwners.register(other)
    CoroutineInstrumentationOwners.unregister(other)
  }

  @Test
  fun coroutineExclusionPoisonsOwnedEpoch() {
    val owner = object : ClassLoader(javaClass.classLoader) {}
    val original = fixture(name)
    val transformer =
        ApplicationCodeTransformer(excludedClassPrefixes = listOf("kotlinx.coroutines.internal."))
    CoroutineInstrumentationOwners.register(owner)
    try {
      assertContentEquals(original, transformer.transform(owner, name, null, null, original))
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(owner) }
      assertFailsWith<IllegalStateException> {
        CoroutineInstrumentationOwners.checkTransformed(owner, name)
      }
    } finally {
      CoroutineInstrumentationOwners.unregister(owner)
    }
  }

  @Test
  fun missingTransformerPoisonsEpoch() {
    val owner = object : ClassLoader(javaClass.classLoader) {}
    CoroutineInstrumentationOwners.register(owner)
    try {
      assertFailsWith<IllegalStateException> {
        CoroutineInstrumentationOwners.checkTransformed(owner, name)
      }
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(owner) }
    } finally {
      CoroutineInstrumentationOwners.unregister(owner)
    }
  }

  @Test
  fun unsupportedJobAbiPoisonsEpoch() {
    val owner = object : ClassLoader(javaClass.classLoader) {}
    CoroutineInstrumentationOwners.register(owner)
    try {
      val job = "kotlinx/coroutines/JobSupport"
      val original = fixture(job)
      val transformer = ApplicationCodeTransformer(excludedClassPrefixes = emptyList())
      assertContentEquals(original, transformer.transform(owner, job, null, null, original))
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(owner) }
    } finally {
      CoroutineInstrumentationOwners.unregister(owner)
    }
  }
}
