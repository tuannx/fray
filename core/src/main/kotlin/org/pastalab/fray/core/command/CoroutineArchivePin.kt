package org.pastalab.fray.core.command

import java.io.ByteArrayInputStream
import java.net.JarURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.security.CodeSource
import java.security.MessageDigest
import java.security.ProtectionDomain
import java.security.cert.Certificate
import java.util.zip.ZipInputStream

/** Exact-source gate for the experimental, iteration-owned coroutines runtime. */
internal class CoroutineArchivePin
private constructor(
    private val archive: Path,
    private val classes: Map<String, ByteArray>,
    private val resources: (String) -> List<URL>,
) {
  companion object {
    const val PINNED_SHA256 = "069c5988633230e074ec0d39321ec3cdaa4547c49e90ba936c63d8fc91c8c00d"
    private const val ROOT_CLASS = "kotlinx/coroutines/JobSupport.class"
    private const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
    private const val MAX_CLASS_BYTES = 8 * 1024 * 1024
    private const val MAX_TOTAL_CLASS_BYTES = 64L * 1024 * 1024

    /** The digest parameter exists so tests can use synthetic archives; the loader uses the pin. */
    fun resolve(
        resources: (String) -> List<URL>,
        expectedSha256: String = PINNED_SHA256,
    ): CoroutineArchivePin {
      val rootResources = resources(ROOT_CLASS)
      require(rootResources.size == 1) {
        "expected exactly one child coroutine JobSupport resource"
      }
      val path = archivePath(rootResources.single(), ROOT_CLASS)
      require(Files.size(path) <= MAX_ARCHIVE_BYTES) { "coroutine archive is too large" }
      val archiveBytes =
          Files.newInputStream(path).use { it.readNBytes(MAX_ARCHIVE_BYTES.toInt() + 1) }
      require(archiveBytes.size.toLong() <= MAX_ARCHIVE_BYTES) { "coroutine archive is too large" }
      val digest =
          MessageDigest.getInstance("SHA-256").digest(archiveBytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
          }
      require(digest == expectedSha256) { "unsupported coroutine runtime archive" }

      // Define from a frozen verified snapshot; later replacement of the file cannot swap code.
      val classes = mutableMapOf<String, ByteArray>()
      var totalClassBytes = 0L
      ZipInputStream(ByteArrayInputStream(archiveBytes)).use { zip ->
        while (true) {
          val entry = zip.nextEntry ?: break
          val name = entry.name
          if (
              !entry.isDirectory &&
                  name.startsWith("kotlinx/coroutines/") &&
                  name.endsWith(".class")
          ) {
            require(name.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
              "invalid coroutine archive entry"
            }
            val bytes = zip.readNBytes(MAX_CLASS_BYTES + 1)
            require(bytes.size <= MAX_CLASS_BYTES) { "coroutine class is too large" }
            totalClassBytes += bytes.size
            require(totalClassBytes <= MAX_TOTAL_CLASS_BYTES) { "coroutine classes are too large" }
            require(classes.put(name, bytes) == null) { "duplicate coroutine archive entry: $name" }
          }
          zip.closeEntry()
        }
      }
      require(classes.containsKey(ROOT_CLASS)) { "pinned archive does not contain JobSupport" }
      return CoroutineArchivePin(path, classes, resources)
    }

    private fun archivePath(resource: URL, entry: String): Path {
      require(resource.protocol == "jar") { "coroutine resource is not in a local archive" }
      val connection =
          resource.openConnection() as? JarURLConnection
              ?: throw IllegalArgumentException("invalid coroutine resource")
      require(connection.entryName == entry && connection.jarFileURL.protocol == "file") {
        "coroutine resource is not in a local archive"
      }
      return Path.of(connection.jarFileURL.toURI()).toRealPath()
    }
  }

  fun classBytes(binaryName: String): Pair<ByteArray, ProtectionDomain> {
    val entry = binaryName.replace('.', '/') + ".class"
    require(entry.startsWith("kotlinx/coroutines/")) { "not a coroutine class" }
    val matches = resources(entry)
    require(matches.size == 1) { "missing or duplicate coroutine class: $binaryName" }
    require(archivePath(matches.single(), entry) == archive) {
      "coroutine class came from a different archive: $binaryName"
    }
    val bytes = classes[entry] ?: throw ClassNotFoundException(binaryName)
    val codeSource = CodeSource(archive.toUri().toURL(), null as Array<Certificate>?)
    return Pair(bytes.clone(), ProtectionDomain(codeSource, null))
  }
}
