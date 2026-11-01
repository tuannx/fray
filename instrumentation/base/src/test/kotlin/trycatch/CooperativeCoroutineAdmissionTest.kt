package trycatch

import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.pastalab.fray.instrumentation.base.CooperativeCoroutineAdmission

/** Syntactic admission tests only. A positive result is not an ownership or sandbox proof. */
class CooperativeCoroutineAdmissionTest {
  private val name = "example/app/CooperativeFixture"
  private val helper = "example/app/KnownHelper"
  private val applicationClasses = setOf(name, helper)

  private fun fixture(
      className: String = name,
      superName: String = "java/lang/Object",
      interfaces: Array<String>? = null,
      access: Int = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
      methodAccess: Int = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
      body: (MethodVisitor) -> Unit = { it.visitInsn(Opcodes.RETURN) },
  ): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V11, access, className, null, superName, interfaces)
    val method = writer.visitMethod(methodAccess, "run", "()V", null, null)
    if (methodAccess and (Opcodes.ACC_NATIVE or Opcodes.ACC_ABSTRACT) == 0) {
      method.visitCode()
      body(method)
      method.visitMaxs(16, 8)
    }
    method.visitEnd()
    writer.visitEnd()
    return writer.toByteArray()
  }

  private fun accept(bytes: ByteArray, classes: Set<String> = applicationClasses) =
      CooperativeCoroutineAdmission.verify(name, bytes, classes)

  private fun reject(
      bytes: ByteArray,
      classes: Set<String> = applicationClasses,
      expectedName: String = name,
  ) {
    assertFailsWith<IllegalArgumentException> {
      CooperativeCoroutineAdmission.verify(expectedName, bytes, classes)
    }
  }

  private fun initializerFixture(
      initializerName: String,
      descriptor: String,
      body: (MethodVisitor) -> Unit,
  ): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(
        Opcodes.V11,
        Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
        name,
        null,
        "java/lang/Object",
        null,
    )
    writer.visitField(0, "value", "I", null, null).visitEnd()
    writer.visitField(Opcodes.ACC_STATIC, "shared", "I", null, null).visitEnd()
    writer.visitField(Opcodes.ACC_STATIC, "INSTANCE", "L$name;", null, null).visitEnd()
    val flags = if (initializerName == "<clinit>") Opcodes.ACC_STATIC else Opcodes.ACC_PUBLIC
    val method = writer.visitMethod(flags, initializerName, descriptor, null, null)
    method.visitCode()
    body(method)
    method.visitMaxs(8, 4)
    method.visitEnd()
    writer.visitEnd()
    return writer.toByteArray()
  }

  @Test
  fun constructorCanCopyCapturedArgumentOnlyToNewInstance() {
    accept(
        initializerFixture("<init>", "(I)V") { m ->
          m.visitVarInsn(Opcodes.ALOAD, 0)
          m.visitVarInsn(Opcodes.ILOAD, 1)
          m.visitFieldInsn(Opcodes.PUTFIELD, name, "value", "I")
          m.visitVarInsn(Opcodes.ALOAD, 0)
          m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
          m.visitInsn(Opcodes.RETURN)
        }
    )
  }

  @Test
  fun initializersRejectSharedFieldsAndEscapingCalls() {
    reject(
        initializerFixture("<init>", "(L$name;)V") { m ->
          m.visitVarInsn(Opcodes.ALOAD, 0)
          m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
          m.visitVarInsn(Opcodes.ALOAD, 1)
          m.visitInsn(Opcodes.ICONST_1)
          m.visitFieldInsn(Opcodes.PUTFIELD, name, "value", "I")
          m.visitInsn(Opcodes.RETURN)
        }
    )
    reject(
        initializerFixture("<init>", "()V") { m ->
          m.visitVarInsn(Opcodes.ALOAD, 0)
          m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
          m.visitVarInsn(Opcodes.ALOAD, 0)
          m.visitMethodInsn(Opcodes.INVOKESTATIC, helper, "publish", "(L$name;)V", false)
          m.visitInsn(Opcodes.RETURN)
        }
    )
    reject(
        initializerFixture("<clinit>", "()V") { m ->
          m.visitFieldInsn(Opcodes.GETSTATIC, name, "shared", "I")
          m.visitInsn(Opcodes.ICONST_1)
          m.visitInsn(Opcodes.IADD)
          m.visitFieldInsn(Opcodes.PUTSTATIC, name, "shared", "I")
          m.visitInsn(Opcodes.RETURN)
        }
    )
  }

  @Test
  fun classInitializerCanPublishOwnFreshSingleton() {
    accept(
        initializerFixture("<clinit>", "()V") { m ->
          m.visitTypeInsn(Opcodes.NEW, name)
          m.visitInsn(Opcodes.DUP)
          m.visitMethodInsn(Opcodes.INVOKESPECIAL, name, "<init>", "()V", false)
          m.visitFieldInsn(Opcodes.PUTSTATIC, name, "INSTANCE", "L$name;")
          m.visitInsn(Opcodes.RETURN)
        }
    )
  }

  @Test
  fun acceptsEnumeratedRunBlockingAndRealDefaultSignatures() {
    accept(
        fixture { m ->
          m.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              "kotlinx/coroutines/Dispatchers",
              "getDefault",
              "()Lkotlinx/coroutines/CoroutineDispatcher;",
              false,
          )
          m.visitInsn(Opcodes.POP)
          m.visitInsn(Opcodes.ACONST_NULL) // Default context selected by the default-argument mask.
          m.visitInsn(
              Opcodes.ACONST_NULL
          ) // Placeholder lambda: this synthetic class is not executed.
          m.visitInsn(Opcodes.ICONST_1)
          m.visitInsn(Opcodes.ACONST_NULL)
          m.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              "kotlinx/coroutines/BuildersKt",
              "runBlocking\u0024default",
              "(Lkotlin/coroutines/CoroutineContext;Lkotlin/jvm/functions/Function2;ILjava/lang/Object;)Ljava/lang/Object;",
              false,
          )
          m.visitInsn(Opcodes.POP)
          m.visitInsn(Opcodes.RETURN)
        }
    )
  }

  @Test
  fun acceptsEnumeratedMutexAndAtomicOperations() {
    accept(
        fixture { m ->
          m.visitInsn(Opcodes.ICONST_0)
          m.visitInsn(Opcodes.ICONST_1)
          m.visitInsn(Opcodes.ACONST_NULL)
          m.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              "kotlinx/coroutines/sync/MutexKt",
              "Mutex\u0024default",
              "(ZILjava/lang/Object;)Lkotlinx/coroutines/sync/Mutex;",
              false,
          )
          m.visitInsn(Opcodes.DUP)
          m.visitInsn(Opcodes.ACONST_NULL)
          m.visitInsn(Opcodes.ACONST_NULL)
          m.visitMethodInsn(
              Opcodes.INVOKEINTERFACE,
              "kotlinx/coroutines/sync/Mutex",
              "lock",
              "(Ljava/lang/Object;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
              true,
          )
          m.visitInsn(Opcodes.POP)
          m.visitInsn(Opcodes.ACONST_NULL)
          m.visitMethodInsn(
              Opcodes.INVOKEINTERFACE,
              "kotlinx/coroutines/sync/Mutex",
              "unlock",
              "(Ljava/lang/Object;)V",
              true,
          )
          m.visitTypeInsn(Opcodes.NEW, "java/util/concurrent/atomic/AtomicInteger")
          m.visitInsn(Opcodes.DUP)
          m.visitMethodInsn(
              Opcodes.INVOKESPECIAL,
              "java/util/concurrent/atomic/AtomicInteger",
              "<init>",
              "()V",
              false,
          )
          m.visitInsn(Opcodes.DUP)
          m.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              "java/util/concurrent/atomic/AtomicInteger",
              "get",
              "()I",
              false,
          )
          m.visitInsn(Opcodes.POP)
          m.visitInsn(Opcodes.ICONST_1)
          m.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              "java/util/concurrent/atomic/AtomicInteger",
              "set",
              "(I)V",
              false,
          )
          m.visitInsn(Opcodes.RETURN)
        }
    )
  }

  @Test
  fun acceptsEnumeratedLaunchAsyncJoinAndCancellationSignatures() {
    val scope = "Lkotlinx/coroutines/CoroutineScope;"
    val context = "Lkotlin/coroutines/CoroutineContext;"
    val start = "Lkotlinx/coroutines/CoroutineStart;"
    val function = "Lkotlin/jvm/functions/Function2;"
    for ((method, result) in
        listOf(
            "launch\u0024default" to "Lkotlinx/coroutines/Job;",
            "async\u0024default" to "Lkotlinx/coroutines/Deferred;",
        )) {
      accept(
          fixture { m ->
            repeat(4) { m.visitInsn(Opcodes.ACONST_NULL) }
            m.visitInsn(Opcodes.ICONST_0)
            m.visitInsn(Opcodes.ACONST_NULL)
            m.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "kotlinx/coroutines/BuildersKt",
                method,
                "($scope$context$start$function${'I'}Ljava/lang/Object;)$result",
                false,
            )
            m.visitInsn(Opcodes.POP)
            m.visitInsn(Opcodes.ACONST_NULL)
            m.visitInsn(Opcodes.ACONST_NULL)
            m.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "kotlinx/coroutines/Job",
                "join",
                "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
                true,
            )
            m.visitInsn(Opcodes.POP)
            m.visitInsn(Opcodes.ACONST_NULL)
            m.visitInsn(Opcodes.ACONST_NULL)
            m.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "kotlinx/coroutines/JobKt",
                "cancelAndJoin",
                "(Lkotlinx/coroutines/Job;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
                false,
            )
            m.visitInsn(Opcodes.POP)
            m.visitInsn(Opcodes.RETURN)
          }
      )
    }
  }

  @Test
  fun acceptsOnlyExplicitlyListedOwnedApplicationClasses() {
    val bytes = fixture { m ->
      m.visitMethodInsn(Opcodes.INVOKESTATIC, helper, "work", "()V", false)
      m.visitInsn(Opcodes.RETURN)
    }
    // The caller must independently verify the helper's bytes and its loader ownership.
    accept(bytes)
    reject(bytes, classes = setOf(name))
  }

  @Test
  fun acceptsPinnedCompilerGeneratedSuspendLambdaShapeSyntactically() {
    val lambda = "example/app/SuspendWork"
    val writer = ClassWriter(0)
    writer.visit(
        Opcodes.V11,
        Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
        lambda,
        null,
        "kotlin/coroutines/jvm/internal/SuspendLambda",
        arrayOf("kotlin/jvm/functions/Function2"),
    )
    val method = writer.visitMethod(0, "<init>", "(Lkotlin/coroutines/Continuation;)V", null, null)
    method.visitCode()
    method.visitVarInsn(Opcodes.ALOAD, 0)
    method.visitInsn(Opcodes.ICONST_2)
    method.visitVarInsn(Opcodes.ALOAD, 1)
    method.visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        "kotlin/coroutines/jvm/internal/SuspendLambda",
        "<init>",
        "(ILkotlin/coroutines/Continuation;)V",
        false,
    )
    method.visitInsn(Opcodes.RETURN)
    method.visitMaxs(3, 2)
    method.visitEnd()
    writer.visitEnd()
    // Shape acceptance does not establish safe captured values or dynamic dispatch.
    CooperativeCoroutineAdmission.verify(lambda, writer.toByteArray(), applicationClasses + lambda)
  }

  @Test
  fun acceptsEnumeratedChannelAndSuspensionSignatures() {
    accept(
        fixture { m ->
          m.visitInsn(Opcodes.ICONST_0)
          m.visitInsn(Opcodes.ACONST_NULL)
          m.visitInsn(Opcodes.ACONST_NULL)
          m.visitInsn(Opcodes.ICONST_0)
          m.visitInsn(Opcodes.ACONST_NULL)
          m.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              "kotlinx/coroutines/channels/ChannelKt",
              "Channel\u0024default",
              "(ILkotlinx/coroutines/channels/BufferOverflow;Lkotlin/jvm/functions/Function1;ILjava/lang/Object;)Lkotlinx/coroutines/channels/Channel;",
              false,
          )
          m.visitInsn(Opcodes.DUP)
          m.visitInsn(Opcodes.ACONST_NULL)
          m.visitInsn(Opcodes.ACONST_NULL)
          m.visitMethodInsn(
              Opcodes.INVOKEINTERFACE,
              "kotlinx/coroutines/channels/Channel",
              "send",
              "(Ljava/lang/Object;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
              true,
          )
          m.visitInsn(Opcodes.POP)
          m.visitInsn(Opcodes.ACONST_NULL)
          m.visitMethodInsn(
              Opcodes.INVOKEINTERFACE,
              "kotlinx/coroutines/channels/Channel",
              "receive",
              "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
              true,
          )
          m.visitInsn(Opcodes.POP)
          m.visitInsn(Opcodes.ACONST_NULL)
          m.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              "kotlinx/coroutines/DelayKt",
              "awaitCancellation",
              "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
              false,
          )
          m.visitInsn(Opcodes.POP)
          m.visitInsn(Opcodes.RETURN)
        }
    )
  }

  @Test
  fun acceptsOnlyTheUnlockAndRethrowHandlerShape() {
    fun withHandler(rethrow: Boolean): ByteArray = fixture { m ->
      val start = Label()
      val end = Label()
      val handler = Label()
      m.visitTryCatchBlock(start, end, handler, null)
      m.visitLabel(start)
      m.visitInsn(Opcodes.NOP)
      m.visitLabel(end)
      m.visitInsn(Opcodes.RETURN)
      m.visitLabel(handler)
      m.visitVarInsn(Opcodes.ASTORE, 2)
      if (rethrow) {
        m.visitVarInsn(Opcodes.ALOAD, 0)
        m.visitVarInsn(Opcodes.ALOAD, 1)
        m.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            "kotlinx/coroutines/sync/Mutex",
            "unlock",
            "(Ljava/lang/Object;)V",
            true,
        )
        m.visitVarInsn(Opcodes.ALOAD, 2)
        m.visitInsn(Opcodes.ATHROW)
      } else {
        m.visitInsn(Opcodes.RETURN)
      }
    }
    // Syntactic recognition only: receiver ownership still requires an independent runtime guard.
    accept(withHandler(true))
    reject(withHandler(false))
  }

  @Test
  fun rejectsDirectHostBridgesAndMutableSystemProperties() {
    reject(
        fixture { m ->
          m.visitInsn(Opcodes.ACONST_NULL)
          m.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              "org/pastalab/fray/poc/HostBridge",
              "queue",
              "(Ljava/lang/Runnable;)V",
              false,
          )
          m.visitInsn(Opcodes.RETURN)
        }
    )
    reject(
        fixture { m ->
          m.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              "java/lang/System",
              "getProperties",
              "()Ljava/util/Properties;",
              false,
          )
          m.visitInsn(Opcodes.ACONST_NULL)
          m.visitInsn(Opcodes.ACONST_NULL)
          m.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              "java/util/Properties",
              "put",
              "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
              false,
          )
          m.visitInsn(Opcodes.POP)
          m.visitInsn(Opcodes.RETURN)
        }
    )
    reject(
        fixture { m ->
          m.visitFieldInsn(
              Opcodes.GETSTATIC,
              "kotlinx/coroutines/GlobalScope",
              "INSTANCE",
              "Lkotlinx/coroutines/GlobalScope;",
          )
          m.visitInsn(Opcodes.POP)
          m.visitInsn(Opcodes.RETURN)
        }
    )
  }

  @Test
  fun rejectsThreadsExecutorsReflectionLoadersUnsafeAndFfm() {
    val calls =
        listOf(
            Triple("java/lang/Thread", "currentThread", "()Ljava/lang/Thread;"),
            Triple(
                "java/util/concurrent/Executors",
                "newSingleThreadExecutor",
                "()Ljava/util/concurrent/ExecutorService;",
            ),
            Triple("java/lang/ClassLoader", "getSystemClassLoader", "()Ljava/lang/ClassLoader;"),
            Triple(
                "java/lang/invoke/MethodHandles",
                "lookup",
                "()Ljava/lang/invoke/MethodHandles\u0024Lookup;",
            ),
            Triple("sun/misc/Unsafe", "getUnsafe", "()Lsun/misc/Unsafe;"),
            Triple("java/lang/foreign/Arena", "ofShared", "()Ljava/lang/foreign/Arena;"),
        )
    for ((owner, method, descriptor) in calls) {
      reject(
          fixture { m ->
            m.visitMethodInsn(Opcodes.INVOKESTATIC, owner, method, descriptor, false)
            m.visitInsn(Opcodes.POP)
            m.visitInsn(Opcodes.RETURN)
          }
      )
    }
    reject(
        fixture { m ->
          m.visitLdcInsn("java.lang.System")
          m.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              "java/lang/Class",
              "forName",
              "(Ljava/lang/String;)Ljava/lang/Class;",
              false,
          )
          m.visitInsn(Opcodes.POP)
          m.visitInsn(Opcodes.RETURN)
        }
    )
  }

  @Test
  fun rejectsCustomJobScopeDispatcherAndDescendantLoaders() {
    for (superName in
        listOf(
            "kotlinx/coroutines/JobSupport",
            "kotlinx/coroutines/CoroutineDispatcher",
            "java/lang/ClassLoader",
        )) {
      reject(fixture(superName = superName))
    }
    for (iface in
        listOf(
            "kotlinx/coroutines/Job",
            "kotlinx/coroutines/CoroutineScope",
            "java/util/concurrent/Executor",
        )) {
      reject(fixture(interfaces = arrayOf(iface)))
    }
    reject(fixture(methodAccess = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_NATIVE))
  }

  @Test
  fun rejectsUnknownOwnersAndSameOwnerWrongNameDescriptorOrOpcode() {
    reject(
        fixture { m ->
          m.visitMethodInsn(Opcodes.INVOKESTATIC, "example/app/Undeclared", "run", "()V", false)
          m.visitInsn(Opcodes.RETURN)
        }
    )
    reject(
        fixture { m ->
          m.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              "kotlinx/coroutines/Dispatchers",
              "getIO",
              "()Lkotlinx/coroutines/CoroutineDispatcher;",
              false,
          )
          m.visitInsn(Opcodes.POP)
          m.visitInsn(Opcodes.RETURN)
        }
    )
    reject(
        fixture { m ->
          m.visitInsn(Opcodes.ICONST_0)
          m.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              "kotlinx/coroutines/Dispatchers",
              "getDefault",
              "(I)Lkotlinx/coroutines/CoroutineDispatcher;",
              false,
          )
          m.visitInsn(Opcodes.POP)
          m.visitInsn(Opcodes.RETURN)
        }
    )
    reject(
        fixture { m ->
          m.visitInsn(Opcodes.ACONST_NULL)
          m.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              "kotlinx/coroutines/Dispatchers",
              "getDefault",
              "()Lkotlinx/coroutines/CoroutineDispatcher;",
              false,
          )
          m.visitInsn(Opcodes.POP)
          m.visitInsn(Opcodes.RETURN)
        }
    )
  }

  @Test
  fun rejectsIndyCondyMethodHandlesAndClassLiterals() {
    val bootstrap =
        Handle(
            Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/StringConcatFactory",
            "makeConcatWithConstants",
            "(Ljava/lang/invoke/MethodHandles\u0024Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
            false,
        )
    reject(
        fixture { m ->
          m.visitInvokeDynamicInsn(
              "makeConcatWithConstants",
              "()Ljava/lang/String;",
              bootstrap,
              "value",
          )
          m.visitInsn(Opcodes.POP)
          m.visitInsn(Opcodes.RETURN)
        }
    )
    reject(
        fixture { m ->
          m.visitLdcInsn(Type.getType("Ljava/lang/String;"))
          m.visitInsn(Opcodes.POP)
          m.visitInsn(Opcodes.RETURN)
        }
    )
    reject(
        fixture { m ->
          m.visitLdcInsn(Handle(Opcodes.H_INVOKESTATIC, "java/lang/Math", "abs", "(I)I", false))
          m.visitInsn(Opcodes.POP)
          m.visitInsn(Opcodes.RETURN)
        }
    )
    val constantBootstrap =
        Handle(
            Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/ConstantBootstraps",
            "nullConstant",
            "(Ljava/lang/invoke/MethodHandles\u0024Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;",
            false,
        )
    reject(
        fixture { m ->
          m.visitLdcInsn(ConstantDynamic("value", "Ljava/lang/String;", constantBootstrap))
          m.visitInsn(Opcodes.POP)
          m.visitInsn(Opcodes.RETURN)
        }
    )
  }

  @Test
  fun rejectsMismatchedClassesAndUntrustedManifestOwners() {
    reject(fixture(), expectedName = "example/app/Other")
    reject(
        fixture(className = "kotlinx/coroutines/Fake"),
        classes = setOf("kotlinx/coroutines/Fake"),
        expectedName = "kotlinx/coroutines/Fake",
    )
    reject(
        fixture { m ->
          m.visitMethodInsn(Opcodes.INVOKESTATIC, "kotlinx/coroutines/Fake", "call", "()V", false)
          m.visitInsn(Opcodes.RETURN)
        },
        classes = applicationClasses + "kotlinx/coroutines/Fake",
    )
    reject(byteArrayOf(0, 1, 2, 3))
  }
}
