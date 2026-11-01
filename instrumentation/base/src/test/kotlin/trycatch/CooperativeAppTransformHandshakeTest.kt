package trycatch

import java.security.MessageDigest
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

/**
 * Opt-in handshake tests. The fixed trusted-process contract must prohibit later transformers: a
 * marker from Fray cannot detect a different agent modifying bytes after Fray returns them.
 */
class CooperativeAppTransformHandshakeTest {
  private val name = "example/app/Fixture"
  private val coroutineName = "kotlinx/coroutines/owned/Example"

  private fun fixture(marker: String = "first", internalName: String = name): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(
        Opcodes.V11,
        Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
        internalName,
        null,
        "java/lang/Object",
        null,
    )
    writer
        .visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "counter", "I", null, null)
        .visitEnd()
    val method =
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, marker, "()V", null, null)
    method.visitCode()
    method.visitFieldInsn(Opcodes.GETSTATIC, internalName, "counter", "I")
    method.visitInsn(Opcodes.POP)
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

  private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it.toInt() and 0xff)
      }

  private fun methods(bytes: ByteArray): Set<String> {
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
              ): MethodVisitor? {
                result += name
                return null
              }
            },
            0,
        )
    return result
  }

  private fun hasRuntimeHook(bytes: ByteArray, target: String): Boolean {
    var found = false
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
                      method: String,
                      desc: String,
                      isInterface: Boolean,
                  ) {
                    if (owner == "org/pastalab/fray/runtime/Runtime" && method == target)
                        found = true
                  }
                }
              }
            },
            0,
        )
    return found
  }

  private fun withOwner(bytes: ByteArray, body: (ClassLoader) -> Unit) {
    val loader = object : ClassLoader(javaClass.classLoader) {}
    CoroutineInstrumentationOwners.register(loader)
    try {
      CoroutineInstrumentationOwners.registerAppGraph(loader, mapOf(name to sha256(bytes)))
      body(loader)
    } finally {
      CoroutineInstrumentationOwners.unregister(loader)
    }
  }

  private fun withTwoClassOwner(body: (ClassLoader, ByteArray, String, ByteArray) -> Unit) {
    val loader = object : ClassLoader(javaClass.classLoader) {}
    val first = fixture()
    val otherName = "example/app/Other"
    val other = fixture(internalName = otherName)
    CoroutineInstrumentationOwners.register(loader)
    try {
      CoroutineInstrumentationOwners.registerAppGraph(
          loader,
          mapOf(name to sha256(first), otherName to sha256(other)),
      )
      body(loader, first, otherName, other)
    } finally {
      CoroutineInstrumentationOwners.unregister(loader)
    }
  }

  @Test
  fun validOwnedInputIsTransformedAndDoesNotUseStaleOrdinaryCache() {
    val old = fixture("stale")
    val wanted = fixture("fresh")
    val transformer =
        ApplicationCodeTransformer(
            interleaveAllMemoryOps = true,
            excludedClassPrefixes = emptyList(),
        )
    // Ordinary classes historically use (source location + name) as cache key. Prime that exact
    // null-CodeSource key with different transformed bytes and ensure owned app input bypasses it.
    val ordinaryLoader = object : ClassLoader(javaClass.classLoader) {}
    val cached = transformer.transform(ordinaryLoader, name, null, null, old)
    assertTrue("stale" in methods(cached))
    withOwner(wanted) { owner ->
      CoroutineInstrumentationOwners.beginAppDefinition(owner, name)
      val actual = transformer.transform(owner, name, null, null, wanted)
      assertTrue("fresh" in methods(actual))
      assertFalse("stale" in methods(actual))
      assertTrue(hasRuntimeHook(actual, "onThreadParkNanos"))
      CoroutineInstrumentationOwners.checkAppTransformed(owner, name)
      CoroutineInstrumentationOwners.finishAppDefinition(owner, name)
      CoroutineInstrumentationOwners.checkHealthy(owner)
    }
  }

  @Test
  fun absentTransformerPoisonsInsteadOfAcceptingOriginalBytes() {
    withOwner(fixture()) { owner ->
      CoroutineInstrumentationOwners.beginAppDefinition(owner, name)
      assertFailsWith<IllegalStateException> {
        CoroutineInstrumentationOwners.checkAppTransformed(owner, name)
      }
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(owner) }
    }
  }

  @Test
  fun differentOrEarlierRogueInputPoisonsEvenWhenSameClassName() {
    val expected = fixture("expected")
    val modifiedByEarlierTransformer = fixture("rogue")
    withOwner(expected) { owner ->
      val transformer =
          ApplicationCodeTransformer(
              interleaveAllMemoryOps = true,
              excludedClassPrefixes = emptyList(),
          )
      CoroutineInstrumentationOwners.beginAppDefinition(owner, name)
      transformer.transform(owner, name, null, null, modifiedByEarlierTransformer)
      assertFailsWith<IllegalStateException> {
        CoroutineInstrumentationOwners.checkAppTransformed(owner, name)
      }
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(owner) }
    }
  }

  @Test
  fun exclusionOfOwnedClassIsRejectedAndPoisons() {
    val original = fixture()
    withOwner(original) { owner ->
      val transformer =
          ApplicationCodeTransformer(
              interleaveAllMemoryOps = true,
              excludedClassPrefixes = listOf("example.app"),
          )
      CoroutineInstrumentationOwners.beginAppDefinition(owner, name)
      assertContentEquals(original, transformer.transform(owner, name, null, null, original))
      assertFailsWith<IllegalStateException> {
        CoroutineInstrumentationOwners.checkAppTransformed(owner, name)
      }
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(owner) }
    }
  }

  @Test
  fun transformationFailurePoisonsEvenIfJvmSuppressesTransformerException() {
    val malformed = byteArrayOf(0, 1, 2, 3)
    withOwner(malformed) { owner ->
      val transformer =
          ApplicationCodeTransformer(
              interleaveAllMemoryOps = true,
              excludedClassPrefixes = emptyList(),
          )
      CoroutineInstrumentationOwners.beginAppDefinition(owner, name)
      transformer.transform(owner, name, null, null, malformed)
      assertFailsWith<IllegalStateException> {
        CoroutineInstrumentationOwners.checkAppTransformed(owner, name)
      }
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(owner) }
    }
  }

  @Test
  fun classRedefinitionIsRejectedEvenWhenInputDigestMatches() {
    val original = fixture()
    withOwner(original) { owner ->
      val transformer =
          ApplicationCodeTransformer(
              interleaveAllMemoryOps = true,
              excludedClassPrefixes = emptyList(),
          )
      CoroutineInstrumentationOwners.beginAppDefinition(owner, name)
      transformer.transform(owner, name, Any::class.java, null, original)
      assertFailsWith<IllegalStateException> {
        CoroutineInstrumentationOwners.checkAppTransformed(owner, name)
      }
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(owner) }
    }
  }

  @Test
  fun wrongClassIdentityPoisonsEvenIfItsInputMatchesTheSuppliedPin() {
    val original = fixture(internalName = "example/app/Different")
    withOwner(original) { owner ->
      val transformer =
          ApplicationCodeTransformer(
              interleaveAllMemoryOps = true,
              excludedClassPrefixes = emptyList(),
          )
      CoroutineInstrumentationOwners.beginAppDefinition(owner, name)
      transformer.transform(owner, name, null, null, original)
      assertFailsWith<IllegalStateException> {
        CoroutineInstrumentationOwners.checkAppTransformed(owner, name)
      }
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(owner) }
    }
  }

  @Test
  fun unexpectedClassAndRepeatedTransformationAreRejected() {
    val original = fixture()
    withOwner(original) { owner ->
      val transformer =
          ApplicationCodeTransformer(
              interleaveAllMemoryOps = true,
              excludedClassPrefixes = emptyList(),
          )
      CoroutineInstrumentationOwners.beginAppDefinition(owner, name)
      transformer.transform(owner, "example/app/Unregistered", null, null, original)
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(owner) }
    }
    withOwner(original) { owner ->
      val transformer =
          ApplicationCodeTransformer(
              interleaveAllMemoryOps = true,
              excludedClassPrefixes = emptyList(),
          )
      CoroutineInstrumentationOwners.beginAppDefinition(owner, name)
      transformer.transform(owner, name, null, null, original)
      CoroutineInstrumentationOwners.checkAppTransformed(owner, name)
      CoroutineInstrumentationOwners.finishAppDefinition(owner, name)
      transformer.transform(owner, name, null, null, original)
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(owner) }
    }
  }

  @Test
  fun postSealAppDefinitionAndTransformerCallbackAreRejected() {
    withTwoClassOwner { owner, original, otherName, _ ->
      val transformer = ApplicationCodeTransformer(excludedClassPrefixes = emptyList())
      CoroutineInstrumentationOwners.beginAppDefinition(owner, name)
      transformer.transform(owner, name, null, null, original)
      CoroutineInstrumentationOwners.checkAppTransformed(owner, name)
      CoroutineInstrumentationOwners.finishAppDefinition(owner, name)
      CoroutineInstrumentationOwners.checkStrictBoundary(owner, name)
      assertFailsWith<IllegalStateException> {
        CoroutineInstrumentationOwners.beginAppDefinition(owner, otherName)
      }
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(owner) }
    }
    withTwoClassOwner { owner, original, otherName, other ->
      val transformer = ApplicationCodeTransformer(excludedClassPrefixes = emptyList())
      CoroutineInstrumentationOwners.beginAppDefinition(owner, name)
      transformer.transform(owner, name, null, null, original)
      CoroutineInstrumentationOwners.checkAppTransformed(owner, name)
      CoroutineInstrumentationOwners.finishAppDefinition(owner, name)
      CoroutineInstrumentationOwners.checkStrictBoundary(owner, name)
      transformer.transform(owner, otherName, null, null, other)
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(owner) }
    }
  }

  @Test
  fun sealRejectsAnAppDefinitionReservedButNotCompleted() {
    withTwoClassOwner { owner, original, otherName, _ ->
      val transformer = ApplicationCodeTransformer(excludedClassPrefixes = emptyList())
      CoroutineInstrumentationOwners.beginAppDefinition(owner, name)
      transformer.transform(owner, name, null, null, original)
      CoroutineInstrumentationOwners.checkAppTransformed(owner, name)
      CoroutineInstrumentationOwners.finishAppDefinition(owner, name)
      CoroutineInstrumentationOwners.beginAppDefinition(owner, otherName)
      assertFailsWith<IllegalStateException> {
        CoroutineInstrumentationOwners.checkStrictBoundary(owner, name)
      }
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(owner) }
    }
  }

  @Test
  fun postSealCoroutineReservationAndTransformerCallbackAreRejected() {
    val app = fixture()
    val coroutine = fixture(internalName = coroutineName)
    withOwner(app) { owner ->
      val transformer = ApplicationCodeTransformer(excludedClassPrefixes = emptyList())
      CoroutineInstrumentationOwners.beginAppDefinition(owner, name)
      transformer.transform(owner, name, null, null, app)
      CoroutineInstrumentationOwners.checkAppTransformed(owner, name)
      CoroutineInstrumentationOwners.finishAppDefinition(owner, name)
      CoroutineInstrumentationOwners.checkStrictBoundary(owner, name)
      assertFailsWith<IllegalStateException> {
        CoroutineInstrumentationOwners.expectCoroutineInput(owner, coroutineName, coroutine)
      }
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(owner) }
    }
    withOwner(app) { owner ->
      val transformer = ApplicationCodeTransformer(excludedClassPrefixes = emptyList())
      CoroutineInstrumentationOwners.beginAppDefinition(owner, name)
      transformer.transform(owner, name, null, null, app)
      CoroutineInstrumentationOwners.checkAppTransformed(owner, name)
      CoroutineInstrumentationOwners.finishAppDefinition(owner, name)
      CoroutineInstrumentationOwners.checkStrictBoundary(owner, name)
      transformer.transform(owner, coroutineName, null, null, coroutine)
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(owner) }
    }
  }

  @Test
  fun laterRogueTransformerCannotBeDetectedByThisMarkerAlone() {
    val original = fixture("checked")
    withOwner(original) { owner ->
      val transformer =
          ApplicationCodeTransformer(
              interleaveAllMemoryOps = true,
              excludedClassPrefixes = emptyList(),
          )
      CoroutineInstrumentationOwners.beginAppDefinition(owner, name)
      val frayOutput = transformer.transform(owner, name, null, null, original)
      // Simulate an untrusted later transformer replacing Fray's returned bytes. The registry has
      // no access to final JVM bytes, so the marker still succeeds: process policy must forbid it.
      val laterTransformerOutput = fixture("rogueLater")
      assertTrue("checked" in methods(frayOutput))
      assertTrue("rogueLater" in methods(laterTransformerOutput))
      CoroutineInstrumentationOwners.checkAppTransformed(owner, name)
      CoroutineInstrumentationOwners.finishAppDefinition(owner, name)
    }
  }

  @Test
  fun ownedAppInstrumentsNonvolatileFieldsWhenGlobalAllMemoryIsDisabled() {
    val original = fixture()
    withOwner(original) { owner ->
      val transformer =
          ApplicationCodeTransformer(
              interleaveAllMemoryOps = false,
              excludedClassPrefixes = emptyList(),
          )
      CoroutineInstrumentationOwners.beginAppDefinition(owner, name)
      val actual = transformer.transform(owner, name, null, null, original)
      assertTrue(hasRuntimeHook(actual, "onStaticFieldRead"))
      CoroutineInstrumentationOwners.checkAppTransformed(owner, name)
      CoroutineInstrumentationOwners.finishAppDefinition(owner, name)
      CoroutineInstrumentationOwners.checkHealthy(owner)
    }
  }

  @Test
  fun strictOwnedCoroutineRequiresAndConsumesExactReservedInput() {
    val coroutine = fixture(internalName = coroutineName)
    withOwner(fixture()) { owner ->
      val transformer = ApplicationCodeTransformer(excludedClassPrefixes = emptyList())
      CoroutineInstrumentationOwners.expectCoroutineInput(owner, coroutineName, coroutine)
      val result = transformer.transform(owner, coroutineName, null, null, coroutine)
      assertTrue(hasRuntimeHook(result, "onThreadParkNanos"))
      CoroutineInstrumentationOwners.checkTransformed(owner, coroutineName)
      CoroutineInstrumentationOwners.finishCoroutineDefinition(owner, coroutineName)
      CoroutineInstrumentationOwners.checkHealthy(owner)
    }
  }

  @Test
  fun strictOwnedCoroutineRejectsUnreservedOrAlteredInput() {
    val coroutine = fixture(internalName = coroutineName)
    withOwner(fixture()) { owner ->
      val transformer = ApplicationCodeTransformer(excludedClassPrefixes = emptyList())
      transformer.transform(owner, coroutineName, null, null, coroutine)
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(owner) }
      assertFailsWith<IllegalStateException> {
        CoroutineInstrumentationOwners.checkTransformed(owner, coroutineName)
      }
    }
    withOwner(fixture()) { owner ->
      val transformer = ApplicationCodeTransformer(excludedClassPrefixes = emptyList())
      CoroutineInstrumentationOwners.expectCoroutineInput(owner, coroutineName, coroutine)
      transformer.transform(owner, coroutineName, null, null, fixture("rogue", coroutineName))
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(owner) }
      assertFailsWith<IllegalStateException> {
        CoroutineInstrumentationOwners.checkTransformed(owner, coroutineName)
      }
    }
  }

  @Test
  fun strictOwnedCoroutineRejectsRedefinitionAndRepeatedTransformation() {
    val coroutine = fixture(internalName = coroutineName)
    withOwner(fixture()) { owner ->
      val transformer = ApplicationCodeTransformer(excludedClassPrefixes = emptyList())
      CoroutineInstrumentationOwners.expectCoroutineInput(owner, coroutineName, coroutine)
      transformer.transform(owner, coroutineName, Any::class.java, null, coroutine)
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(owner) }
    }
    withOwner(fixture()) { owner ->
      val transformer = ApplicationCodeTransformer(excludedClassPrefixes = emptyList())
      CoroutineInstrumentationOwners.expectCoroutineInput(owner, coroutineName, coroutine)
      transformer.transform(owner, coroutineName, null, null, coroutine)
      CoroutineInstrumentationOwners.checkTransformed(owner, coroutineName)
      CoroutineInstrumentationOwners.finishCoroutineDefinition(owner, coroutineName)
      transformer.transform(owner, coroutineName, null, null, coroutine)
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(owner) }
    }
  }

  @Test
  fun strictBoundaryRejectsAcknowledgedCoroutineDefinitionBeforeItsFinish() {
    val app = fixture()
    val coroutine = fixture(internalName = coroutineName)
    withOwner(app) { owner ->
      val transformer = ApplicationCodeTransformer(excludedClassPrefixes = emptyList())
      CoroutineInstrumentationOwners.beginAppDefinition(owner, name)
      transformer.transform(owner, name, null, null, app)
      CoroutineInstrumentationOwners.checkAppTransformed(owner, name)
      CoroutineInstrumentationOwners.finishAppDefinition(owner, name)
      CoroutineInstrumentationOwners.expectCoroutineInput(owner, coroutineName, coroutine)
      transformer.transform(owner, coroutineName, null, null, coroutine)
      CoroutineInstrumentationOwners.checkTransformed(owner, coroutineName)
      // Acknowledging Fray's transformer is not the end of the JVM's defineClass call: a later
      // transformer may still be running. Seal must wait for the explicit finish notification.
      assertFailsWith<IllegalStateException> {
        CoroutineInstrumentationOwners.checkStrictBoundary(owner, name)
      }
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(owner) }
    }
  }

  @Test
  fun strictBoundaryAcceptsOnlyAfterFinishedAppAndCoroutineDefinitions() {
    val app = fixture()
    val coroutine = fixture(internalName = coroutineName)
    withOwner(app) { owner ->
      val transformer = ApplicationCodeTransformer(excludedClassPrefixes = emptyList())
      CoroutineInstrumentationOwners.beginAppDefinition(owner, name)
      transformer.transform(owner, name, null, null, app)
      CoroutineInstrumentationOwners.checkAppTransformed(owner, name)
      CoroutineInstrumentationOwners.finishAppDefinition(owner, name)
      CoroutineInstrumentationOwners.expectCoroutineInput(owner, coroutineName, coroutine)
      transformer.transform(owner, coroutineName, null, null, coroutine)
      CoroutineInstrumentationOwners.checkTransformed(owner, coroutineName)
      CoroutineInstrumentationOwners.finishCoroutineDefinition(owner, coroutineName)
      CoroutineInstrumentationOwners.checkStrictBoundary(owner, name)
      CoroutineInstrumentationOwners.checkHealthy(owner)
    }
  }

  @Test
  fun strictOwnedCoroutineRejectsWrongDeclaredClassIdentity() {
    val coroutine = fixture(internalName = "kotlinx/coroutines/owned/Different")
    withOwner(fixture()) { owner ->
      val transformer = ApplicationCodeTransformer(excludedClassPrefixes = emptyList())
      CoroutineInstrumentationOwners.expectCoroutineInput(owner, coroutineName, coroutine)
      transformer.transform(owner, coroutineName, null, null, coroutine)
      assertFailsWith<IllegalStateException> { CoroutineInstrumentationOwners.checkHealthy(owner) }
      assertFailsWith<IllegalStateException> {
        CoroutineInstrumentationOwners.checkTransformed(owner, coroutineName)
      }
    }
  }
}
