package org.pastalab.fray.core.command

import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.pastalab.fray.runtime.CoroutineInstrumentationOwners

class OwnedCoroutineClassLoaderTest {
  private fun fixture(internalName: String): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
    writer.visitEnd()
    return writer.toByteArray()
  }

  @Test
  fun ownedRuntimeDoesNotFallBackToParent() {
    val binaryName = "kotlinx.coroutines.guardtest.OnlyInParent"
    val bytes = fixture(binaryName.replace('.', '/'))
    val parent =
        object : ClassLoader(javaClass.classLoader) {
          fun define(): Class<*> = defineClass(binaryName, bytes, 0, bytes.size)
        }
    val parentClass = parent.define()
    FrayClassLoader(emptyArray(), parent).use { ordinary ->
      assertEquals(parentClass, ordinary.loadClass(binaryName))
    }
    // Host-only classes cannot satisfy the required child JobSupport pin.
    assertFailsWith<IllegalArgumentException> {
      FrayClassLoader(emptyArray(), parent, ownedCoroutineRuntime = true)
    }
  }

  @Test
  fun remoteClasspathIsRejectedBeforeRegistration() {
    assertFailsWith<IllegalArgumentException> {
      FrayClassLoader(
          arrayOf(URL("https://example.invalid/coroutines.jar")),
          javaClass.classLoader,
          true,
      )
    }
    val anotherOwner = object : ClassLoader(javaClass.classLoader) {}
    CoroutineInstrumentationOwners.register(anotherOwner)
    CoroutineInstrumentationOwners.unregister(anotherOwner)
  }
}
