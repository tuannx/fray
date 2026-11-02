package org.pastalab.fray.core.coroutines

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.io.TempDir

class JdkImageFingerprintTest {
  @TempDir lateinit var temp: Path

  private fun deadline() = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)

  private fun image(name: String): Path {
    val root = Files.createDirectory(temp.resolve(name))
    Files.createDirectories(root.resolve("bin"))
    Files.createDirectories(root.resolve("lib/server"))
    Files.writeString(root.resolve("bin/java"), "launcher")
    Files.writeString(root.resolve("lib/modules"), "modules")
    Files.writeString(root.resolve("lib/server/libjvm.so"), "native-A")
    return root
  }

  private fun fingerprint(root: Path) = JdkImageFingerprint.digest(root, deadline())

  @Test
  fun bindsNativeRuntimeConfigNamesAndExecutablePermissions() {
    val root = image("jdk")
    val native = root.resolve("lib/server/libjvm.so")
    val initial = fingerprint(root)
    assertEquals(initial, fingerprint(root))
    val originalTime = Files.getLastModifiedTime(native)
    Files.writeString(native, "native-B") // Same length; preserve metadata to isolate contents.
    Files.setLastModifiedTime(native, originalTime)
    val changedNative = fingerprint(root)
    assertNotEquals(initial, changedNative)
    assertFailsWith<IllegalArgumentException> {
      JdkImageFingerprint.requireUnchanged(root, initial, deadline())
    }
    JdkImageFingerprint.requireUnchanged(root, changedNative, deadline())

    val config = Files.writeString(root.resolve("lib/jvm.cfg"), "-server KNOWN")
    val added = fingerprint(root)
    assertNotEquals(changedNative, added)
    val renamed = Files.move(config, root.resolve("lib/renamed.cfg"))
    val moved = fingerprint(root)
    assertNotEquals(added, moved)
    Files.delete(renamed)
    val deleted = fingerprint(root)
    assertNotEquals(moved, deleted)

    val launcher = root.resolve("bin/java")
    val oldMode = Files.getPosixFilePermissions(launcher)
    val newMode = oldMode.toMutableSet()
    if (PosixFilePermission.OWNER_EXECUTE in newMode)
        newMode.remove(PosixFilePermission.OWNER_EXECUTE)
    else newMode.add(PosixFilePermission.OWNER_EXECUTE)
    Files.setPosixFilePermissions(launcher, newMode)
    assertNotEquals(deleted, fingerprint(root))
  }

  @Test
  fun sameTreeAndMetadataHaveOrderIndependentIdentity() {
    val left = image("left")
    val right = Files.createDirectory(temp.resolve("right"))
    Files.createDirectories(right.resolve("lib/server"))
    Files.createDirectories(right.resolve("bin"))
    Files.writeString(right.resolve("lib/server/libjvm.so"), "native-A")
    Files.writeString(right.resolve("lib/modules"), "modules")
    Files.writeString(right.resolve("bin/java"), "launcher")
    val fixed = FileTime.fromMillis(1_700_000_000_000L)
    for (root in listOf(left, right)) {
      Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.setLastModifiedTime(it, fixed) }
      }
    }
    assertEquals(fingerprint(left), fingerprint(right))
  }

  @Test
  fun bindsInternalLinksAndRejectsUnsafeLinks() {
    val root = image("links")
    val legal = Files.createDirectory(root.resolve("legal"))
    val first = Files.createSymbolicLink(legal.resolve("license"), Path.of("../lib/modules"))
    val withLink = fingerprint(root)
    Files.delete(first)
    Files.createSymbolicLink(first, Path.of("../lib/server/libjvm.so"))
    assertNotEquals(withLink, fingerprint(root))
    Files.delete(first)
    val outside = Files.writeString(temp.resolve("outside"), "outside")
    Files.createSymbolicLink(first, outside)
    assertFails { fingerprint(root) }
    Files.delete(first)
    Files.createSymbolicLink(first, Path.of("../../outside"))
    assertFails { fingerprint(root) }
    Files.delete(first)
    Files.createSymbolicLink(first, Path.of("missing"))
    assertFails { fingerprint(root) }
    Files.delete(first)
    Files.createSymbolicLink(first, Path.of("cycle"))
    Files.createSymbolicLink(legal.resolve("cycle"), Path.of("license"))
    assertFails { fingerprint(root) }
  }

  @Test
  fun boundsTraversalBytesAndDeadline() {
    val root = image("bounded")
    assertFails {
      JdkImageFingerprint.digest(root, deadline(), JdkImageFingerprint.Limits(entries = 1))
    }
    assertFails {
      JdkImageFingerprint.digest(root, deadline(), JdkImageFingerprint.Limits(depth = 1))
    }
    assertFails {
      JdkImageFingerprint.digest(root, deadline(), JdkImageFingerprint.Limits(fileBytes = 2))
    }
    assertFails {
      JdkImageFingerprint.digest(root, deadline(), JdkImageFingerprint.Limits(totalFileBytes = 2))
    }
    assertFails {
      JdkImageFingerprint.digest(root, deadline(), JdkImageFingerprint.Limits(pathBytes = 2))
    }
    assertFails {
      JdkImageFingerprint.digest(root, deadline(), JdkImageFingerprint.Limits(totalPathBytes = 2))
    }
    assertFails { JdkImageFingerprint.digest(root, Long.MIN_VALUE) }
  }

  @Test
  fun rejectsScratchOverlappingImageIncludingSymlinkedAncestor() {
    val root = image("jdk")
    assertFails { JdkImageFingerprint.requireDisjoint(root, root.resolve("new/scratch")) }
    assertFails { JdkImageFingerprint.requireDisjoint(root, temp) }
    val alias = Files.createSymbolicLink(temp.resolve("alias"), root)
    assertFails { JdkImageFingerprint.requireDisjoint(root, alias.resolve("new/scratch")) }
    val separate = temp.resolve("separate/scratch")
    JdkImageFingerprint.requireDisjoint(root, separate)
    assertFalse(Files.exists(separate))
  }
}
