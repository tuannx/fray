package org.pastalab.fray.core.command

import java.net.URL
import java.net.URLClassLoader
import java.security.CodeSource
import java.security.MessageDigest
import java.security.ProtectionDomain
import java.util.Collections
import java.util.Enumeration
import org.pastalab.fray.instrumentation.base.Utils.isFrayRuntimeClass
import org.pastalab.fray.instrumentation.base.VerifiedCooperativeGraph
import org.pastalab.fray.runtime.CoroutineInstrumentationOwners
import org.pastalab.fray.runtime.Runtime

class FrayClassLoader
@JvmOverloads
constructor(
    urls: Array<URL>,
    parent: ClassLoader,
    private val ownedCoroutineRuntime: Boolean = false,
    private val frozenAppGraph: VerifiedCooperativeGraph? = null,
    trustedRuntimeClasses: Map<String, Class<*>> = emptyMap(),
    private val trustedRuntimeResolver: FrozenTrustedRuntimeResolver? = null,
) : URLClassLoader(urls, parent) {

  private var ownershipRegistered = false
  private var coroutineArchive: CoroutineArchivePin? = null
  // Lab-only: each delegated Class is an explicit trust input, not a classpath or jar-pin proof.
  // The worker remains unsupported until all runtime sources/resources and the fixed agent chain
  // are independently pinned and the complete graph is validated.
  private val strictNames = frozenAppGraph?.classNames() ?: emptySet()
  private val trustedRuntimeClasses = trustedRuntimeClasses.toMap()

  init {
    setDefaultAssertionStatus(true)
    if (ownedCoroutineRuntime || frozenAppGraph != null) {
      Runtime.onSkipPrimitive("FrayClassLoader.pinCoroutineArchive")
      try {
        require(urls.all { it.protocol == "file" && it.toURI().authority.isNullOrEmpty() }) {
          "owned coroutine classpath must contain only local file URLs"
        }
        if (ownedCoroutineRuntime) {
          coroutineArchive =
              CoroutineArchivePin.resolve(
                  resources = { resource -> Collections.list(super.findResources(resource)) }
              )
        }
        require(
            this.trustedRuntimeClasses.all { (name, clazz) ->
              name == clazz.name &&
                  name.replace('.', '/') !in strictNames &&
                  !(ownedCoroutineRuntime && name.startsWith("kotlinx.coroutines.")) &&
                  !clazz.isArray &&
                  !clazz.isPrimitive
            }
        ) {
          "invalid trusted runtime class map"
        }
        require(
            trustedRuntimeResolver == null ||
                (frozenAppGraph != null &&
                    this.trustedRuntimeClasses.isEmpty() &&
                    trustedRuntimeResolver.parent === parent)
        ) {
          "invalid frozen trusted runtime resolver"
        }
        CoroutineInstrumentationOwners.register(this)
        ownershipRegistered = true
        if (frozenAppGraph != null) {
          val digests =
              strictNames.associateWith { name ->
                MessageDigest.getInstance("SHA-256")
                    .digest(frozenAppGraph.classBytes(name))
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
              }
          CoroutineInstrumentationOwners.registerAppGraph(this, digests)
        }
      } catch (e: Throwable) {
        if (ownershipRegistered) {
          ownershipRegistered = false
          try {
            CoroutineInstrumentationOwners.unregister(this)
          } catch (failure: Throwable) {
            e.addSuppressed(failure)
          }
        }
        try {
          super.close()
        } catch (closeFailure: Throwable) {
          e.addSuppressed(closeFailure)
        }
        throw e
      } finally {
        Runtime.onSkipPrimitiveDone("FrayClassLoader.pinCoroutineArchive")
      }
    }
  }

  override fun loadClass(name: String): Class<*> {
    if (frozenAppGraph != null) return loadClass(name, false)
    try {
      Runtime.onSkipPrimitive("FrayClassLoader.loadClass")
      synchronized(getClassLoadingLock(name)) {
        if (ownedCoroutineRuntime && name.startsWith("kotlinx.coroutines.")) {
          CoroutineInstrumentationOwners.checkHealthy(this)
        }
        val loaded = findLoadedClass(name)
        if (loaded != null) {
          return loaded
        }
        if (ownedCoroutineRuntime && name.startsWith("kotlinx.coroutines.")) {
          // Every class is defined from the same verified snapshot; never resolve from parent.
          val (classBytes, protectionDomain) =
              try {
                checkNotNull(coroutineArchive).classBytes(name)
              } catch (e: Throwable) {
                CoroutineInstrumentationOwners.markFailure(
                    this,
                    "coroutine provenance failure: $name",
                )
                throw e
              }
          val result = defineClass(name, classBytes, 0, classBytes.size, protectionDomain)
          CoroutineInstrumentationOwners.checkTransformed(this, name.replace('.', '/'))
          return result
        }
        if (isFrayRuntimeClass(name) || skipFrayClassLoading(name)) {
          return super.loadClass(name)
        }
        try {
          val (classBytes, protectionDomain) = getClassBytesAndProtectionDomain(name)
          return defineClass(name, classBytes, 0, classBytes.size, protectionDomain)
        } catch (exception: Exception) {
          return super.loadClass(name)
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone("FrayClassLoader.loadClass")
    }
  }

  /** Closed lookup is deliberately opt-in. Parent resources and unknown classes never fall back. */
  override fun loadClass(name: String, resolve: Boolean): Class<*> {
    val graph = frozenAppGraph ?: return super.loadClass(name, resolve)
    Runtime.onSkipPrimitive("FrayClassLoader.loadFrozenClass")
    try {
      synchronized(getClassLoadingLock(name)) {
        CoroutineInstrumentationOwners.checkHealthy(this)
        val cached = findLoadedClass(name)
        if (cached != null) {
          val internalName = name.replace('.', '/')
          val valid =
              cached.name == name &&
                  ((internalName in strictNames && cached.classLoader === this) ||
                      (ownedCoroutineRuntime &&
                          name.startsWith("kotlinx.coroutines.") &&
                          cached.classLoader === this) ||
                      trustedRuntimeClasses[name] === cached ||
                      trustedRuntimeResolver?.isResolvedIdentity(name, cached) == true)
          if (!valid) {
            CoroutineInstrumentationOwners.markFailure(
                this,
                "unexpected cached frozen class: $name",
            )
            throw IllegalStateException("unexpected cached frozen class")
          }
          if (resolve && cached.classLoader === this) {
            CoroutineInstrumentationOwners.beginCachedResolution(this, internalName)
            try {
              resolveClass(cached)
              CoroutineInstrumentationOwners.finishCachedResolution(this, internalName)
            } catch (failure: Throwable) {
              CoroutineInstrumentationOwners.markFailure(
                  this,
                  "cached frozen resolution failed: $name",
              )
              throw failure
            }
          }
          return cached
        }
        // Pinned coroutine 1.10.1 checks this exact Android marker inside runCatching. Preserve
        // the normal JVM CNFE only after the pinned resolver confirms the parent cannot expose it.
        // Never return a parent class, never generalize to android.*, and poison on ambiguity.
        if (name == "android.os.Build" && trustedRuntimeResolver != null) {
          try {
            check(trustedRuntimeResolver.isVerifiedAbsentAndroidProbe(name))
          } catch (failure: Throwable) {
            CoroutineInstrumentationOwners.markFailure(
                this,
                "Android probe absence not established",
            )
            throw failure
          }
          throw ClassNotFoundException("verified absent JVM-only Android marker")
        }
        val internalName = name.replace('.', '/')
        try {
          var appDefinition = false
          var coroutineDefinition = false
          val result =
              when {
                internalName in strictNames -> {
                  val bytes = graph.classBytes(internalName)
                  CoroutineInstrumentationOwners.beginAppDefinition(this, internalName)
                  appDefinition = true
                  val defined = defineClass(name, bytes, 0, bytes.size)
                  if (defined.classLoader !== this || defined.name != name)
                      error("wrong app class identity")
                  CoroutineInstrumentationOwners.checkAppTransformed(this, internalName)
                  defined
                }
                ownedCoroutineRuntime && name.startsWith("kotlinx.coroutines.") -> {
                  val (bytes, domain) = checkNotNull(coroutineArchive).classBytes(name)
                  CoroutineInstrumentationOwners.expectCoroutineInput(this, internalName, bytes)
                  coroutineDefinition = true
                  val defined = defineClass(name, bytes, 0, bytes.size, domain)
                  if (defined.classLoader !== this || defined.name != name)
                      error("wrong coroutine class identity")
                  CoroutineInstrumentationOwners.checkTransformed(this, internalName)
                  defined
                }
                else ->
                    (trustedRuntimeClasses[name] ?: trustedRuntimeResolver?.resolve(name))
                        ?: throw ClassNotFoundException("untrusted class: $name")
              }
          if (resolve && result.classLoader === this) resolveClass(result)
          if (appDefinition) CoroutineInstrumentationOwners.finishAppDefinition(this, internalName)
          if (coroutineDefinition)
              CoroutineInstrumentationOwners.finishCoroutineDefinition(this, internalName)
          return result
        } catch (failure: Throwable) {
          CoroutineInstrumentationOwners.markFailure(this, "frozen lookup failed: $name")
          throw failure
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone("FrayClassLoader.loadFrozenClass")
    }
  }

  override fun getResource(name: String): URL? {
    if (frozenAppGraph == null) return super.getResource(name)
    CoroutineInstrumentationOwners.markFailure(this, "unsupported frozen resource: $name")
    throw IllegalStateException("resources are unsupported in frozen graph mode")
  }

  override fun getResources(name: String): Enumeration<URL> {
    if (frozenAppGraph == null) return super.getResources(name)
    CoroutineInstrumentationOwners.markFailure(this, "unsupported frozen resources: $name")
    throw IllegalStateException("resources are unsupported in frozen graph mode")
  }

  override fun findResource(name: String): URL? {
    if (frozenAppGraph == null) return super.findResource(name)
    CoroutineInstrumentationOwners.markFailure(this, "unsupported frozen resource search: $name")
    throw IllegalStateException("resource search is unsupported in frozen graph mode")
  }

  override fun findResources(name: String): Enumeration<URL> {
    if (frozenAppGraph == null) return super.findResources(name)
    CoroutineInstrumentationOwners.markFailure(this, "unsupported frozen resource search: $name")
    throw IllegalStateException("resource search is unsupported in frozen graph mode")
  }

  /** Caller must stop workers and callback sources before using this final opt-in check. */
  fun checkFrozenBoundary() {
    val graph = frozenAppGraph ?: throw IllegalStateException("no frozen app graph")
    try {
      trustedRuntimeResolver?.verifySourcesUnchanged()
      CoroutineInstrumentationOwners.checkStrictBoundary(this, graph.entryClass)
    } catch (failure: Throwable) {
      CoroutineInstrumentationOwners.markFailure(this, "frozen boundary validation failed")
      throw failure
    }
  }

  /** Close only after all owned workers and callbacks have stopped. */
  override fun close() {
    Runtime.onSkipPrimitive("FrayClassLoader.close")
    try {
      synchronized(this) {
        try {
          super.close()
        } finally {
          if (ownershipRegistered) {
            ownershipRegistered = false
            CoroutineInstrumentationOwners.unregister(this)
          }
          coroutineArchive = null
        }
      }
    } finally {
      Runtime.onSkipPrimitiveDone("FrayClassLoader.close")
    }
  }

  private fun skipFrayClassLoading(name: String): Boolean {
    // We do not want to reload Mockito classes every time or ByteBuddy classes because
    // they register their own agents and class transformers dynamically, reloading
    // them would register multiple agents and transformers.
    return name.startsWith("org.mockito") || name.startsWith("net.bytebuddy")
  }

  private fun getClassBytesAndProtectionDomain(
      className: String
  ): Pair<ByteArray, ProtectionDomain> {
    val classPath = className.replace('.', '/') + ".class"
    // We should use findResource instead of getResource here to avoid delegation to parent class
    // loader
    val resource = findResource(classPath) ?: throw ClassNotFoundException(className)
    val codeSource = CodeSource(resource, null as Array<java.security.cert.Certificate>?)
    val protectionDomain = ProtectionDomain(codeSource, null)
    resource.openStream().use { stream ->
      return Pair(stream.readAllBytes(), protectionDomain)
    }
  }
}
