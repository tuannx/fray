package org.pastalab.fray.core.coroutines

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class CooperativeApplicationArchiveTest {
  @TempDir lateinit var temp: Path
  private val internalName = "example/Fixture"
  private val entry = Entrypoint("example.Fixture", "run")

  private fun clazz(): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(
        Opcodes.V11,
        Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
        internalName,
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
    return writer.toByteArray()
  }

  private fun sha(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it.toInt() and 0xff)
      }

  private fun jar(name: String, entries: List<Pair<String, ByteArray>>): Path {
    val path = temp.resolve(name)
    ZipOutputStream(Files.newOutputStream(path)).use { zip ->
      for ((entryName, contents) in entries) {
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(contents)
        zip.closeEntry()
      }
    }
    return path
  }

  private fun spec(path: Path, classes: Map<String, String>) =
      CooperativeApplicationArchive(path, sha(Files.readAllBytes(path)), classes)

  @Test
  fun readsFrozenExactInventoryAndAllowsUnusedResources() {
    val original = clazz()
    val archive =
        jar(
            "valid.jar",
            listOf(
                "$internalName.class" to original,
                "META-INF/project.kotlin_module" to byteArrayOf(1, 2),
            ),
        )
    val graph =
        CooperativeApplicationArchiveReader.read(
            spec(archive, mapOf(internalName to sha(original))),
            entry,
        )
    assertEquals(setOf(internalName), graph.classNames())
    Files.writeString(archive, "changed after verification")
    val returned = graph.classBytes(internalName)
    assertContentEquals(original, returned)
    returned[0] = 0
    assertContentEquals(original, graph.classBytes(internalName))
  }

  @Test
  fun rejectsWrongPinsAndIncompleteOrExtraClasses() {
    val original = clazz()
    val archive = jar("classes.jar", listOf("$internalName.class" to original))
    val valid = spec(archive, mapOf(internalName to sha(original)))
    assertFailsWith<IllegalArgumentException> {
      CooperativeApplicationArchiveReader.read(valid.copy(sha256 = "0".repeat(64)), entry)
    }
    assertFailsWith<IllegalArgumentException> {
      CooperativeApplicationArchiveReader.read(
          valid.copy(classDigests = mapOf(internalName to "0".repeat(64))),
          entry,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      CooperativeApplicationArchiveReader.read(
          valid.copy(classDigests = mapOf("example/Missing" to sha(original))),
          entry,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      CooperativeApplicationArchiveReader.read(
          valid.copy(classDigests = valid.classDigests + ("example/Missing" to sha(original))),
          entry,
      )
    }
  }

  @Test
  fun rejectsExternalClassPathServicesAndVersionedEntries() {
    val original = clazz()
    val classEntry = "$internalName.class" to original
    for ((index, extra) in
        listOf(
                "META-INF/MANIFEST.MF" to
                    "Manifest-Version: 1.0\nClass-Path: other.jar\n\n".toByteArray(),
                "META-INF/services/example.Service" to "example.Impl".toByteArray(),
                "META-INF/versions/11/example/Fixture.class" to original,
            )
            .withIndex()) {
      val archive = jar("bad-$index.jar", listOf(classEntry, extra))
      assertFailsWith<IllegalArgumentException> {
        CooperativeApplicationArchiveReader.read(
            spec(archive, mapOf(internalName to sha(original))),
            entry,
        )
      }
    }
  }
}
