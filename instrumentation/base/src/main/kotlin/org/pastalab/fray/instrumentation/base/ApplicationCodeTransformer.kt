package org.pastalab.fray.instrumentation.base

import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.util.CheckClassAdapter
import org.pastalab.fray.instrumentation.base.Utils.isFrayRuntimeClass
import org.pastalab.fray.instrumentation.base.visitors.ArrayOperationInstrumenter
import org.pastalab.fray.instrumentation.base.visitors.ClassConstructorInstrumenter
import org.pastalab.fray.instrumentation.base.visitors.ClassVersionInstrumenter
import org.pastalab.fray.instrumentation.base.visitors.ConditionInstrumenter
import org.pastalab.fray.instrumentation.base.visitors.CoroutineJobInstrumenter
import org.pastalab.fray.instrumentation.base.visitors.CoroutineSchedulerInstrumenter
import org.pastalab.fray.instrumentation.base.visitors.LoadClassInstrumenter
import org.pastalab.fray.instrumentation.base.visitors.MonitorInstrumenter
import org.pastalab.fray.instrumentation.base.visitors.ObjectNotifyInstrumenter
import org.pastalab.fray.instrumentation.base.visitors.SkipPrimitiveInstrumenter
import org.pastalab.fray.instrumentation.base.visitors.SkipScheduleInstrumenter
import org.pastalab.fray.instrumentation.base.visitors.SleepInstrumenter
import org.pastalab.fray.instrumentation.base.visitors.SynchronizedMethodInstrumenter
import org.pastalab.fray.instrumentation.base.visitors.TargetExitInstrumenter
import org.pastalab.fray.instrumentation.base.visitors.TimeInstrumenter
import org.pastalab.fray.instrumentation.base.visitors.TimedWaitInstrumenter
import org.pastalab.fray.instrumentation.base.visitors.VolatileFieldsInstrumenter
import org.pastalab.fray.runtime.CoroutineInstrumentationOwners
import org.pastalab.fray.runtime.Runtime

