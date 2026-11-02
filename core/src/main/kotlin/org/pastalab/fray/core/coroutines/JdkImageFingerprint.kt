package org.pastalab.fray.core.coroutines

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributes
import java.security.MessageDigest

/** Bounded replay identity of one immutable JDK image, not authentication or a sandbox. */
internal object JdkImageFingerprint {
  internal data class Limits(
      val entries: Int = 20_000,
      val depth: Int = 64,
      val pathBytes: Int = 4096,
      val totalPathBytes: Long = 8L * 1024 * 1024,
      val fileBytes: Long = 2L * 1024 * 1024 * 1024,
      val totalFileBytes: Long = 8L * 1024 * 1024 * 1024,
  )

  /**
   * No links are followed while walking. Relative links must resolve within the canonical image;
   * their target bytes and the target's own entry are both bound. POSIX metadata is required so an
   * image on a filesystem with unknown executable/permission semantics is rejected, not assumed.
   * POSIX owner/group names and modification time are included, but ACLs, extended attributes, OS
   * libraries and CPU state are outside this identity. The caller must prevent concurrent image
   * mutation: a pre/post hash cannot detect replacement followed by restoration during execution.
   */
  fun digest(root: Path, deadline: Long, limits: Limits = Limits()): String {
    require(
        limits.entries > 0 &&
            limits.depth > 0 &&
            limits.pathBytes > 0 &&
            limits.totalPathBytes > 0 &&
            limits.fileBytes >= 0 &&
            limits.totalFileBytes >= 0
    )
    checkDeadline(deadline)
    val image = root.toRealPath()
    require(!Files.isSymbolicLink(root) && Files.isDirectory(image, NOFOLLOW_LINKS)) {
      "Invalid JDK image root"
    }
    val entries = mutableListOf<Path>()
    var totalPathBytes = 0L
    fun add(path: Path) {
      checkDeadline(deadline)
      require(entries.size < limits.entries) { "JDK image exceeds entry limit" }
      val relative = image.relativize(path)
      val depth = if (path == image) 0 else relative.nameCount
      require(depth <= limits.depth) { "JDK image exceeds depth limit" }
      val length = utf8(relativeName(image, path)).size
      require(length <= limits.pathBytes) { "JDK image path exceeds size limit" }
      totalPathBytes = Math.addExact(totalPathBytes, length.toLong())
      require(totalPathBytes <= limits.totalPathBytes) { "JDK image paths exceed size limit" }
      entries.add(path)
    }
    Files.walkFileTree(
        image,
        object : SimpleFileVisitor<Path>() {
          override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            add(dir)
            return FileVisitResult.CONTINUE
          }

          override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            add(file)
            return FileVisitResult.CONTINUE
          }
        },
    )
    checkDeadline(deadline)
    entries.sortBy { relativeName(image, it) }
    val digest = MessageDigest.getInstance("SHA-256")
    fun framed(value: String) {
      val bytes = utf8(value)
      digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
      digest.update(bytes)
    }
    framed("fray-jdk-image-v1")
    var totalBytes = 0L
    for (path in entries) {
      checkDeadline(deadline)
      val before = Files.readAttributes(path, PosixFileAttributes::class.java, NOFOLLOW_LINKS)
      val type =
          when {
            before.isDirectory -> "directory"
            before.isRegularFile -> "file"
            before.isSymbolicLink -> "symlink"
            else -> throw IllegalArgumentException("Unsupported JDK image entry")
          }
      framed(type)
      framed(relativeName(image, path))
      framed(before.permissions().map { it.name }.sorted().joinToString(","))
      framed(before.owner().name)
      framed(before.group().name)
      framed(before.lastModifiedTime().toString())
      when (type) {
        "file" -> {
          require(before.size() <= limits.fileBytes) { "JDK image file exceeds size limit" }
          totalBytes = Math.addExact(totalBytes, before.size())
          require(totalBytes <= limits.totalFileBytes) { "JDK image exceeds byte limit" }
          framed(before.size().toString())
          val fileDigest = MessageDigest.getInstance("SHA-256")
          var actual = 0L
          Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { stream ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
              checkDeadline(deadline)
              val count = stream.read(buffer)
              if (count < 0) break
              actual += count
              require(actual <= before.size() && actual <= limits.fileBytes) {
                "JDK image file changed while hashing"
              }
              fileDigest.update(buffer, 0, count)
            }
          }
          require(actual == before.size()) { "JDK image file changed while hashing" }
          framed(fileDigest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
        }
        "symlink" -> {
          val target = Files.readSymbolicLink(path)
          require(!target.isAbsolute && path.parent.resolve(target).normalize().startsWith(image)) {
            "JDK image link escapes image"
          }
          val resolved = path.toRealPath()
          require(resolved.startsWith(image)) { "JDK image link escapes image" }
          framed(target.toString())
        }
      }
      val after = Files.readAttributes(path, PosixFileAttributes::class.java, NOFOLLOW_LINKS)
      require(
          before.isRegularFile == after.isRegularFile &&
              before.isDirectory == after.isDirectory &&
              before.isSymbolicLink == after.isSymbolicLink &&
              before.size() == after.size() &&
              before.lastModifiedTime() == after.lastModifiedTime() &&
              before.permissions() == after.permissions() &&
              before.owner() == after.owner() &&
              before.group() == after.group() &&
              before.fileKey() == after.fileKey()
      ) {
        "JDK image changed while hashing"
      }
    }
    checkDeadline(deadline)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }

  fun requireUnchanged(root: Path, expected: String, deadline: Long) {
    require(digest(root, deadline) == expected) { "JDK image changed during execution" }
  }

  private fun relativeName(root: Path, path: Path): String =
      if (root == path) "" else root.relativize(path).joinToString("/") { it.toString() }

  /** Resolve the deepest existing ancestor first, without creating the candidate path. */
  fun requireDisjoint(image: Path, candidate: Path) {
    val canonicalImage = image.toRealPath()
    var ancestor = candidate.toAbsolutePath().normalize()
    val suffix = ArrayDeque<String>()
    while (!Files.exists(ancestor, NOFOLLOW_LINKS)) {
      val name = ancestor.fileName ?: throw IllegalArgumentException("Invalid output path")
      suffix.addFirst(name.toString())
      ancestor = ancestor.parent ?: throw IllegalArgumentException("Invalid output path")
    }
    var canonicalCandidate = ancestor.toRealPath()
    for (name in suffix) canonicalCandidate = canonicalCandidate.resolve(name)
    canonicalCandidate = canonicalCandidate.normalize()
    require(
        !canonicalCandidate.startsWith(canonicalImage) &&
            !canonicalImage.startsWith(canonicalCandidate)
    ) {
      "Output or scratch overlaps the JDK image"
    }
  }

  private fun checkDeadline(deadline: Long) {
    require(System.nanoTime() < deadline) { "Epoch deadline exceeded while hashing JDK image" }
  }

  private fun utf8(value: String): ByteArray {
    val bytes =
        StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(value))
    return ByteArray(bytes.remaining()).also { bytes.get(it) }
  }
}
