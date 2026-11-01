package org.pastalab.fray.core.observers

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.pastalab.fray.core.RunContext
import org.pastalab.fray.core.ThreadContext
import org.pastalab.fray.core.command.Configuration
import org.pastalab.fray.core.command.ExecutionInfo
import org.pastalab.fray.core.command.LambdaExecutor
import org.pastalab.fray.core.command.NetworkDelegateType
import org.pastalab.fray.core.command.SystemTimeDelegateType
import org.pastalab.fray.core.randomness.ControlledRandom
import org.pastalab.fray.core.randomness.ControlledRandomProvider
import org.pastalab.fray.core.randomness.Randomness
import org.pastalab.fray.core.scheduler.RandomScheduler
import org.pastalab.fray.core.scheduler.Scheduler
import org.pastalab.fray.rmi.ThreadState
import org.pastalab.fray.runtime.CoroutineInstrumentationOwners

class StrictStateCaptureV1Test {
  private fun context(): RunContext =
      RunContext(
          Configuration(
              ExecutionInfo(LambdaExecutor({}), false, false, -1),
              Files.createTempDirectory("strict-state-context"),
              1,
              30,
              RandomScheduler(),
              ControlledRandomProvider(),
              fullSchedule = false,
              exploreMode = false,
              noExitWhenBugFound = true,
              isReplay = true,
              noFray = false,
              dummyRun = false,
              networkDelegateType = NetworkDelegateType.NONE,
              systemTimeDelegateType = SystemTimeDelegateType.NONE,
              virtualTimeDelta = 100_000L,
              ignoreTimedBlock = true,
              sleepAsYield = false,
              resetClassLoader = true,
              redirectStdout = false,
              abortThreadExecutionAfterMainExit = false,
              resolveRacingOperationStackTraceHash = false,
              timelineCoverageType = TimelineCoverageType.NONE,
          )
      )

  @Test
  fun decoratorCapturesActualCandidatesWithoutExtraRandomDraws() {
    val context = context()
    try {
      val first = ThreadContext(Thread(), 2, context, 0)
      val second = ThreadContext(Thread(), 1, context, 0)
      val random = ControlledRandom(mutableListOf(17, 19))
      val delegate =
          object : Scheduler(random) {
            override fun scheduleNextOperation(
                threads: List<ThreadContext>,
                allThreads: Collection<ThreadContext>,
            ): ThreadContext {
              assertEquals(17, rand.nextInt())
              return threads.last()
            }

            override fun nextIteration(randomness: Randomness): Scheduler =
                RandomScheduler(randomness)
          }
      val capture = StrictStateCapture()
      val wrapper = StrictStateCapturingScheduler(delegate, capture)
      assertSame(random, wrapper.rand)
      // The all-thread collection is deliberately larger than the real candidate list.
      val selected = wrapper.scheduleNextOperation(listOf(first), listOf(first, second))
      assertSame(first, selected)
      assertEquals(listOf(2), capture.take(selected))
      assertEquals(19, random.nextInt())
      assertFailsWith<IllegalStateException> { capture.take(selected) }
      capture.record(listOf(first, second), first)
      assertEquals(listOf(1, 2), capture.take(first))
      val nextRandom = ControlledRandom()
      assertSame(nextRandom, wrapper.nextIteration(nextRandom).rand)
      val mutating =
          object : Scheduler(ControlledRandom()) {
            override fun scheduleNextOperation(
                threads: List<ThreadContext>,
                allThreads: Collection<ThreadContext>,
            ): ThreadContext {
              (threads as MutableList<ThreadContext>).removeAt(0)
              return threads.first()
            }

            override fun nextIteration(randomness: Randomness): Scheduler = this
          }
      assertFailsWith<IllegalStateException> {
        StrictStateCapturingScheduler(mutating, StrictStateCapture())
            .scheduleNextOperation(mutableListOf(first, second), listOf(first, second))
      }
    } finally {
      context.shutDown()
    }
  }

  private class WorkerLoader : ClassLoader(StrictStateCaptureV1Test::class.java.classLoader) {
    fun worker(includeRng: Boolean): Thread {
      val internalName = StrictStateSnapshotter.WORKER_CLASS.replace('.', '/')
      val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
      writer.visit(
          Opcodes.V11,
          Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
          internalName,
          null,
          "java/lang/Thread",
          null,
      )
      if (includeRng) writer.visitField(Opcodes.ACC_PRIVATE, "rngState", "I", null, null).visitEnd()
      val init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
      init.visitCode()
      init.visitVarInsn(Opcodes.ALOAD, 0)
      init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Thread", "<init>", "()V", false)
      if (includeRng) {
        init.visitVarInsn(Opcodes.ALOAD, 0)
        init.visitIntInsn(Opcodes.BIPUSH, 42)
        init.visitFieldInsn(Opcodes.PUTFIELD, internalName, "rngState", "I")
      }
      init.visitInsn(Opcodes.RETURN)
      init.visitMaxs(0, 0)
      init.visitEnd()
      writer.visitEnd()
      val bytes = writer.toByteArray()
      val clazz = defineClass(StrictStateSnapshotter.WORKER_CLASS, bytes, 0, bytes.size)
      return clazz.getConstructor().newInstance() as Thread
    }
  }

  @Test
  fun snapshotReadsOnlyOwnedPinnedWorkerRngAndRejectsMissingField() {
    val context = context()
    val loader = WorkerLoader()
    try {
      val app = ThreadContext(Thread(), 0, context, 0).apply { state = ThreadState.Runnable }
      val thread = loader.worker(true)
      val worker = ThreadContext(thread, 1, context, 0).apply { state = ThreadState.Runnable }
      val snapshotter = StrictStateSnapshotter()
      assertFailsWith<IllegalStateException> {
        snapshotter.snapshot(worker, listOf(0, 1), listOf(app, worker))
      }
      CoroutineInstrumentationOwners.register(loader)
      try {
        val snapshot = snapshotter.snapshot(worker, listOf(0, 1), listOf(app, worker))
        assertEquals(42, snapshot.threads[1].workerRngState)
        val field = thread.javaClass.getDeclaredField("rngState")
        field.isAccessible = true
        field.setInt(thread, 43)
        assertEquals(
            43,
            snapshotter
                .snapshot(worker, listOf(0, 1), listOf(app, worker))
                .threads[1]
                .workerRngState,
        )
      } finally {
        CoroutineInstrumentationOwners.unregister(loader)
      }
      // A previously seen loader is insufficient while its worker is not completed.
      assertFailsWith<IllegalStateException> {
        snapshotter.snapshot(worker, listOf(0, 1), listOf(app, worker))
      }
      val malformed = WorkerLoader()
      val bad =
          ThreadContext(malformed.worker(false), 1, context, 0).apply {
            state = ThreadState.Runnable
          }
      CoroutineInstrumentationOwners.register(malformed)
      try {
        assertFailsWith<NoSuchFieldException> {
          StrictStateSnapshotter().snapshot(bad, listOf(0, 1), listOf(app, bad))
        }
      } finally {
        CoroutineInstrumentationOwners.unregister(malformed)
      }
    } finally {
      context.shutDown()
    }
  }
}
