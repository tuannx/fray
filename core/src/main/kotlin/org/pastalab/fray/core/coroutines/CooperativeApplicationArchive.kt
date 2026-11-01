package org.pastalab.fray.core.coroutines

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipInputStream
import org.pastalab.fray.instrumentation.base.CooperativeClosedGraph
import org.pastalab.fray.instrumentation.base.PinnedCooperativeClass
import org.pastalab.fray.instrumentation.base.VerifiedCooperativeGraph

/**
 * Explicit cooperative app inventory; all expected digests must originate from the caller's trusted
 * manifest. Archive verification does not verify runtime sources or prove callback provenance, and
 * is not a security sandbox. It does not authorize target execution by itself.
 */
internal data class CooperativeApplicationArchive(
    val path: Path,
    val sha256: String,
    /** Internal JVM class name (without .class) to SHA-256. */
    val classDigests: Map<String, String>,
)

internal object CooperativeApplicationArchiveReader {
  private const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
  private const val MAX_ENTRY_BYTES = 1_000_000
  private const val MAX_UNCOMPRESSED_BYTES = 32_000_000
  private const val MAX_ENTRIES = 5000
  private val shaPattern = Regex("[0-9a-f]{64}")
  private val classPattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*(/[A-Za-z_$][A-Za-z0-9_$]*)*")

  fun read(input: CooperativeApplicationArchive, entry: Entrypoint): VerifiedCooperativeGraph {
    require(
        shaPattern.matches(input.sha256) &&
            input.classDigests.isNotEmpty() &&
            input.classDigests.size <= 1000 &&
            input.classDigests.all { (name, hash) ->
              classPattern.matches(name) && shaPattern.matches(hash)
            }
    ) {
      "Invalid explicit app manifest"
    }
    require(
        !Files.isSymbolicLink(input.path) &&
            Files.isRegularFile(input.path, LinkOption.NOFOLLOW_LINKS)
    )
    require(Files.size(input.path) <= MAX_ARCHIVE_BYTES)
    // Freeze and hash the exact archive before parsing; later file replacement cannot swap a class.
    val bytes = Files.newInputStream(input.path).use { it.readNBytes(MAX_ARCHIVE_BYTES + 1) }
    require(bytes.size <= MAX_ARCHIVE_BYTES && digest(bytes) == input.sha256) {
      "App archive pin mismatch"
    }
    val classes = linkedMapOf<String, ByteArray>()
    val seen = mutableSetOf<String>()
    var total = 0L
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      while (true) {
        val item = zip.nextEntry ?: break
        val name = item.name
        require(seen.size < MAX_ENTRIES && seen.add(name)) {
          "Duplicate or excessive archive entries"
        }
        require(
            name.isNotEmpty() &&
                !name.startsWith('/') &&
                !name.contains('\\') &&
                name.removeSuffix("/").split('/').all { it.isNotEmpty() && it != "." && it != ".." }
        ) {
          "Invalid archive entry"
        }
        require(!name.startsWith("META-INF/versions/") && !name.startsWith("META-INF/services/")) {
          "Multi-release classes and service providers are unsupported"
        }
        if (!item.isDirectory) {
          val content = zip.readNBytes(MAX_ENTRY_BYTES + 1)
          require(content.size <= MAX_ENTRY_BYTES) { "App archive entry too large" }
          total += content.size
          require(total <= MAX_UNCOMPRESSED_BYTES) { "App archive contents too large" }
          if (name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) {
            val attributes = Manifest(ByteArrayInputStream(content)).mainAttributes
            require(
                attributes.getValue(Attributes.Name.CLASS_PATH) == null &&
                    !attributes.getValue("Multi-Release").equals("true", ignoreCase = true)
            ) {
              "Manifest class path and multi-release jars are unsupported"
            }
          }
          if (name.endsWith(".class")) {
            val internalName = name.removeSuffix(".class")
            val expected = input.classDigests[internalName]
            require(expected != null) { "Unexpected app class" }
            require(digest(content) == expected) { "App class digest mismatch" }
            classes[internalName] = content
          }
        }
        zip.closeEntry()
      }
    }
    require(classes.keys == input.classDigests.keys) { "Missing app class" }
    return CooperativeClosedGraph.verify(
        classes.map { (name, contents) ->
          PinnedCooperativeClass(name, input.classDigests.getValue(name), contents)
        },
        entry.className.replace('.', '/'),
        entry.methodName,
    )
  }

  private fun digest(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it.toInt() and 0xff)
      }
}
