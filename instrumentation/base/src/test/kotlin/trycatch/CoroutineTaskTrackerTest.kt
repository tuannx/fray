package trycatch

import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.pastalab.fray.runtime.CoroutineEpochGate
import org.pastalab.fray.runtime.CoroutineInstrumentationOwners
import org.pastalab.fray.runtime.CoroutineJobTracker
import org.pastalab.fray.runtime.CoroutineTaskTracker

class CoroutineTaskTrackerTest {
  @Test
  fun thirdGenerationIsRejectedWhileTwoGenerationsStillRun() {
    val f = Fixture()
    try {
      f.completeRoot()
      val task = Runnable {}
      repeat(2) {
        CoroutineTaskTracker.dispatchBegin(f.scheduler)
        CoroutineTaskTracker.registerTask(f.scheduler, task)
        CoroutineTaskTracker.dispatchEnd(f.scheduler, false)
        CoroutineTaskTracker.runBegin(f.scheduler, task)
      }
      CoroutineTaskTracker.dispatchBegin(f.scheduler)
      assertFailsWith<IllegalStateException> {
        CoroutineTaskTracker.registerTask(f.scheduler, task)
      }
      CoroutineTaskTracker.dispatchEnd(f.scheduler, true)
      CoroutineTaskTracker.runEnd(f.scheduler, task, false)
      CoroutineTaskTracker.runEnd(f.scheduler, task, false)
      assertFailsWith<IllegalStateException> { CoroutineEpochGate.atomicSeal(f.loader) }
    } finally {
      f.discardForUnitTest()
    }
  }

  private class OwnedLoader(parent: ClassLoader) : ClassLoader(parent) {
    fun make(binaryName: String): Class<*> {
      val internalName = binaryName.replace('.', '/')
      val writer = ClassWriter(0)
      writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
      val init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
      init.visitCode()
      init.visitVarInsn(Opcodes.ALOAD, 0)
      init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
      init.visitInsn(Opcodes.RETURN)
      init.visitMaxs(1, 1)
      init.visitEnd()
      writer.visitEnd()
      val bytes = writer.toByteArray()
      return defineClass(binaryName, bytes, 0, bytes.size)
    }

    fun defaultExecutor(empty: Boolean): Any {
      val baseName = "kotlinx/coroutines/EventLoopImplBase"
      val baseWriter = ClassWriter(0)
      baseWriter.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, baseName, null, "java/lang/Object", null)
      baseWriter.visitField(Opcodes.ACC_PUBLIC, "empty", "Z", null, null).visitEnd()
      val baseInit = baseWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
      baseInit.visitCode()
      baseInit.visitVarInsn(Opcodes.ALOAD, 0)
      baseInit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
      baseInit.visitInsn(Opcodes.RETURN)
      baseInit.visitMaxs(1, 1)
      baseInit.visitEnd()
      val isEmpty = baseWriter.visitMethod(Opcodes.ACC_PROTECTED, "isEmpty", "()Z", null, null)
      isEmpty.visitCode()
      isEmpty.visitVarInsn(Opcodes.ALOAD, 0)
      isEmpty.visitFieldInsn(Opcodes.GETFIELD, baseName, "empty", "Z")
      isEmpty.visitInsn(Opcodes.IRETURN)
      isEmpty.visitMaxs(1, 1)
      isEmpty.visitEnd()
      baseWriter.visitEnd()
      val baseBytes = baseWriter.toByteArray()
      val base = defineClass(baseName.replace('/', '.'), baseBytes, 0, baseBytes.size)

