package trycatch

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.pastalab.fray.instrumentation.base.ApplicationCodeTransformer
import org.pastalab.fray.runtime.CoroutineInstrumentationOwners
import org.pastalab.fray.runtime.Delegate
import org.pastalab.fray.runtime.Runtime

/** Legacy class transformation must not enter the opt-in coroutine ownership machinery. */
class LegacyOwnershipProbeTest {
  private fun fixture(name: String): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
    val method =
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()V", null, null)
    method.visitCode()
    method.visitInsn(Opcodes.RETURN)
    method.visitMaxs(0, 0)
    method.visitEnd()
    writer.visitEnd()
    return writer.toByteArray()
  }

  @Test
  fun unownedAndExcludedClassesDoNotInvokeCoroutineOwnershipHooks() {
    val host = object : ClassLoader(javaClass.classLoader) {}
    val transformer = ApplicationCodeTransformer(excludedClassPrefixes = listOf("example.excluded"))
    val names =
        listOf(
            "example/normal/LegacyProbe",
            "example/excluded/LegacyProbe",
            "kotlin/LegacyProbe",
            "kotlinx/coroutines/LegacyProbe",
        )
    val inputs = names.associateWith(::fixture)
    // Initialize the existing registry before installing the spy. These tests run without an
    // active owned loader, and the original delegate must always be restored.
    CoroutineInstrumentationOwners.owns(null)
    val oldDelegate = Runtime.LOCK_DELEGATE
    val ownershipEvents = mutableListOf<String>()
    val legacyEvents = mutableListOf<String>()
    val spy =
        object : Delegate() {
          override fun onSkipPrimitive(signature: String) {
            if (signature == "coroutine instrumentation ownership") ownershipEvents.add("begin")
            if (signature == "instrumentation") legacyEvents.add("begin")
          }

          override fun onSkipPrimitiveDone(signature: String) {
            if (signature == "coroutine instrumentation ownership") ownershipEvents.add("end")
            if (signature == "instrumentation") legacyEvents.add("end")
          }
        }
    Runtime.LOCK_DELEGATE = spy
    try {
      for (name in names) {
        val input = inputs.getValue(name)
        val output = transformer.transform(host, name, null, null, input)
        if (name.startsWith("example/excluded/") || name.startsWith("kotlin/")) {
          assertContentEquals(input, output, "legacy exclusion changed: $name")
        }
      }
    } finally {
      Runtime.LOCK_DELEGATE = oldDelegate
    }
    assertTrue(legacyEvents.containsAll(listOf("begin", "end")), "legacy transform did not run")
    assertEquals(
        emptyList(),
        ownershipEvents,
        "unowned legacy class invoked coroutine ownership hooks",
    )
  }
}
