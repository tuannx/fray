package org.pastalab.fray.core.command

import java.io.ByteArrayInputStream
import java.io.File
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.jar.Manifest
import java.util.zip.ZipInputStream
import org.pastalab.fray.instrumentation.base.CooperativeClosedGraph

/**
 * Lab-only resolver for a fixed trusted process, not a sandbox. The caller must separately pin the
 * JDK image, Java/JVMTI agents and ordered parent classpath, prohibit unexpected transformers and
 * dynamic attach, and ensure the pinned files cannot change while a child runs. Pre/post hashes
 * detect persistent changes, not concurrent replacement-and-restore or transformed parent bytes.
 */
class FrozenTrustedRuntimeResolver
private constructor(
    internal val parent: ClassLoader,
    private val stdlib: Archive,
    private val coroutines: Archive,
    private val reflect: Archive?,
    private val allSources: List<Archive>,
) {
  companion object {
    const val STDLIB_SHA256 = "6558a3d233da56a20934b32159f9db5f86ed5816ef098f78a2c223dc6abb79dd"
    const val COROUTINES_SHA256 = "069c5988633230e074ec0d39321ec3cdaa4547c49e90ba936c63d8fc91c8c00d"
    // Local observed Maven-cache artifact; the caller must treat this as an explicit trust input.
    const val REFLECT_SHA256 = "44380abf37d245ce5c0f294f43512d1c39a59642bfa463922c74e96877cf49f8"
    private const val MAX_ARCHIVE = 64 * 1024 * 1024
    private const val MAX_ENTRY = 8 * 1024 * 1024
    private const val MAX_EXPANDED = 64L * 1024 * 1024
    private const val MAX_ENTRIES = 20_000
    private val binaryName = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
    private val runtimeBridges =
        setOf(
            "org.pastalab.fray.runtime.Runtime",
            "org.pastalab.fray.runtime.CoroutineJobTracker",
            "org.pastalab.fray.runtime.CoroutineTaskTracker",
        )

    @JvmStatic
    fun create(
        parent: ClassLoader,
        stdlibJar: Path,
        coroutineJar: Path,
        parentClasspath: List<Path>,
    ): FrozenTrustedRuntimeResolver =
        createPinned(
            parent,
            stdlibJar,
            coroutineJar,
            STDLIB_SHA256,
            COROUTINES_SHA256,
            parentClasspath,
            true,
        )

    // Only tests can supply synthetic digests. The worker uses the fixed pins above.
    internal fun forTesting(
        parent: ClassLoader,
        stdlibJar: Path,
        coroutineJar: Path,
        stdlibSha256: String,
        coroutineSha256: String,
        parentClasspath: List<Path> = listOf(stdlibJar, coroutineJar),
    ): FrozenTrustedRuntimeResolver =
        createPinned(
            parent,
            stdlibJar,
            coroutineJar,
            stdlibSha256,
            coroutineSha256,
            parentClasspath,
            false,
        )

    private fun createPinned(
        parent: ClassLoader,
        stdlibJar: Path,
        coroutineJar: Path,
        stdlibSha256: String,
        coroutineSha256: String,
        parentClasspath: List<Path>,
        verifySystemClasspath: Boolean,
    ): FrozenTrustedRuntimeResolver {
      val stdlib = Archive.read(stdlibJar, stdlibSha256, ArchiveKind.STDLIB)
      // Four _COROUTINE helpers are from the exact pinned coroutine JAR but are parent-resolved;
      // kotlinx.coroutines itself remains owned by the strict loader and is never delegated here.
      val coroutines = Archive.read(coroutineJar, coroutineSha256, ArchiveKind.COROUTINES)
      require(stdlib.path != coroutines.path) { "runtime archives must be distinct" }
      val classpath = parentClasspath.map { it.toRealPath() }
      require(
          classpath.isNotEmpty() &&
              classpath.distinct().size == classpath.size &&
              classpath.count { it == stdlib.path } == 1 &&
              classpath.count { it == coroutines.path } == 1
      ) {
        "invalid ordered runtime classpath"
      }
      if (verifySystemClasspath) {
        require(parent === ClassLoader.getSystemClassLoader()) {
          "runtime parent must be the system loader"
        }
        val actual =
            System.getProperty("java.class.path").split(File.pathSeparator).map {
              Path.of(it).toRealPath()
            }
        require(actual == classpath) { "runtime classpath differs from process classpath" }
      }
      val others =
          classpath
              .filter { it != stdlib.path && it != coroutines.path }
              .map { Archive.read(it, null, ArchiveKind.OTHER) }
      val reflect = others.singleOrNull { it.digest == REFLECT_SHA256 }
      require(others.count { it.digest == REFLECT_SHA256 } <= 1) { "duplicate reflect archive" }
      require(reflect == null || stdlib.classEntries.intersect(reflect.classEntries).isEmpty()) {
        "stdlib/reflect class overlap"
      }
      return FrozenTrustedRuntimeResolver(
          parent,
          stdlib,
          coroutines,
          reflect,
          listOf(stdlib, coroutines) + others,
      )
    }
  }

  private val resolved = mutableMapOf<String, Class<*>>()

  /** Unknown classes return null; strict loader converts that to a poisoned unsupported lookup. */
  @Synchronized
  fun resolve(name: String): Class<*>? {
    require(binaryName.matches(name)) { "invalid binary class name" }
    resolved[name]?.let {
      return it
    }
    val result =
        when {
          name.startsWith("java.") ||
              name.startsWith("javax.") ||
              name.startsWith("jdk.internal.") ||
              name.startsWith("sun.") ||
              name in runtimeBridges -> {
            val clazz =
                try {
                  Class.forName(name, false, null)
                } catch (_: ClassNotFoundException) {
                  return null
                }
            // No platform/system-loader or arbitrary module fallback; image and runtime bridge are
            // part of the caller-pinned instrumented java.base image.
            if (clazz.classLoader != null || clazz.module !== Any::class.java.module) return null
            clazz
          }
          name.startsWith("kotlin.") ->
              resolveArchiveClass(name, stdlib)
                  ?: reflect?.let { resolveArchiveClass(name, it) }
                  ?: return null
          name.startsWith("_COROUTINE.") -> resolveArchiveClass(name, coroutines) ?: return null
          else -> return null
        }
    require(result.name == name) { "trusted class name mismatch" }
    resolved[name] = result
    return result
  }

  @Synchronized
  fun isResolvedIdentity(name: String, clazz: Class<*>): Boolean = resolved[name] === clazz

  /**
   * The pinned coroutine 1.10.1 FastServiceLoader probes precisely this optional Android class
   * inside runCatching. On a non-Android cooperative JVM we may preserve its expected CNFE, but
   * only after verifying the trusted parent exposes neither a resource nor an already-loadable
   * class. No class is returned and unknown Android names remain unsupported.
   */
  internal fun isVerifiedAbsentAndroidProbe(name: String): Boolean {
    if (name != "android.os.Build") return false
    require(!parent.getResources("android/os/Build.class").hasMoreElements()) {
      "Android class resource is present; JVM-only mode is unsupported"
    }
    try {
      Class.forName(name, false, parent)
    } catch (_: ClassNotFoundException) {
      return true
    }
    throw IllegalStateException("Android class is present; JVM-only mode is unsupported")
  }

  /** Call after all worker/callback sources stop and before reporting a successful epoch. */
  fun verifySourcesUnchanged() {
    allSources.forEach { it.verifyUnchanged() }
  }

  private fun resolveArchiveClass(name: String, archive: Archive): Class<*>? {
    val entry = name.replace('.', '/') + ".class"
    if (entry !in archive.classEntries) return null
    val matches = Collections.list(parent.getResources(entry))
    require(matches.size == 1) { "missing or duplicate trusted runtime resource: $name" }
    val connection =
        matches.single().openConnection() as? JarURLConnection
            ?: throw IllegalStateException("trusted runtime class is not in a jar")
    connection.useCaches = false
    require(connection.entryName == entry && connection.jarFileURL.protocol == "file") {
      "invalid trusted runtime resource"
    }
    require(Path.of(connection.jarFileURL.toURI()).toRealPath() == archive.path) {
      "trusted runtime class resolved from a different archive"
    }
    val clazz = Class.forName(name, false, parent)
    require(
        clazz.classLoader === parent &&
            clazz.protectionDomain?.codeSource?.location?.protocol == "file"
    ) {
      "trusted runtime class has a different loader or source"
    }
    require(
        Path.of(clazz.protectionDomain.codeSource.location.toURI()).toRealPath() == archive.path
    ) {
      "trusted runtime class source mismatch"
    }
    return clazz
  }

  private enum class ArchiveKind {
    STDLIB,
    COROUTINES,
    OTHER,
  }

  private class Archive(val path: Path, val digest: String, val classEntries: Set<String>) {
    fun verifyUnchanged() {
      require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
      require(sha256(readBounded(path)) == digest) { "trusted runtime archive changed" }
    }

    companion object {
      fun read(path: Path, expected: String?, kind: ArchiveKind): Archive {
        require(expected == null || expected.matches(Regex("[0-9a-f]{64}"))) {
          "invalid runtime archive pin"
        }
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        val actual = path.toRealPath()
        val bytes = readBounded(actual)
        val digest = sha256(bytes)
        require(expected == null || digest == expected) { "runtime archive hash mismatch" }
        val isReflect = kind == ArchiveKind.OTHER && digest == REFLECT_SHA256
        val seen = mutableSetOf<String>()
        val classes = mutableSetOf<String>()
        var expanded = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
          while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name
            require(seen.size < MAX_ENTRIES && seen.add(name)) {
              "duplicate or excessive runtime archive entries"
            }
            require(
                name.isNotEmpty() &&
                    !name.startsWith('/') &&
                    '\\' !in name &&
                    name.split('/').dropLast(if (entry.isDirectory) 1 else 0).none {
                      it.isEmpty() || it == "." || it == ".."
                    }
            ) {
              "invalid runtime archive entry"
            }
            if (entry.isDirectory) {
              zip.closeEntry()
              continue
            }
            val reflectServices =
                setOf(
                    "META-INF/services/kotlin.reflect.jvm.internal.impl.builtins.BuiltInsLoader",
                    "META-INF/services/kotlin.reflect.jvm.internal.impl.km.internal.extensions.MetadataExtensions",
                    "META-INF/services/kotlin.reflect.jvm.internal.impl.resolve.ExternalOverridabilityCondition",
                )
            require(
                !name.startsWith("META-INF/services/") || (isReflect && name in reflectServices)
            ) {
              "runtime service provider is unsupported"
            }
            val versioned = name.startsWith("META-INF/versions/")
            val unversioned =
                if (versioned) name.substringAfter("META-INF/versions/").substringAfter('/')
                else name
            val reserved =
                unversioned.startsWith("kotlin/") ||
                    unversioned.startsWith("kotlinx/coroutines/") ||
                    unversioned.startsWith("_COROUTINE/")
            val allowedClass =
                when (kind) {
                  ArchiveKind.STDLIB -> unversioned.startsWith("kotlin/")
                  ArchiveKind.COROUTINES ->
                      unversioned.startsWith("kotlinx/coroutines/") ||
                          unversioned.startsWith("_COROUTINE/")
                  ArchiveKind.OTHER -> isReflect && unversioned.startsWith("kotlin/reflect/")
                }
            require(!name.endsWith(".class") || !reserved || allowedClass) {
              "runtime namespace shadow"
            }
            if (versioned && name.endsWith(".class")) {
              val reflectException =
                  isReflect &&
                      name ==
                          "META-INF/versions/9/kotlin/reflect/jvm/internal/impl/serialization/deserialization/builtins/BuiltInsResourceLoader.class"
              require(
                  name == "META-INF/versions/9/module-info.class" ||
                      reflectException ||
                      (!reserved && kind == ArchiveKind.OTHER)
              ) {
                "versioned runtime class shadow is unsupported"
              }
            }
            val content = zip.readNBytes(MAX_ENTRY + 1)
            require(content.size <= MAX_ENTRY) { "runtime archive entry too large" }
            expanded += content.size
            require(expanded <= MAX_EXPANDED) { "expanded runtime archive too large" }
            if (name == "META-INF/MANIFEST.MF") {
              val manifest = Manifest(ByteArrayInputStream(content))
              require(manifest.mainAttributes.getValue("Class-Path") == null) {
                "runtime manifest Class-Path is unsupported"
              }
            }
            if (name.endsWith(".class") && allowedClass) {
              require(
                  CooperativeClosedGraph.checkedRuntimeClassName(content) + ".class" == unversioned
              ) {
                "runtime class identity mismatch"
              }
              // The pinned reflect JAR legitimately contains one version-9 replacement. Parent
              // loading follows the pinned JDK's multi-release selection; direct resolver names
              // remain keyed by the base entry.
              if (!versioned && (kind != ArchiveKind.COROUTINES || name.startsWith("_COROUTINE/")))
                  classes.add(name)
            }
            zip.closeEntry()
          }
        }
        require(kind == ArchiveKind.OTHER || classes.isNotEmpty()) {
          "trusted runtime archive has no allowed classes"
        }
        return Archive(actual, digest, classes.toSet())
      }
    }
  }
}

private fun readBounded(path: Path): ByteArray {
  val max = 64 * 1024 * 1024
  return Files.newInputStream(path).use { input ->
    val bytes = input.readNBytes(max + 1)
    require(bytes.size <= max) { "runtime archive too large" }
    bytes
  }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
      "%02x".format(it.toInt() and 0xff)
    }