      val name = "kotlinx/coroutines/DefaultExecutor"
      val writer = ClassWriter(0)
      writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, name, null, baseName, null)
      writer
          .visitField(
              Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
              "INSTANCE",
              "L$name;",
              null,
              null,
          )
          .visitEnd()
      val init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
      init.visitCode()
      init.visitVarInsn(Opcodes.ALOAD, 0)
      init.visitMethodInsn(Opcodes.INVOKESPECIAL, baseName, "<init>", "()V", false)
      init.visitInsn(Opcodes.RETURN)
      init.visitMaxs(1, 1)
      init.visitEnd()
      val clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
      clinit.visitCode()
      clinit.visitTypeInsn(Opcodes.NEW, name)
      clinit.visitInsn(Opcodes.DUP)
      clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, name, "<init>", "()V", false)
      clinit.visitFieldInsn(Opcodes.PUTSTATIC, name, "INSTANCE", "L$name;")
      clinit.visitInsn(Opcodes.RETURN)
      clinit.visitMaxs(2, 0)
      clinit.visitEnd()
      writer.visitEnd()
      val bytes = writer.toByteArray()
      val type = defineClass(name.replace('/', '.'), bytes, 0, bytes.size)
      val instance = type.getField("INSTANCE").get(null)
      base.getField("empty").setBoolean(instance, empty)
      return instance
    }
  }

  private class Fixture {
    val loader = OwnedLoader(CoroutineTaskTrackerTest::class.java.classLoader)
    val rootClass = loader.make("kotlinx.coroutines.BlockingCoroutine")
    val root = rootClass.getDeclaredConstructor().newInstance()
    val scheduler = loader.make("test.Scheduler").getDeclaredConstructor().newInstance()

    init {
      CoroutineInstrumentationOwners.register(loader)
      CoroutineEpochGate.begin(loader)
      CoroutineJobTracker.begin(loader, rootClass, Thread.currentThread())
      CoroutineTaskTracker.begin(loader)
    }

    fun completeRoot() {
      CoroutineJobTracker.constructingEnter(root)
      CoroutineJobTracker.created(root)
      CoroutineJobTracker.parentInitialized(root, null)
      CoroutineJobTracker.finalizationEnter(root)
      CoroutineJobTracker.finalizationExit(root, null, false)
    }

    fun cleanEnd() {
      CoroutineEpochGate.endAfterTermination(loader)
      CoroutineInstrumentationOwners.unregister(loader)
    }

    // A failed epoch intentionally cannot be reset in production. Unit tests discard synthetic
    // state via reflection only to keep other tests in this same test JVM independent.
    fun discardForUnitTest() {
      fun clear(type: Class<*>, name: String, value: Any?) {
        val field = type.getDeclaredField(name)
        field.isAccessible = true
        field.set(null, value)
      }
      clear(CoroutineJobTracker::class.java, "current", null)
      clear(CoroutineTaskTracker::class.java, "current", null)
      clear(CoroutineEpochGate::class.java, "owner", null)
      clear(CoroutineEpochGate::class.java, "sealed", false)
      clear(CoroutineEpochGate::class.java, "broken", false)
      clear(CoroutineEpochGate::class.java, "staleEvent", false)
      clear(CoroutineEpochGate::class.java, "permittedRootFailure", null)
      CoroutineInstrumentationOwners.unregister(loader)
    }
  }

  @Test
  fun cannotSealWhileConstructorOrDispatchIsInFlight() {
    val f = Fixture()
    try {
      CoroutineJobTracker.constructingEnter(f.root)
      assertFailsWith<IllegalStateException> { CoroutineEpochGate.atomicSeal(f.loader) }
      CoroutineJobTracker.created(f.root)
      CoroutineJobTracker.parentInitialized(f.root, null)
      CoroutineJobTracker.finalizationEnter(f.root)
      CoroutineJobTracker.finalizationExit(f.root, null, false)

      CoroutineTaskTracker.dispatchBegin(f.scheduler)
      val task = Runnable {}
      CoroutineTaskTracker.registerTask(f.scheduler, task)
      // A worker may start and finish before the publishing dispatch method returns.
      CoroutineTaskTracker.runBegin(f.scheduler, task)
      CoroutineTaskTracker.runTask(f.scheduler, task)
      CoroutineTaskTracker.runEnd(f.scheduler, task, false)
      assertFailsWith<IllegalStateException> { CoroutineEpochGate.atomicSeal(f.loader) }
      CoroutineTaskTracker.dispatchEnd(f.scheduler, false)
      assertTrue(CoroutineEpochGate.atomicSeal(f.loader).contains("outstanding=0"))
      assertTrue(CoroutineEpochGate.verifySealed(f.loader).contains("running=0"))
      f.cleanEnd()
    } catch (error: Throwable) {
      f.discardForUnitTest()
      throw error
    }
  }

  @Test
  fun redispatchWhileOriginalRunsCanFinishOnAnotherThreadFirst() {
    val f = Fixture()
    var worker: Thread? = null
    try {
      f.completeRoot()
      val task = Runnable {}
      CoroutineTaskTracker.dispatchBegin(f.scheduler)
      CoroutineTaskTracker.registerTask(f.scheduler, task)
      CoroutineTaskTracker.dispatchEnd(f.scheduler, false)
      CoroutineTaskTracker.runBegin(f.scheduler, task)

      // A running continuation can publish itself again; it is a distinct dispatch.
      CoroutineTaskTracker.dispatchBegin(f.scheduler)
      CoroutineTaskTracker.registerTask(f.scheduler, task)
      CoroutineTaskTracker.dispatchEnd(f.scheduler, false)
      assertTrue(CoroutineTaskTracker.diagnosticSnapshot(f.loader).contains("outstanding=2"))

      val error = AtomicReference<Throwable>()
      val startedWorker = Thread {
        try {
          CoroutineTaskTracker.runBegin(f.scheduler, task)
          CoroutineTaskTracker.runEnd(f.scheduler, task, false)
        } catch (failure: Throwable) {
          error.set(failure)
        }
      }
      startedWorker.isDaemon = true
      worker = startedWorker
      startedWorker.start()
      startedWorker.join(5_000)
      if (startedWorker.isAlive) {
        startedWorker.interrupt()
        startedWorker.join(1_000)
      }
      assertFalse(startedWorker.isAlive, "worker did not finish within the test timeout")
      error.get()?.let { throw AssertionError("second generation failed", it) }
      val whileFirstRuns = CoroutineTaskTracker.diagnosticSnapshot(f.loader)
      assertTrue(whileFirstRuns.contains("outstanding=1"), whileFirstRuns)
      assertTrue(whileFirstRuns.contains("running=1"), whileFirstRuns)
      CoroutineTaskTracker.runEnd(f.scheduler, task, false)
      assertTrue(CoroutineEpochGate.atomicSeal(f.loader).contains("outstanding=0"))
      f.cleanEnd()
    } catch (error: Throwable) {
      // Never reset bookkeeping under a still-running worker.
      if (worker?.isAlive != true) f.discardForUnitTest()
      throw error
    }
  }

  @Test
  fun finishedGenerationCanBeRedispatchedBeforeItsPublisherReturns() {
    val f = Fixture()
    try {
      f.completeRoot()
      val task = Runnable {}
      CoroutineTaskTracker.dispatchBegin(f.scheduler)
      CoroutineTaskTracker.registerTask(f.scheduler, task)
      CoroutineTaskTracker.runBegin(f.scheduler, task)
      CoroutineTaskTracker.runEnd(f.scheduler, task, false)

      CoroutineTaskTracker.dispatchBegin(f.scheduler)
      CoroutineTaskTracker.registerTask(f.scheduler, task)
      CoroutineTaskTracker.dispatchEnd(f.scheduler, false)
      CoroutineTaskTracker.runBegin(f.scheduler, task)
      CoroutineTaskTracker.runEnd(f.scheduler, task, false)
      // The first publisher is still on the stack even though both runs are finished.
      assertFailsWith<IllegalStateException> { CoroutineEpochGate.atomicSeal(f.loader) }
      CoroutineTaskTracker.dispatchEnd(f.scheduler, false)
      assertTrue(CoroutineEpochGate.atomicSeal(f.loader).contains("outstanding=0"))
      f.cleanEnd()
    } catch (error: Throwable) {
      f.discardForUnitTest()
      throw error
    }
  }

  @Test
  fun sameThreadNestedRunsPairWithTheirOwnGeneration() {
    val f = Fixture()
    try {
      f.completeRoot()
      val task = Runnable {}
      CoroutineTaskTracker.dispatchBegin(f.scheduler)
      CoroutineTaskTracker.registerTask(f.scheduler, task)
      CoroutineTaskTracker.dispatchEnd(f.scheduler, false)
      CoroutineTaskTracker.runBegin(f.scheduler, task)
      CoroutineTaskTracker.dispatchBegin(f.scheduler)
      CoroutineTaskTracker.registerTask(f.scheduler, task)
      CoroutineTaskTracker.dispatchEnd(f.scheduler, false)
      CoroutineTaskTracker.runBegin(f.scheduler, task)
      CoroutineTaskTracker.runEnd(f.scheduler, task, false)
      val afterInner = CoroutineTaskTracker.diagnosticSnapshot(f.loader)
      assertTrue(afterInner.contains("running=1"), afterInner)
      assertTrue(afterInner.contains("outstanding=1"), afterInner)
      CoroutineTaskTracker.runEnd(f.scheduler, task, false)
      assertTrue(CoroutineEpochGate.atomicSeal(f.loader).contains("outstanding=0"))
      f.cleanEnd()
    } catch (error: Throwable) {
      f.discardForUnitTest()
      throw error
    }
  }

  @Test
  fun twoUnstartedGenerationsOfTheSameTaskAreRejected() {
    val f = Fixture()
    try {
      f.completeRoot()
      val task = Runnable {}
      CoroutineTaskTracker.dispatchBegin(f.scheduler)
      CoroutineTaskTracker.registerTask(f.scheduler, task)
      CoroutineTaskTracker.dispatchEnd(f.scheduler, false)
      CoroutineTaskTracker.dispatchBegin(f.scheduler)
      assertFailsWith<IllegalStateException> {
        CoroutineTaskTracker.registerTask(f.scheduler, task)
      }
      CoroutineTaskTracker.dispatchEnd(f.scheduler, true)
      assertFailsWith<IllegalStateException> { CoroutineEpochGate.atomicSeal(f.loader) }
    } finally {
      f.discardForUnitTest()
    }
  }

  @Test
  fun failedNestedRunBeginDoesNotConsumeOuterRunFrame() {
    val f = Fixture()
    try {
      f.completeRoot()
      val task = Runnable {}
      CoroutineTaskTracker.dispatchBegin(f.scheduler)
      CoroutineTaskTracker.registerTask(f.scheduler, task)
      CoroutineTaskTracker.dispatchEnd(f.scheduler, false)
      CoroutineTaskTracker.runBegin(f.scheduler, task)
      assertFailsWith<IllegalStateException> { CoroutineTaskTracker.runBegin(f.scheduler, task) }
      val afterFailure = CoroutineTaskTracker.diagnosticSnapshot(f.loader)
      assertTrue(afterFailure.contains("running=1"), afterFailure)
      assertTrue(afterFailure.contains("outstanding=1"), afterFailure)
      CoroutineTaskTracker.runEnd(f.scheduler, task, false)
      val afterOuter = CoroutineTaskTracker.diagnosticSnapshot(f.loader)
      assertTrue(afterOuter.contains("running=0"), afterOuter)
      assertTrue(afterOuter.contains("outstanding=0"), afterOuter)
      assertFailsWith<IllegalStateException> { CoroutineEpochGate.atomicSeal(f.loader) }
    } finally {
      f.discardForUnitTest()
    }
  }

  @Test
  fun lateDispatchTaintsSealedEpoch() {
    val f = Fixture()
    try {
      f.completeRoot()
      CoroutineEpochGate.atomicSeal(f.loader)
      assertFailsWith<IllegalStateException> { CoroutineTaskTracker.dispatchBegin(f.scheduler) }
      assertFailsWith<IllegalStateException> { CoroutineEpochGate.verifySealed(f.loader) }
    } finally {
      f.discardForUnitTest()
    }
  }

  @Test
  fun swallowedTaskFailurePreventsSeal() {
    val f = Fixture()
    try {
      f.completeRoot()
      CoroutineTaskTracker.dispatchBegin(f.scheduler)
      val failure = IllegalArgumentException("worker failure")
      val task = Runnable { throw failure }
      CoroutineTaskTracker.registerTask(f.scheduler, task)
      CoroutineTaskTracker.dispatchEnd(f.scheduler, false)
      CoroutineTaskTracker.runBegin(f.scheduler, task)
      val caught =
          assertFailsWith<IllegalArgumentException> {
            CoroutineTaskTracker.runTask(f.scheduler, task)
          }
      assertSame(failure, caught)
      CoroutineTaskTracker.runEnd(f.scheduler, task, false)
      assertFailsWith<IllegalStateException> { CoroutineEpochGate.atomicSeal(f.loader) }
    } finally {
      f.discardForUnitTest()
    }
  }

  @Test
  fun unknownTaskCannotConsumeAnotherTasksReservation() {
    val f = Fixture()
    try {
      f.completeRoot()
      val expected = Runnable {}
      CoroutineTaskTracker.dispatchBegin(f.scheduler)
      CoroutineTaskTracker.registerTask(f.scheduler, expected)
      CoroutineTaskTracker.dispatchEnd(f.scheduler, false)
      assertFailsWith<IllegalStateException> {
        CoroutineTaskTracker.runBegin(f.scheduler, Runnable {})
      }
      assertFailsWith<IllegalStateException> { CoroutineEpochGate.atomicSeal(f.loader) }
    } finally {
      f.discardForUnitTest()
    }
  }

  @Test
  fun unsupportedTimerAlonePreventsSeal() {
    val f = Fixture()
    try {
      f.completeRoot()
      assertFailsWith<IllegalStateException> {
        CoroutineTaskTracker.rejectUnsupported(f.scheduler, "delay")
      }
      assertFailsWith<IllegalStateException> { CoroutineEpochGate.atomicSeal(f.loader) }
    } finally {
      f.discardForUnitTest()
    }
  }

  @Test
  fun lateJobConstructionTaintsSealedEpoch() {
    val f = Fixture()
    try {
      f.completeRoot()
      CoroutineEpochGate.atomicSeal(f.loader)
      assertFailsWith<IllegalStateException> { CoroutineJobTracker.constructingEnter(f.root) }
      assertFailsWith<IllegalStateException> { CoroutineEpochGate.verifySealed(f.loader) }
    } finally {
      f.discardForUnitTest()
    }
  }

  @Test
  fun sealedOwnerMayPollOnlyTheEmptyOwnedDefaultExecutor() {
    val f = Fixture()
    try {
      f.completeRoot()
      val loop = f.loader.defaultExecutor(true)
      CoroutineEpochGate.atomicSeal(f.loader)
      CoroutineTaskTracker.pollEventLoop(loop)
      CoroutineTaskTracker.unconfinedProcessed(loop, false)
      assertTrue(CoroutineEpochGate.verifySealed(f.loader).contains("shutdownPollActive=false"))
      f.cleanEnd()
    } catch (error: Throwable) {
      f.discardForUnitTest()
      throw error
    }
  }

  @Test
  fun nonemptyShutdownPollAndLateEnqueueAreRejected() {
    val nonempty = Fixture()
    try {
      nonempty.completeRoot()
      val loop = nonempty.loader.defaultExecutor(false)
      CoroutineEpochGate.atomicSeal(nonempty.loader)
      assertFailsWith<IllegalStateException> { CoroutineTaskTracker.pollEventLoop(loop) }
      assertFailsWith<IllegalStateException> { CoroutineEpochGate.verifySealed(nonempty.loader) }
    } finally {
      nonempty.discardForUnitTest()
    }
    val late = Fixture()
    try {
      late.completeRoot()
      val loop = late.loader.defaultExecutor(true)
      CoroutineEpochGate.atomicSeal(late.loader)
      assertFailsWith<IllegalStateException> {
        CoroutineTaskTracker.enqueueBegin(loop, Runnable {})
      }
      assertFailsWith<IllegalStateException> { CoroutineEpochGate.verifySealed(late.loader) }
    } finally {
      late.discardForUnitTest()
    }
  }

  @Test
  fun nonOwnerAndUnconfinedShutdownEventsAreRejected() {
    val foreign = Fixture()
    try {
      foreign.completeRoot()
      val loop = foreign.loader.defaultExecutor(true)
      CoroutineEpochGate.atomicSeal(foreign.loader)
      val error = AtomicReference<Throwable>()
      val worker = Thread {
        try {
          CoroutineTaskTracker.pollEventLoop(loop)
        } catch (failure: Throwable) {
          error.set(failure)
        }
      }
      worker.start()
      worker.join()
      assertTrue(error.get() is IllegalStateException)
      assertFailsWith<IllegalStateException> { CoroutineEpochGate.verifySealed(foreign.loader) }
    } finally {
      foreign.discardForUnitTest()
    }
    val unconfined = Fixture()
    try {
      unconfined.completeRoot()
      val loop = unconfined.loader.defaultExecutor(true)
      CoroutineEpochGate.atomicSeal(unconfined.loader)
      CoroutineTaskTracker.pollEventLoop(loop)
      assertFailsWith<IllegalStateException> {
        CoroutineTaskTracker.unconfinedProcessed(loop, true)
      }
      assertFailsWith<IllegalStateException> { CoroutineEpochGate.verifySealed(unconfined.loader) }
    } finally {
      unconfined.discardForUnitTest()
    }
  }

  @Test
  fun nonDefaultLoopAndUnpairedPollCompletionAreRejected() {
    val other = Fixture()
    try {
      other.completeRoot()
      CoroutineEpochGate.atomicSeal(other.loader)
      assertFailsWith<IllegalStateException> { CoroutineTaskTracker.pollEventLoop(other.scheduler) }
      assertFailsWith<IllegalStateException> { CoroutineEpochGate.verifySealed(other.loader) }
    } finally {
      other.discardForUnitTest()
    }
    val unpaired = Fixture()
    try {
      unpaired.completeRoot()
      val loop = unpaired.loader.defaultExecutor(true)
      CoroutineEpochGate.atomicSeal(unpaired.loader)
      assertFailsWith<IllegalStateException> {
        CoroutineTaskTracker.unconfinedProcessed(loop, false)
      }
      assertFailsWith<IllegalStateException> { CoroutineEpochGate.verifySealed(unpaired.loader) }
    } finally {
      unpaired.discardForUnitTest()
    }
  }
}
