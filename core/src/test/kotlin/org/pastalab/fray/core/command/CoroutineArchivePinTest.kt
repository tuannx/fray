package org.pastalab.fray.core.command

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.Comparator
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class CoroutineArchivePinTest {
  private val job = "kotlinx/coroutines/JobSupport.class"
  private val dispatchers = "kotlinx/coroutines/Dispatchers.class"

  private fun jar(path: Path, entries: Map<String, ByteArray>) {
    ZipOutputStream(Files.newOutputStream(path)).use { zip ->
      entries.forEach { (name, bytes) ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
      }
    }
  }

  private fun digest(path: Path): String =
      MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") {
        "%02x".format(it.toInt() and 0xff)
      }

  private fun withDirectory(block: (Path) -> Unit) {
    val root = Files.createTempDirectory("fray-archive-pin")
    try {
      block(root)
    } finally {
      Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
      }
    }
  }

  private fun resources(loader: URLClassLoader): (String) -> List<java.net.URL> = { name ->
    Collections.list(loader.findResources(name))
  }

  @Test
  fun uniquePinnedArchiveSuppliesAnImmutableSnapshot() = withDirectory { root ->
    val archive = root.resolve("pinned.jar")
    val expected = byteArrayOf(1, 2, 3)
    jar(archive, mapOf(job to byteArrayOf(0), dispatchers to expected))
    URLClassLoader(arrayOf(archive.toUri().toURL()), null).use { loader ->
      val pin = CoroutineArchivePin.resolve(resources(loader), digest(archive))
      val first = pin.classBytes("kotlinx.coroutines.Dispatchers").first
      assertContentEquals(expected, first)
      first[0] = 9
      assertContentEquals(expected, pin.classBytes("kotlinx.coroutines.Dispatchers").first)
    }
  }

  @Test
  fun shadowedDispatcherOrForeignClassIsRejected() = withDirectory { root ->
    val archive = root.resolve("pinned.jar")
    val shadow = root.resolve("shadow.jar")
    jar(archive, mapOf(job to byteArrayOf(0), dispatchers to byteArrayOf(1)))
    jar(
        shadow,
        mapOf(dispatchers to byteArrayOf(2), "kotlinx/coroutines/Foreign.class" to byteArrayOf(3)),
    )
    URLClassLoader(arrayOf(shadow.toUri().toURL(), archive.toUri().toURL()), null).use { loader ->
      val pin = CoroutineArchivePin.resolve(resources(loader), digest(archive))
      assertFailsWith<IllegalArgumentException> { pin.classBytes("kotlinx.coroutines.Dispatchers") }
      assertFailsWith<IllegalArgumentException> { pin.classBytes("kotlinx.coroutines.Foreign") }
    }
  }

  @Test
  fun missingOrDuplicateJobSupportAndWrongDigestAreRejected() = withDirectory { root ->
    val archive = root.resolve("pinned.jar")
    val duplicate = root.resolve("duplicate.jar")
    val missing = root.resolve("missing.jar")
    jar(archive, mapOf(job to byteArrayOf(0)))
    jar(duplicate, mapOf(job to byteArrayOf(1)))
    jar(missing, mapOf(dispatchers to byteArrayOf(2)))
    URLClassLoader(arrayOf(missing.toUri().toURL()), null).use { loader ->
      assertFailsWith<IllegalArgumentException> {
        CoroutineArchivePin.resolve(resources(loader), digest(archive))
      }
    }
    URLClassLoader(arrayOf(archive.toUri().toURL(), duplicate.toUri().toURL()), null).use { loader
      ->
      assertFailsWith<IllegalArgumentException> {
        CoroutineArchivePin.resolve(resources(loader), digest(archive))
      }
    }
    URLClassLoader(arrayOf(archive.toUri().toURL()), null).use { loader ->
      assertFailsWith<IllegalArgumentException> {
        CoroutineArchivePin.resolve(resources(loader), "00")
      }
      val pin = CoroutineArchivePin.resolve(resources(loader), digest(archive))
      assertFailsWith<IllegalArgumentException> { pin.classBytes("kotlinx.coroutines.Missing") }
    }
  }
}
