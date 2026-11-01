package org.pastalab.fray.core.command

import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Collections
import java.util.Enumeration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.pastalab.fray.instrumentation.base.CooperativeClosedGraph
import org.pastalab.fray.instrumentation.base.PinnedCooperativeClass
import org.pastalab.fray.runtime.CoroutineInstrumentationOwners

/** Synthetic source-identity tests only; real runtime/JDK pins need independent integration. */
class FrozenTrustedRuntimeResolverTest {
  @TempDir lateinit var temp: Path

  private val stdName = "kotlin/fake/Trusted"
  private val helperName = "_COROUTINE/Fake"

  private fun classBytes(internalName: String, entrypoint: Boolean = false): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(
        Opcodes.V11,
        Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
        internalName,
        null,
        "java/lang/Object",
        null,
    )
    val constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
    constructor.visitCode()
    constructor.visitVarInsn(Opcodes.ALOAD, 0)
    constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
    constructor.visitInsn(Opcodes.RETURN)
    constructor.visitMaxs(1, 1)
    constructor.visitEnd()
    if (entrypoint) {
      val method =
          writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()V", null, null)
      method.visitCode()
      method.visitInsn(Opcodes.RETURN)
      method.visitMaxs(0, 0)
      method.visitEnd()
    }
    writer.visitEnd()
    return writer.toByteArray()
  }

  private fun writeJar(path: Path, entries: Map<String, ByteArray>) {
    ZipOutputStream(Files.newOutputStream(path)).use { zip ->
      entries.forEach { (name, bytes) ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
      }
    }
  }

  private fun sha256(path: Path): String =
      MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") {
        "%02x".format(it.toInt() and 0xff)
      }

  private fun stdEntries(): Map<String, ByteArray> = mapOf("$stdName.class" to classBytes(stdName))

  private fun coroutineEntries(): Map<String, ByteArray> =
      mapOf("$helperName.class" to classBytes(helperName))

  private fun files(
      std: Map<String, ByteArray> = stdEntries(),
      coroutine: Map<String, ByteArray> = coroutineEntries(),
  ): Pair<Path, Path> {
    val stdlib = temp.resolve("stdlib.jar")
    val coroutines = temp.resolve("coroutines.jar")
    writeJar(stdlib, std)
    writeJar(coroutines, coroutine)
    return stdlib to coroutines
  }

  private fun resolver(
      parent: ClassLoader,
      stdlib: Path,
      coroutines: Path,
  ): FrozenTrustedRuntimeResolver =
      FrozenTrustedRuntimeResolver.forTesting(
          parent,
          stdlib,
          coroutines,
          sha256(stdlib),
          sha256(coroutines),
      )

  private fun appGraph(): org.pastalab.fray.instrumentation.base.VerifiedCooperativeGraph {
    val name = "example/app/ProbeFixture"
    val bytes = classBytes(name, entrypoint = true)
    val hash =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
          "%02x".format(it.toInt() and 0xff)
        }
    return CooperativeClosedGraph.verify(
        listOf(PinnedCooperativeClass(name, hash, bytes)),
        name,
        "run",
    )
  }

  private fun strictLoader(parent: ClassLoader, stdlib: Path, coroutines: Path): FrayClassLoader =
      FrayClassLoader(
          emptyArray(),
          parent,
          false,
          appGraph(),
          emptyMap(),
          resolver(parent, stdlib, coroutines),
      )

  @Test
  fun verifiedAbsentAndroidBuildProbeDoesNotPoisonStrictLoader() {
    val (stdlib, coroutines) = files()
    URLClassLoader(arrayOf(stdlib.toUri().toURL(), coroutines.toUri().toURL()), null).use { parent
      ->
      strictLoader(parent, stdlib, coroutines).use { frozen ->
        repeat(2) {
          assertFailsWith<ClassNotFoundException> { frozen.loadClass("android.os.Build") }
          CoroutineInstrumentationOwners.checkHealthy(frozen)
        }
      }
    }
  }

  @Test
  fun unknownAndroidClassStillPoisonsStrictLoader() {
    val (stdlib, coroutines) = files()
    URLClassLoader(arrayOf(stdlib.toUri().toURL(), coroutines.toUri().toURL()), null).use { parent
      ->
      strictLoader(parent, stdlib, coroutines).use { frozen ->
        assertFailsWith<ClassNotFoundException> { frozen.loadClass("android.os.Bundle") }
        assertFailsWith<IllegalStateException> {
          CoroutineInstrumentationOwners.checkHealthy(frozen)
        }
      }
    }
  }

  @Test
  fun parentAndroidBuildResourceRejectsProbeAndPoisonsStrictLoader() {
    val (stdlib, coroutines) = files()
    val android = temp.resolve("android.jar")
    writeJar(android, mapOf("android/os/Build.class" to classBytes("android/os/Build")))
    URLClassLoader(
            arrayOf(stdlib.toUri().toURL(), coroutines.toUri().toURL(), android.toUri().toURL()),
            null,
        )
        .use { parent ->
          strictLoader(parent, stdlib, coroutines).use { frozen ->
            assertFailsWith<IllegalArgumentException> { frozen.loadClass("android.os.Build") }
            assertFailsWith<IllegalStateException> {
              CoroutineInstrumentationOwners.checkHealthy(frozen)
            }
          }
        }
  }

  @Test
  fun parentAndroidBuildClassWithoutVisibleResourceRejectsProbeAndPoisonsStrictLoader() {
    val (stdlib, coroutines) = files()
    val android = temp.resolve("android.jar")
    writeJar(android, mapOf("android/os/Build.class" to classBytes("android/os/Build")))
    val parent =
        object :
            URLClassLoader(
                arrayOf(
                    stdlib.toUri().toURL(),
                    coroutines.toUri().toURL(),
                    android.toUri().toURL(),
                ),
                null,
            ) {
          override fun getResources(name: String): Enumeration<URL> =
              if (name == "android/os/Build.class") Collections.emptyEnumeration()
              else super.getResources(name)
        }
    parent.use {
      strictLoader(parent, stdlib, coroutines).use { frozen ->
        assertFailsWith<IllegalStateException> { frozen.loadClass("android.os.Build") }
        assertFailsWith<IllegalStateException> {
          CoroutineInstrumentationOwners.checkHealthy(frozen)
        }
      }
    }
  }

  @Test
  fun resolvesOnlyPinnedSourceAndTracksExactIdentity() {
    val (stdlib, coroutines) = files()
    URLClassLoader(arrayOf(stdlib.toUri().toURL(), coroutines.toUri().toURL()), null).use { parent
      ->
      val trusted = resolver(parent, stdlib, coroutines)
      val parentLoaded = parent.loadClass("kotlin.fake.Trusted")
      assertFalse(trusted.isResolvedIdentity("kotlin.fake.Trusted", parentLoaded))
      val standard = trusted.resolve("kotlin.fake.Trusted")!!
      assertSame(parentLoaded, standard)
      assertEquals(parent, standard.classLoader)
      assertEquals("kotlin.fake.Trusted", standard.name)
      assertTrue(trusted.isResolvedIdentity("kotlin.fake.Trusted", standard))
      assertFalse(trusted.isResolvedIdentity("kotlin.fake.Trusted", String::class.java))
      assertSame(standard, trusted.resolve("kotlin.fake.Trusted"))
      val helper = trusted.resolve("_COROUTINE.Fake")!!
      assertEquals(parent, helper.classLoader)
      assertTrue(trusted.isResolvedIdentity("_COROUTINE.Fake", helper))
      trusted.verifySourcesUnchanged()
    }
  }

  @Test
  fun delegatesJavaBaseToBootstrapAndDeniesUnknownAndOwnedCoroutines() {
    val (stdlib, coroutines) = files()
    URLClassLoader(arrayOf(stdlib.toUri().toURL(), coroutines.toUri().toURL()), null).use { parent
      ->
      val trusted = resolver(parent, stdlib, coroutines)
      assertFalse(trusted.isResolvedIdentity("java.lang.String", String::class.java))
      assertSame(String::class.java, trusted.resolve("java.lang.String"))
      assertTrue(trusted.isResolvedIdentity("java.lang.String", String::class.java))
      assertNull(trusted.resolve("java.sql.Driver")) // outside the allowed java.base module
      assertNull(trusted.resolve("example.host.ExternalBridge"))
      assertNull(trusted.resolve("kotlin.fake.Missing"))
      assertNull(trusted.resolve("kotlinx.coroutines.JobSupport"))
    }
  }

  @Test
  fun rejectsWrongHashAndChangedSourceEvenIfClassWasPreviouslyResolved() {
    val (stdlib, coroutines) = files()
    URLClassLoader(arrayOf(stdlib.toUri().toURL(), coroutines.toUri().toURL()), null).use { parent
      ->
      assertFailsWith<IllegalArgumentException> {
        FrozenTrustedRuntimeResolver.forTesting(
            parent,
            stdlib,
            coroutines,
            "0".repeat(64),
            sha256(coroutines),
        )
      }
      val trusted = resolver(parent, stdlib, coroutines)
      trusted.resolve("kotlin.fake.Trusted")
      Files.write(stdlib, byteArrayOf(0), StandardOpenOption.APPEND)
      assertFailsWith<IllegalArgumentException> { trusted.verifySourcesUnchanged() }
    }
  }

  @Test
  fun rejectsDuplicateParentResourceAndDifferentParentSource() {
    val (stdlib, coroutines) = files()
    val shadow = temp.resolve("shadow.jar")
    writeJar(shadow, stdEntries())
    URLClassLoader(
            arrayOf(stdlib.toUri().toURL(), shadow.toUri().toURL(), coroutines.toUri().toURL()),
            null,
        )
        .use { parent ->
          assertFailsWith<IllegalArgumentException> {
            resolver(parent, stdlib, coroutines).resolve("kotlin.fake.Trusted")
          }
        }
    URLClassLoader(arrayOf(shadow.toUri().toURL(), coroutines.toUri().toURL()), null).use { parent
      ->
      assertFailsWith<IllegalArgumentException> {
        resolver(parent, stdlib, coroutines).resolve("kotlin.fake.Trusted")
      }
    }
  }

  @Test
  fun rejectsServiceProvidersManifestClassPathAndMultiReleaseShadow() {
    val forbidden =
        listOf(
            "META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler" to
                "bad.Provider".toByteArray(),
            "META-INF/MANIFEST.MF" to
                "Manifest-Version: 1.0\r\nClass-Path: extra.jar\r\n\r\n".toByteArray(),
            "META-INF/versions/9/$stdName.class" to classBytes(stdName),
        )
    for ((name, bytes) in forbidden) {
      val (stdlib, coroutines) = files(stdEntries() + (name to bytes))
      URLClassLoader(arrayOf(stdlib.toUri().toURL(), coroutines.toUri().toURL()), null).use { parent
        ->
        assertFailsWith<IllegalArgumentException> { resolver(parent, stdlib, coroutines) }
      }
    }
    val service =
        "META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler" to
            "bad.Provider".toByteArray()
    val (stdlib, coroutines) = files(coroutine = coroutineEntries() + service)
    URLClassLoader(arrayOf(stdlib.toUri().toURL(), coroutines.toUri().toURL()), null).use { parent
      ->
      assertFailsWith<IllegalArgumentException> { resolver(parent, stdlib, coroutines) }
    }
  }

  @Test
  fun rejectsDuplicateClassEntryInSameArchive() {
    val (stdlib, coroutines) =
        files(stdEntries() + ("kotlin/fake/TrusteX.class" to classBytes(stdName)))
    // ZIP APIs reject duplicate names when writing. Patch both same-length names in the local and
    // central-directory entries; CRC covers content, not entry names.
    val from = "kotlin/fake/TrusteX.class".toByteArray()
    val to = "$stdName.class".toByteArray()
    assertEquals(from.size, to.size)
    val bytes = Files.readAllBytes(stdlib)
    var replacements = 0
    for (index in 0..bytes.size - from.size) {
      if (from.indices.all { bytes[index + it] == from[it] }) {
        System.arraycopy(to, 0, bytes, index, to.size)
        replacements++
      }
    }
    assertEquals(2, replacements)
    Files.write(stdlib, bytes)
    URLClassLoader(arrayOf(stdlib.toUri().toURL(), coroutines.toUri().toURL()), null).use { parent
      ->
      assertFailsWith<IllegalArgumentException> { resolver(parent, stdlib, coroutines) }
    }
  }
}
