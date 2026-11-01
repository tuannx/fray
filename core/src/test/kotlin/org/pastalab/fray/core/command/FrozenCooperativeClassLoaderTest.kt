package org.pastalab.fray.core.command

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.pastalab.fray.instrumentation.base.CooperativeClosedGraph
import org.pastalab.fray.instrumentation.base.PinnedCooperativeClass
import org.pastalab.fray.runtime.CoroutineInstrumentationOwners

/** The worker is still unsupported. These tests exercise denial paths without a Java agent. */
class FrozenCooperativeClassLoaderTest {
  private val root = "example/app/FrozenFixture"

  private fun graph(): org.pastalab.fray.instrumentation.base.VerifiedCooperativeGraph {
    val writer = ClassWriter(0)
    writer.visit(
        Opcodes.V11,
        Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
        root,
        null,
        "java/lang/Object",
        null,
    )
    val method =
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()V", null, null)
    method.visitCode()
    method.visitInsn(Opcodes.RETURN)
    method.visitMaxs(0, 0)
    method.visitEnd()
    writer.visitEnd()
    val bytes = writer.toByteArray()
    val hash =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
          "%02x".format(it.toInt() and 0xff)
        }
    return CooperativeClosedGraph.verify(
        listOf(PinnedCooperativeClass(root, hash, bytes)),
        root,
        "run",
    )
  }

  private fun loader(): FrayClassLoader =
      FrayClassLoader(
          emptyArray(),
          javaClass.classLoader,
          false,
          graph(),
          mapOf("java.lang.Object" to Any::class.java),
      )

  @Test
  fun explicitlyTrustedRuntimeClassIsDelegatedByIdentity() {
    loader().use { frozen ->
      assertEquals(Any::class.java, frozen.loadClass("java.lang.Object"))
      assertEquals(Any::class.java, frozen.loadClass("java.lang.Object"))
      CoroutineInstrumentationOwners.checkHealthy(frozen)
    }
  }

  @Test
  fun unknownClassCannotFallBackToParentAndPoisonsEpoch() {
    loader().use { frozen ->
      // This class exists on the parent test classpath but was not explicitly trusted.
      assertFailsWith<ClassNotFoundException> { frozen.loadClass(javaClass.name) }
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(frozen) }
    }
  }

  @Test
  fun parentResourceCannotBeReadAndPoisonsEpoch() {
    loader().use { frozen ->
      assertFailsWith<IllegalStateException> {
        frozen.getResource("META-INF/services/example.Service")
      }
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(frozen) }
    }
  }

  @Test
  fun publicFindResourceCannotBypassFrozenResourceBlock() {
    loader().use { frozen ->
      assertFailsWith<IllegalStateException> { frozen.findResource("example/data") }
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(frozen) }
    }
    loader().use { frozen ->
      assertFailsWith<IllegalStateException> {
        frozen.findResources("META-INF/services/example.Service")
      }
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(frozen) }
    }
  }

  @Test
  fun missingAgentAcknowledgementFailsAfterDefineWithoutParentFallback() {
    loader().use { frozen ->
      // core:test has no javaagent; defining frozen bytes cannot be accepted without the callback.
      assertFailsWith<IllegalStateException> { frozen.loadClass(root.replace('/', '.')) }
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(frozen) }
      assertFailsWith<IllegalStateException> { frozen.loadClass(root.replace('/', '.')) }
    }
  }

  @Test
  fun trustedRuntimeMapCannotShadowFrozenGraph() {
    val frozenGraph = graph()
    val parent =
        object : ClassLoader(javaClass.classLoader) {
          fun defineShadow(): Class<*> {
            val bytes = frozenGraph.classBytes(root)
            return defineClass(root.replace('/', '.'), bytes, 0, bytes.size)
          }
        }
    val sameNameParentClass = parent.defineShadow()
    assertEquals(root.replace('/', '.'), sameNameParentClass.name)
    assertFailsWith<IllegalArgumentException> {
      FrayClassLoader(
          emptyArray(),
          parent,
          false,
          frozenGraph,
          mapOf(root.replace('/', '.') to sameNameParentClass),
      )
    }
  }
}