class ApplicationCodeTransformer(
    val interleaveAllMemoryOps: Boolean = false,
    private val excludedClassPrefixes: List<String> = configuredExcludedClassPrefixes(),
) : ClassFileTransformer {

  companion object {
    const val EXCLUDE_PREFIXES_PROPERTY = "fray.transformation.excludePrefixes"

    private fun configuredExcludedClassPrefixes(): List<String> =
        System.getProperty(EXCLUDE_PREFIXES_PROPERTY, "")
            .split(',')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { it.replace('/', '.') }
            .toList()
  }

  val instrumentedClassCache = mutableMapOf<String, ByteArray>()

  override fun transform(
      loader: ClassLoader?,
      className: String,
      classBeingRedefined: Class<*>?,
      protectionDomain: ProtectionDomain?,
      classfileBuffer: ByteArray,
  ): ByteArray {
    if (
        className.startsWith("kotlinx/coroutines/") &&
            Runtime.hasCoroutineInstrumentationOwner() &&
            CoroutineInstrumentationOwners.owns(loader)
    ) {
      if (classBeingRedefined != null) {
        CoroutineInstrumentationOwners.markFailure(
            loader,
            "owned coroutine redefinition: $className",
        )
        return classfileBuffer
      }
      // Partial exclusions would silently leave scheduler primitives uncontrolled. Reject the
      // entire opt-in epoch instead of falling back to the host or uninstrumented coroutine code.
      val coroutinePrefix = "kotlinx.coroutines"
      if (
          excludedClassPrefixes.any {
            coroutinePrefix.startsWith(it) || it.startsWith("$coroutinePrefix.")
          }
      ) {
        CoroutineInstrumentationOwners.markFailure(loader, "coroutine classes are excluded")
        return classfileBuffer
      }
      try {
        Runtime.onSkipPrimitive("instrumentation")
        if (CoroutineInstrumentationOwners.hasAppGraph(loader)) {
          CoroutineInstrumentationOwners.verifyCoroutineInput(loader, className, classfileBuffer)
        }
        val reader = ClassReader(classfileBuffer)
        val node = ClassNode()
        var visitor: ClassVisitor = CoroutineJobInstrumenter(node)
        visitor = CoroutineSchedulerInstrumenter(visitor)
        visitor = ClassConstructorInstrumenter(visitor, false, experimentalSkipPrimitive = true)
        visitor = TimedWaitInstrumenter(visitor)
        // Experimental only: route the owned worker RNG's nanoTime seed through Fray's clock.
        visitor = TimeInstrumenter(visitor)
        visitor = SynchronizedMethodInstrumenter(visitor, false)
        visitor = MonitorInstrumenter(visitor)
        val versionVisitor = ClassVersionInstrumenter(visitor)
        reader.accept(versionVisitor, ClassReader.EXPAND_FRAMES)
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
        if (versionVisitor.classVersion >= Opcodes.V1_5) {
          node.accept(CheckClassAdapter(writer))
        } else {
          node.accept(writer)
        }
        val output = writer.toByteArray()
        require(ClassReader(output).className == className) {
          "owned coroutine output name mismatch"
        }
        CoroutineInstrumentationOwners.markTransformed(loader, className)
        return output
      } catch (e: Throwable) {
        // The JVM may suppress transformer exceptions. The owning loader checks this poison state
        // after defineClass and must not fall back to the parent or treat the epoch as successful.
        CoroutineInstrumentationOwners.markFailure(loader, "$className: ${e.javaClass.name}")
        return classfileBuffer
      } finally {
        Runtime.onSkipPrimitiveDone("instrumentation")
      }
    }
    // Lab-only verified app graph. The normal transformer below remains unchanged for every
    // loader without a registered graph. A fixed, trusted agent chain is a required process
    // precondition: this handshake cannot detect a transformer running after Fray.
    val strictApp =
        Runtime.hasCoroutineInstrumentationOwner() &&
            CoroutineInstrumentationOwners.hasAppGraph(loader)
    if (strictApp) {
      val excluded = excludedClassPrefixes.any(className.replace('/', '.')::startsWith)
      if (
          !CoroutineInstrumentationOwners.expectsApp(loader, className) ||
              classBeingRedefined != null ||
              excluded ||
              isFrayRuntimeClass(className) ||
              className.startsWith("org/mockito") ||
              className.startsWith("net/bytebuddy")
      ) {
        CoroutineInstrumentationOwners.markFailure(
            loader,
            "unsupported owned app transform: $className",
        )
        return classfileBuffer
      }
      try {
        CoroutineInstrumentationOwners.verifyAppInput(loader, className, classfileBuffer)
      } catch (failure: Throwable) {
        CoroutineInstrumentationOwners.markFailure(loader, "invalid owned app input: $className")
        return classfileBuffer
      }
    }
    // Explicit user exclusions also apply to the narrowly instrumented coroutine classes.
    if (excludedClassPrefixes.any(className.replace('/', '.')::startsWith)) {
      return classfileBuffer
    }
    if (className.startsWith("kotlinx/coroutines/")) {
      // Coroutine internals are otherwise excluded. A JVM atomic call from their <clinit>
      // can still enter Fray via JDK instrumentation while holding the JVM initialization
      // lock. Keep the existing class-initialization scheduling guard without enabling
      // application instrumentation throughout the coroutine library.
      try {
        Runtime.onSkipPrimitive("instrumentation")
        val reader = ClassReader(classfileBuffer)
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
        reader.accept(ClassConstructorInstrumenter(writer, false), ClassReader.EXPAND_FRAMES)
        return writer.toByteArray()
      } catch (e: Throwable) {
        println("Failed to instrument coroutine initializer: $className")
        e.printStackTrace()
        return classfileBuffer
      } finally {
        Runtime.onSkipPrimitiveDone("instrumentation")
      }
    }
    if (isFrayRuntimeClass(className)) return classfileBuffer
    val classIdentifier = (protectionDomain?.codeSource?.location?.path ?: "null:") + className
    if (!strictApp && instrumentedClassCache.containsKey(classIdentifier)) {
      return instrumentedClassCache[classIdentifier]!!
    }
    try {
      Runtime.onSkipPrimitive("instrumentation")
      if (Runtime.getDebugMode()) {
        Utils.writeClassFile(className, classfileBuffer, false)
      }
      val classReader = ClassReader(classfileBuffer)
      val cn = ClassNode()
      var cv: ClassVisitor = ObjectNotifyInstrumenter(cn)
      cv = TargetExitInstrumenter(cv)
      cv = TimedWaitInstrumenter(cv)
      // Scope the all-memory mode to frozen app classes even when the normal agent defaults to
      // volatile-only. The visitor still excludes constructors and class initializers, so this
      // handshake does not establish complete plain-field coverage.
      cv = VolatileFieldsInstrumenter(cv, false, interleaveAllMemoryOps || strictApp)
      cv = ObjectNotifyInstrumenter(cv)

      cv =
          SynchronizedMethodInstrumenter(
              cv,
              false,
          ) // Synchronized Method Instrumenter should be before Monitor Instrumenter
      cv = MonitorInstrumenter(cv)

      cv = ConditionInstrumenter(cv)
      cv = ClassConstructorInstrumenter(cv, false)
      cv = SleepInstrumenter(cv, false)
      cv = TimeInstrumenter(cv)
      cv = SkipPrimitiveInstrumenter(cv)
      cv = SkipScheduleInstrumenter(cv)
      cv = LoadClassInstrumenter(cv)
      //      cv = ObjectHashCodeInstrumenter(cv, false)
      //      cv = AtomicGetInstrumenter(cv)
      //      cv = ToStringInstrumenter(cv)
      val classVersionInstrumenter = ClassVersionInstrumenter(cv)
      cv = ArrayOperationInstrumenter(classVersionInstrumenter)
      classReader.accept(cv, ClassReader.EXPAND_FRAMES)
      val classWriter = ClassWriter(classReader, ClassWriter.COMPUTE_MAXS)
      if (classVersionInstrumenter.classVersion >= Opcodes.V1_5) {
        val checkClassAdapter = CheckClassAdapter(classWriter)
        cn.accept(checkClassAdapter)
      } else {
        cn.accept(classWriter)
      }
      val out = classWriter.toByteArray()
      if (Runtime.getDebugMode()) {
        Utils.writeClassFile(className, out, true)
      }
      if (strictApp) {
        require(ClassReader(out).className == className) { "owned app output name mismatch" }
        CoroutineInstrumentationOwners.markAppTransformed(loader, className, out)
      } else {
        instrumentedClassCache[classIdentifier] = out
      }
      return out
    } catch (e: Throwable) {
      if (strictApp) {
        CoroutineInstrumentationOwners.markFailure(
            loader,
            "owned app transformation failed: $className",
        )
      }
      println("Failed to instrument: $className")
      e.printStackTrace()
    } finally {
      Runtime.onSkipPrimitiveDone("instrumentation")
    }
    return classfileBuffer
  }
}
