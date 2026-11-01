package org.pastalab.fray.instrumentation.base.visitors

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.AdviceAdapter
import org.objectweb.asm.commons.Method

/** Pinned 1.10.1 scheduler hooks; only valid for an owned, instrumented coroutine loader. */
class CoroutineSchedulerInstrumenter(cv: ClassVisitor) : ClassVisitor(Opcodes.ASM9, cv) {
  private val tracker = Type.getObjectType("org/pastalab/fray/runtime/CoroutineTaskTracker")
  private var owner = ""
  private val found = mutableSetOf<String>()
  private var foundTaskInvocation = false
  private var foundEventInvocation = false

  override fun visit(
      version: Int,
      access: Int,
      name: String,
      signature: String?,
      superName: String?,
      interfaces: Array<out String?>?,
  ) {
    owner = name
    super.visit(version, access, name, signature, superName, interfaces)
  }

  override fun visitMethod(
      access: Int,
      name: String,
      descriptor: String,
      signature: String?,
      exceptions: Array<out String?>?,
  ): MethodVisitor {
    val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
    val key = "$name$descriptor"
    if (owner == BLOCKING_LOOP && key == BLOCKING_LOOP_CONSTRUCTOR) {
      found.add(key)
      return object : AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
        override fun onMethodExit(opcode: Int) {
          if (opcode == Opcodes.RETURN) {
            loadThis()
            loadArg(0)
            invokeStatic(
                tracker,
                Method("registerEventLoop", "(Ljava/lang/Object;Ljava/lang/Thread;)V"),
            )
          }
        }
      }
    }
    if (owner == EVENT_LOOP && key == ENQUEUE) {
      found.add(key)
      return counted(mv, access, name, descriptor, "enqueueBegin", "enqueueEnd", true)
    }
    if (owner == EVENT_LOOP && key == PROCESS_NEXT) {
      found.add(key)
      val rewriteEventCall =
          object : MethodVisitor(Opcodes.ASM9, mv) {
            override fun visitMethodInsn(
                opcode: Int,
                target: String,
                method: String,
                desc: String,
                isInterface: Boolean,
            ) {
              if (
                  opcode == Opcodes.INVOKEINTERFACE &&
                      target == "java/lang/Runnable" &&
                      method == "run" &&
                      desc == "()V"
              ) {
                foundEventInvocation = true
                super.visitVarInsn(Opcodes.ALOAD, 0)
                super.visitInsn(Opcodes.SWAP)
                super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    tracker.internalName,
                    "runEventLoopTask",
                    "(Ljava/lang/Object;Ljava/lang/Runnable;)V",
                    false,
                )
              } else {
                super.visitMethodInsn(opcode, target, method, desc, isInterface)
              }
            }
          }
      return object : AdviceAdapter(Opcodes.ASM9, rewriteEventCall, access, name, descriptor) {
        override fun onMethodEnter() {
          loadThis()
          invokeStatic(tracker, Method("pollEventLoop", "(Ljava/lang/Object;)V"))
        }
      }
    }
    if (owner == EVENT_LOOP_BASE && key == PROCESS_UNCONFINED) {
      found.add(key)
      return object : AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
        override fun onMethodExit(opcode: Int) {
          if (opcode == Opcodes.IRETURN) {
            dup()
            loadThis()
            swap()
            invokeStatic(tracker, Method("unconfinedProcessed", "(Ljava/lang/Object;Z)V"))
          }
        }
      }
    }
    if (owner == SCHEDULER && key == DISPATCH) {
      found.add(key)
      return counted(mv, access, name, descriptor, "dispatchBegin", "dispatchEnd", false)
    }
    if (owner == SCHEDULER && key == CREATE_TASK) {
      found.add(key)
      return object : AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
        override fun onMethodExit(opcode: Int) {
          if (opcode == Opcodes.ARETURN) {
            dup()
            loadThis()
            swap()
            invokeStatic(tracker, Method("registerTask", "(Ljava/lang/Object;Ljava/lang/Object;)V"))
          }
        }
      }
    }
    if (owner == SCHEDULER && key == RUN_SAFELY) {
      found.add(key)
      val rewriteTaskCall =
          object : MethodVisitor(Opcodes.ASM9, mv) {
            override fun visitMethodInsn(
                opcode: Int,
                target: String,
                method: String,
                desc: String,
                isInterface: Boolean,
            ) {
              if (
                  opcode == Opcodes.INVOKEVIRTUAL &&
                      target == TASK &&
                      method == "run" &&
                      desc == "()V"
              ) {
                foundTaskInvocation = true
                // Original stack: task. Place this scheduler before the task for
                // runTask(Object,Runnable).
                super.visitVarInsn(Opcodes.ALOAD, 0)
                super.visitInsn(Opcodes.SWAP)
                super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    tracker.internalName,
                    "runTask",
                    "(Ljava/lang/Object;Ljava/lang/Runnable;)V",
                    false,
                )
              } else {
                super.visitMethodInsn(opcode, target, method, desc, isInterface)
              }
            }
          }
      return counted(rewriteTaskCall, access, name, descriptor, "runBegin", "runEnd", true)
    }
    val unsupported =
        when (owner) {
          EVENT_LOOP -> EVENT_LOOP_UNSUPPORTED
          EVENT_LOOP_BASE -> EVENT_LOOP_BASE_UNSUPPORTED
          DEFAULT_EXECUTOR -> DEFAULT_EXECUTOR_UNSUPPORTED
          else -> emptySet()
        }
    if (key in unsupported) {
      found.add(key)
      return object : AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
        override fun onMethodEnter() {
          loadThis()
          push("$owner#$name")
          invokeStatic(
              tracker,
              Method("rejectUnsupported", "(Ljava/lang/Object;Ljava/lang/String;)V"),
          )
        }
      }
    }
    return mv
  }

  private fun counted(
      mv: MethodVisitor,
      access: Int,
      name: String,
      descriptor: String,
      begin: String,
      end: String,
      withTask: Boolean,
  ): MethodVisitor {
    val exit =
        MethodExitVisitor(mv, null, access, name, descriptor, true, withTask, true, owner) {
            v,
            failed ->
          v.push(failed)
          val desc =
              if (withTask) "(Ljava/lang/Object;Ljava/lang/Object;Z)V" else "(Ljava/lang/Object;Z)V"
          v.invokeStatic(tracker, Method(end, desc))
        }
    return object : AdviceAdapter(Opcodes.ASM9, exit, access, name, descriptor) {
      override fun onMethodEnter() {
        loadThis()
        if (withTask) loadArg(0)
        val desc =
            if (withTask) "(Ljava/lang/Object;Ljava/lang/Object;)V" else "(Ljava/lang/Object;)V"
        invokeStatic(tracker, Method(begin, desc))
        // The finalizer must not consume an outer frame if this begin hook itself throws.
        exit.excludeEntryFromFinalBlock()
      }
    }
  }

  override fun visitEnd() {
    val expected =
        when (owner) {
          SCHEDULER -> setOf(DISPATCH, CREATE_TASK, RUN_SAFELY)
          EVENT_LOOP -> EVENT_LOOP_UNSUPPORTED + setOf(ENQUEUE, PROCESS_NEXT)
          EVENT_LOOP_BASE -> EVENT_LOOP_BASE_UNSUPPORTED + PROCESS_UNCONFINED
          BLOCKING_LOOP -> setOf(BLOCKING_LOOP_CONSTRUCTOR)
          DEFAULT_EXECUTOR -> DEFAULT_EXECUTOR_UNSUPPORTED
          else -> emptySet()
        }
    if (
        !found.containsAll(expected) ||
            (owner == SCHEDULER && !foundTaskInvocation) ||
            (owner == EVENT_LOOP && !foundEventInvocation)
    ) {
      throw IllegalStateException("Unsupported kotlinx.coroutines scheduler/event-loop ABI: $owner")
    }
    super.visitEnd()
  }

  private companion object {
    const val SCHEDULER = "kotlinx/coroutines/scheduling/CoroutineScheduler"
    const val TASK = "kotlinx/coroutines/scheduling/Task"
    const val EVENT_LOOP = "kotlinx/coroutines/EventLoopImplBase"
    const val EVENT_LOOP_BASE = "kotlinx/coroutines/EventLoop"
    const val BLOCKING_LOOP = "kotlinx/coroutines/BlockingEventLoop"
    const val DEFAULT_EXECUTOR = "kotlinx/coroutines/DefaultExecutor"
    const val DISPATCH = "dispatch(Ljava/lang/Runnable;ZZ)V"
    const val CREATE_TASK = "createTask(Ljava/lang/Runnable;Z)Lkotlinx/coroutines/scheduling/Task;"
    const val RUN_SAFELY = "runSafely(Lkotlinx/coroutines/scheduling/Task;)V"
    const val ENQUEUE = "enqueue(Ljava/lang/Runnable;)V"
    const val PROCESS_NEXT = "processNextEvent()J"
    const val PROCESS_UNCONFINED = "processUnconfinedEvent()Z"
    const val BLOCKING_LOOP_CONSTRUCTOR = "<init>(Ljava/lang/Thread;)V"
    val EVENT_LOOP_BASE_UNSUPPORTED =
        setOf(
            "dispatchUnconfined(Lkotlinx/coroutines/DispatchedTask;)V",
        )
    val EVENT_LOOP_UNSUPPORTED =
        setOf(
            "scheduleResumeAfterDelay(JLkotlinx/coroutines/CancellableContinuation;)V",
            "scheduleInvokeOnTimeout(JLjava/lang/Runnable;)Lkotlinx/coroutines/DisposableHandle;",
            "schedule(JLkotlinx/coroutines/EventLoopImplBase\$DelayedTask;)V",
        )
    val DEFAULT_EXECUTOR_UNSUPPORTED =
        setOf(
            "enqueue(Ljava/lang/Runnable;)V",
            "reschedule(JLkotlinx/coroutines/EventLoopImplBase\$DelayedTask;)V",
            "invokeOnTimeout(JLjava/lang/Runnable;Lkotlin/coroutines/CoroutineContext;)Lkotlinx/coroutines/DisposableHandle;",
        )
  }
}
