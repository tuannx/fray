package trycatch

import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.pastalab.fray.instrumentation.base.visitors.CoroutineSchedulerInstrumenter
import org.pastalab.fray.runtime.CoroutineEpochGate
import org.pastalab.fray.runtime.CoroutineInstrumentationOwners
import org.pastalab.fray.runtime.CoroutineJobTracker
import org.pastalab.fray.runtime.CoroutineTaskTracker

class CoroutineSchedulerInstrumenterTest {
  private val scheduler = "kotlinx/coroutines/scheduling/CoroutineScheduler"
  private val task = "kotlinx/coroutines/scheduling/Task"
  private val tracker = "org/pastalab/fray/runtime/CoroutineTaskTracker"

  private fun schedulerFixture(includeRun: Boolean): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, scheduler, null, "java/lang/Object", null)
    val init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
    init.visitCode()
    init.visitVarInsn(Opcodes.ALOAD, 0)
    init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
    init.visitInsn(Opcodes.RETURN)
    init.visitMaxs(1, 1)
    init.visitEnd()
    val dispatch =
        writer.visitMethod(Opcodes.ACC_PUBLIC, "dispatch", "(Ljava/lang/Runnable;ZZ)V", null, null)
    dispatch.visitCode()
    dispatch.visitInsn(Opcodes.RETURN)
    dispatch.visitMaxs(0, 4)
    dispatch.visitEnd()
    val create =
        writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "createTask",
            "(Ljava/lang/Runnable;Z)L$task;",
            null,
            null,
        )
    create.visitCode()
    create.visitInsn(Opcodes.ACONST_NULL)
    create.visitInsn(Opcodes.ARETURN)
    create.visitMaxs(1, 3)
    create.visitEnd()
    if (includeRun) {
      val run = writer.visitMethod(Opcodes.ACC_PUBLIC, "runSafely", "(L$task;)V", null, null)
      run.visitCode()
      run.visitVarInsn(Opcodes.ALOAD, 1)
      run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, task, "run", "()V", false)
      run.visitInsn(Opcodes.RETURN)
      run.visitMaxs(1, 2)
      run.visitEnd()
    }
    writer.visitEnd()
    return writer.toByteArray()
  }

  private fun transform(bytes: ByteArray): ByteArray {
    val reader = ClassReader(bytes)
    val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
    reader.accept(CoroutineSchedulerInstrumenter(writer), ClassReader.EXPAND_FRAMES)
    return writer.toByteArray()
  }

  private fun calls(bytes: ByteArray): Map<String, List<String>> {
    val calls = mutableMapOf<String, MutableList<String>>()
    ClassReader(bytes)
        .accept(
            object : ClassVisitor(Opcodes.ASM9) {
              override fun visitMethod(
                  access: Int,
                  name: String,
                  descriptor: String,
                  signature: String?,
                  exceptions: Array<out String>?,
              ): MethodVisitor {
                val methodCalls = calls.getOrPut(name) { mutableListOf() }
                return object : MethodVisitor(Opcodes.ASM9) {
                  override fun visitMethodInsn(
                      opcode: Int,
                      owner: String,
                      name: String,
                      descriptor: String,
                      isInterface: Boolean,
                  ) {
                    methodCalls.add("$owner#$name$descriptor")
                  }
                }
              }
            },
            0,
        )
    return calls
  }

  @Test
  fun dispatchAndRunAreCountedAndTaskThrowableIsObserved() {
    val methods = calls(transform(schedulerFixture(true)))
    val dispatch = methods.getValue("dispatch")
    assertTrue(dispatch.contains("$tracker#dispatchBegin(Ljava/lang/Object;)V"))
    assertTrue(dispatch.contains("$tracker#dispatchEnd(Ljava/lang/Object;Z)V"))
    val create = methods.getValue("createTask")
    assertTrue(create.contains("$tracker#registerTask(Ljava/lang/Object;Ljava/lang/Object;)V"))
    val run = methods.getValue("runSafely")
    assertTrue(run.contains("$tracker#runBegin(Ljava/lang/Object;Ljava/lang/Object;)V"))
    assertTrue(run.contains("$tracker#runEnd(Ljava/lang/Object;Ljava/lang/Object;Z)V"))
    assertTrue(run.contains("$tracker#runTask(Ljava/lang/Object;Ljava/lang/Runnable;)V"))
    assertFalse(run.contains("$task#run()V"))
  }

  @Test
  fun missingSchedulerAbiFailsClosed() {
    assertFailsWith<IllegalStateException> { transform(schedulerFixture(false)) }
  }

  private class OwnedLoader(parent: ClassLoader) : ClassLoader(parent) {
    fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
  }

  private fun simpleClass(name: String, runnable: Boolean): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(
        Opcodes.V11,
        Opcodes.ACC_PUBLIC,
        name,
        null,
        "java/lang/Object",
        if (runnable) arrayOf("java/lang/Runnable") else null,
    )
    val init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
    init.visitCode()
    init.visitVarInsn(Opcodes.ALOAD, 0)
    init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
    init.visitInsn(Opcodes.RETURN)
    init.visitMaxs(1, 1)
    init.visitEnd()
    if (runnable) {
      val run = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null)
      run.visitCode()
      run.visitInsn(Opcodes.RETURN)
      run.visitMaxs(0, 1)
      run.visitEnd()
    }
    writer.visitEnd()
    return writer.toByteArray()
  }

  private fun withOwnedTracker(block: (OwnedLoader, Class<*>, Class<*>, Any) -> Unit) {
    val loader = OwnedLoader(javaClass.classLoader)
    val root =
        loader.define(
            "kotlinx.coroutines.BlockingCoroutine",
            simpleClass("kotlinx/coroutines/BlockingCoroutine", false),
        )
    val taskType = loader.define(task.replace('/', '.'), simpleClass(task, true))
    val schedulerType =
        loader.define(scheduler.replace('/', '.'), transform(schedulerFixture(true)))
    val schedulerInstance = schedulerType.getDeclaredConstructor().newInstance()
    CoroutineInstrumentationOwners.register(loader)
    try {
      CoroutineEpochGate.begin(loader)
      CoroutineJobTracker.begin(loader, root, Thread.currentThread())
      CoroutineTaskTracker.begin(loader)
      block(loader, schedulerType, taskType, schedulerInstance)
    } finally {
      // Synthetic failure cases taint the process by design; discard only this test fixture.
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
  fun failedInstrumentedRunBeginDoesNotEndOuterRun() =
      withOwnedTracker { loader, schedulerType, taskType, schedulerInstance ->
        val instance = taskType.getDeclaredConstructor().newInstance() as Runnable
        CoroutineTaskTracker.dispatchBegin(schedulerInstance)
        CoroutineTaskTracker.registerTask(schedulerInstance, instance)
        CoroutineTaskTracker.dispatchEnd(schedulerInstance, false)
        CoroutineTaskTracker.runBegin(schedulerInstance, instance)
        val failure =
            assertFailsWith<InvocationTargetException> {
              schedulerType.getMethod("runSafely", taskType).invoke(schedulerInstance, instance)
            }
        assertTrue(failure.cause is IllegalStateException)
        val snapshot = CoroutineTaskTracker.diagnosticSnapshot(loader)
        assertTrue(snapshot.contains("running=1"), snapshot)
        assertTrue(snapshot.contains("violations=1"), snapshot)
        CoroutineTaskTracker.runEnd(schedulerInstance, instance, false)
      }

  @Test
  fun failedInstrumentedDispatchBeginDoesNotEndOuterPublisher() =
      withOwnedTracker { loader, schedulerType, _, schedulerInstance ->
        val other = schedulerType.getDeclaredConstructor().newInstance()
        CoroutineTaskTracker.dispatchBegin(schedulerInstance)
        val failure =
            assertFailsWith<InvocationTargetException> {
              schedulerType
                  .getMethod(
                      "dispatch",
                      Runnable::class.java,
                      Boolean::class.javaPrimitiveType,
                      Boolean::class.javaPrimitiveType,
                  )
                  .invoke(other, Runnable {}, false, false)
            }
        assertTrue(failure.cause is IllegalStateException)
        val snapshot = CoroutineTaskTracker.diagnosticSnapshot(loader)
        assertTrue(snapshot.contains("publishers=1"), snapshot)
        assertTrue(snapshot.contains("dispatching=1"), snapshot)
        assertTrue(snapshot.contains("violations=1"), snapshot)
      }

  @Test
  fun failedInstrumentedEnqueueBeginDoesNotEndOuterPublisher() =
      withOwnedTracker { loader, _, _, _ ->
        for (name in
            listOf(
                "kotlinx/coroutines/CancellableContinuation",
                "kotlinx/coroutines/DisposableHandle",
                "kotlinx/coroutines/EventLoopImplBase\$DelayedTask",
            )) {
          loader.define(name.replace('/', '.'), simpleClass(name, false))
        }
        val baseName = "kotlinx/coroutines/EventLoopImplBase"
        val signatures =
            listOf(
                "scheduleResumeAfterDelay" to "(JLkotlinx/coroutines/CancellableContinuation;)V",
                "scheduleInvokeOnTimeout" to
                    "(JLjava/lang/Runnable;)Lkotlinx/coroutines/DisposableHandle;",
                "schedule" to "(JLkotlinx/coroutines/EventLoopImplBase\$DelayedTask;)V",
                "enqueue" to "(Ljava/lang/Runnable;)V",
                "processNextEvent" to "()J",
            )
        val base =
            loader.define(
                baseName.replace('/', '.'),
                transform(eventLoopFixture(baseName, signatures)),
            )
        val blockingName = "kotlinx/coroutines/BlockingEventLoop"
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, blockingName, null, baseName, null)
        val init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        init.visitCode()
        init.visitVarInsn(Opcodes.ALOAD, 0)
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, baseName, "<init>", "()V", false)
        init.visitInsn(Opcodes.RETURN)
        init.visitMaxs(1, 1)
        init.visitEnd()
        writer.visitEnd()
        val loop =
            loader
                .define(blockingName.replace('/', '.'), writer.toByteArray())
                .getDeclaredConstructor()
                .newInstance()
        CoroutineTaskTracker.registerEventLoop(loop, Thread.currentThread())
        val task = Runnable {}
        CoroutineTaskTracker.enqueueBegin(loop, task)
        val failure =
            assertFailsWith<InvocationTargetException> {
              base.getMethod("enqueue", Runnable::class.java).invoke(loop, task)
            }
        assertTrue(failure.cause is IllegalStateException)
        val snapshot = CoroutineTaskTracker.diagnosticSnapshot(loader)
        assertTrue(snapshot.contains("eventTasks=1"), snapshot)
        assertTrue(snapshot.contains("publishers=1"), snapshot)
        assertTrue(snapshot.contains("dispatching=1"), snapshot)
        assertTrue(snapshot.contains("violations=1"), snapshot)
      }

  private fun eventLoopFixture(owner: String, signatures: List<Pair<String, String>>): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null)
    if (signatures.none { it.first == "<init>" }) {
      val init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
      init.visitCode()
      init.visitVarInsn(Opcodes.ALOAD, 0)
      init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
      init.visitInsn(Opcodes.RETURN)
      init.visitMaxs(1, 1)
      init.visitEnd()
    }
    signatures.forEach { (name, descriptor) ->
      val method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null)
      method.visitCode()
      if (name == "<init>") {
        method.visitVarInsn(Opcodes.ALOAD, 0)
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
      }
      if (name == "processNextEvent") {
        method.visitInsn(Opcodes.ACONST_NULL)
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true)
      }
      when (Type.getReturnType(descriptor).sort) {
        Type.VOID -> method.visitInsn(Opcodes.RETURN)
        Type.BOOLEAN -> {
          method.visitInsn(Opcodes.ICONST_0)
          method.visitInsn(Opcodes.IRETURN)
        }
        Type.LONG -> {
          method.visitInsn(Opcodes.LCONST_0)
          method.visitInsn(Opcodes.LRETURN)
        }
        else -> {
          method.visitInsn(Opcodes.ACONST_NULL)
          method.visitInsn(Opcodes.ARETURN)
        }
      }
      method.visitMaxs(2, 1 + Type.getArgumentTypes(descriptor).sumOf { it.size })
      method.visitEnd()
    }
    writer.visitEnd()
    return writer.toByteArray()
  }

  @Test
  fun pinnedTimerAndFallbackEntrypointsAreRejected() {
    val eventMethods =
        listOf(
            "scheduleResumeAfterDelay" to "(JLkotlinx/coroutines/CancellableContinuation;)V",
            "scheduleInvokeOnTimeout" to
                "(JLjava/lang/Runnable;)Lkotlinx/coroutines/DisposableHandle;",
            "schedule" to "(JLkotlinx/coroutines/EventLoopImplBase\$DelayedTask;)V",
        )
    val executorMethods =
        listOf(
            "enqueue" to "(Ljava/lang/Runnable;)V",
            "reschedule" to "(JLkotlinx/coroutines/EventLoopImplBase\$DelayedTask;)V",
            "invokeOnTimeout" to
                "(JLjava/lang/Runnable;Lkotlin/coroutines/CoroutineContext;)Lkotlinx/coroutines/DisposableHandle;",
        )
    val loopMethods =
        eventMethods + listOf("enqueue" to "(Ljava/lang/Runnable;)V", "processNextEvent" to "()J")
    for ((owner, methods) in
        listOf(
            "kotlinx/coroutines/EventLoopImplBase" to loopMethods,
            "kotlinx/coroutines/DefaultExecutor" to executorMethods,
        )) {
      val transformed = calls(transform(eventLoopFixture(owner, methods)))
      for ((name, _) in methods) {
        if (name != "enqueue" || owner.endsWith("DefaultExecutor")) {
          if (name != "processNextEvent") {
            assertTrue(
                transformed
                    .getValue(name)
                    .contains("$tracker#rejectUnsupported(Ljava/lang/Object;Ljava/lang/String;)V")
            )
          }
        }
      }
      if (owner.endsWith("EventLoopImplBase")) {
        assertTrue(
            transformed
                .getValue("enqueue")
                .contains("$tracker#enqueueBegin(Ljava/lang/Object;Ljava/lang/Object;)V")
        )
        assertTrue(
            transformed
                .getValue("enqueue")
                .contains("$tracker#enqueueEnd(Ljava/lang/Object;Ljava/lang/Object;Z)V")
        )
        assertTrue(
            transformed
                .getValue("processNextEvent")
                .contains("$tracker#pollEventLoop(Ljava/lang/Object;)V")
        )
        assertTrue(
            transformed
                .getValue("processNextEvent")
                .contains("$tracker#runEventLoopTask(Ljava/lang/Object;Ljava/lang/Runnable;)V")
        )
      }
      assertFailsWith<IllegalStateException> {
        transform(eventLoopFixture(owner, methods.dropLast(1)))
      }
    }
    val baseMethods =
        listOf(
            "dispatchUnconfined" to "(Lkotlinx/coroutines/DispatchedTask;)V",
            "processUnconfinedEvent" to "()Z",
        )
    val base = calls(transform(eventLoopFixture("kotlinx/coroutines/EventLoop", baseMethods)))
    assertTrue(
        base
            .getValue("dispatchUnconfined")
            .contains("$tracker#rejectUnsupported(Ljava/lang/Object;Ljava/lang/String;)V")
    )
    assertTrue(
        base
            .getValue("processUnconfinedEvent")
            .contains("$tracker#unconfinedProcessed(Ljava/lang/Object;Z)V")
    )
    val blocking =
        calls(
            transform(
                eventLoopFixture(
                    "kotlinx/coroutines/BlockingEventLoop",
                    listOf("<init>" to "(Ljava/lang/Thread;)V"),
                )
            )
        )
    assertTrue(
        blocking
            .getValue("<init>")
            .contains("$tracker#registerEventLoop(Ljava/lang/Object;Ljava/lang/Thread;)V")
    )
  }
}
