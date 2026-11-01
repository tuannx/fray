package trycatch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.pastalab.fray.instrumentation.base.RestrictedCoroutineAppVerifier

/** Negative controls for the experimental primitive-only admission prototype. */
class RestrictedCoroutineAppVerifierTest {
  private val name = "example/RestrictedFixture"

  private fun fixture(
      className: String = name,
      methodAccess: Int = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
      descriptor: String = "()V",
      addField: Boolean = false,
      body: (org.objectweb.asm.MethodVisitor) -> Unit = { it.visitInsn(Opcodes.RETURN) },
  ): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(
        Opcodes.V11,
        Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
        className,
        null,
        "java/lang/Object",
        null,
    )
    if (addField) {
      writer
          .visitField(
              Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
              "value",
              "Ljava/lang/Object;",
              null,
              null,
          )
          .visitEnd()
    }
    val method = writer.visitMethod(methodAccess, "run", descriptor, null, null)
    if (methodAccess and (Opcodes.ACC_NATIVE or Opcodes.ACC_ABSTRACT) == 0) {
      method.visitCode()
      body(method)
      // The admission visitor must not rely on stack frames or prior class definition.
      method.visitMaxs(8, 8)
    }
    method.visitEnd()
    writer.visitEnd()
    return writer.toByteArray()
  }

  private fun reject(bytes: ByteArray, expectedName: String = name) {
    assertFailsWith<IllegalArgumentException> {
      RestrictedCoroutineAppVerifier.verify(expectedName, bytes)
    }
  }

  @Test
  fun acceptsOnlyThePrimitiveMathFixture() {
    val bytes =
        fixture(descriptor = "(I)I") { method ->
          method.visitVarInsn(Opcodes.ILOAD, 0)
          method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "abs", "(I)I", false)
          method.visitInsn(Opcodes.IRETURN)
        }
    RestrictedCoroutineAppVerifier.verify(name, bytes)
    val loader =
        object : ClassLoader(javaClass.classLoader) {
          fun define(): Class<*> =
              defineClass(
                  this@RestrictedCoroutineAppVerifierTest.name.replace('/', '.'),
                  bytes,
                  0,
                  bytes.size,
              )
        }
    val method = loader.define().getMethod("run", Int::class.javaPrimitiveType)
    assertEquals(7, method.invoke(null, -7))
  }

  @Test
  fun rejectsUnknownOrHostCallsEvenWithAnOtherwisePrimitiveSignature() {
    for (owner in listOf("example/host/Bridge", "java/lang/Integer", "example/RestrictedFixture")) {
      val bytes =
          fixture(descriptor = "(I)I") { method ->
            method.visitVarInsn(Opcodes.ILOAD, 0)
            method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "abs", "(I)I", false)
            method.visitInsn(Opcodes.IRETURN)
          }
      reject(bytes)
    }
    reject(
        fixture { method ->
          method.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              "example/host/Bridge",
              "pendingCallback",
              "()Ljava/lang/Runnable;",
              false,
          )
          method.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              "example/host/Bridge",
              "queue",
              "(Ljava/lang/Runnable;)V",
              false,
          )
          method.visitInsn(Opcodes.RETURN)
        }
    )
  }

  @Test
  fun rejectsHostPropertiesThreadsExecutorsReflectionLoadersUnsafeAndFfm() {
    val calls =
        listOf(
            Triple("java/lang/System", "getProperties", "()Ljava/util/Properties;"),
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
    for ((owner, methodName, descriptor) in calls) {
      reject(
          fixture { method ->
            method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, methodName, descriptor, false)
            method.visitInsn(Opcodes.POP)
            method.visitInsn(Opcodes.RETURN)
          }
      )
    }
    reject(
        fixture { method ->
          method.visitLdcInsn("java.lang.System")
          method.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              "java/lang/Class",
              "forName",
              "(Ljava/lang/String;)Ljava/lang/Class;",
              false,
          )
          method.visitInsn(Opcodes.POP)
          method.visitInsn(Opcodes.RETURN)
        }
    )
  }

  @Test
  fun rejectsRealDefaultDispatcherReferenceUntilSupported() {
    reject(
        fixture { method ->
          method.visitFieldInsn(
              Opcodes.GETSTATIC,
              "kotlinx/coroutines/Dispatchers",
              "INSTANCE",
              "Lkotlinx/coroutines/Dispatchers;",
          )
          method.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              "kotlinx/coroutines/Dispatchers",
              "getDefault",
              "()Lkotlinx/coroutines/CoroutineDispatcher;",
              false,
          )
          method.visitInsn(Opcodes.POP)
          method.visitInsn(Opcodes.RETURN)
        }
    )
  }

  @Test
  fun rejectsNativeFieldsReferencesAndArrays() {
    reject(fixture(methodAccess = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_NATIVE))
    reject(fixture(addField = true))
    reject(fixture(descriptor = "(Ljava/lang/Object;)V"))
    reject(fixture(descriptor = "([I)V"))
    reject(
        fixture { method ->
          method.visitInsn(Opcodes.ICONST_1)
          method.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT)
          method.visitInsn(Opcodes.POP)
          method.visitInsn(Opcodes.RETURN)
        }
    )
    reject(
        fixture { method ->
          method.visitFieldInsn(
              Opcodes.GETSTATIC,
              "java/lang/System",
              "out",
              "Ljava/io/PrintStream;",
          )
          method.visitInsn(Opcodes.POP)
          method.visitInsn(Opcodes.RETURN)
        }
    )
  }

  @Test
  fun rejectsDynamicBootstrapClassLiteralsAndMethodHandles() {
    val bootstrap =
        Handle(
            Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/StringConcatFactory",
            "makeConcatWithConstants",
            "(Ljava/lang/invoke/MethodHandles\u0024Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
            false,
        )
    reject(
        fixture { method ->
          method.visitInvokeDynamicInsn(
              "makeConcatWithConstants",
              "()Ljava/lang/String;",
              bootstrap,
              "value",
          )
          method.visitInsn(Opcodes.POP)
          method.visitInsn(Opcodes.RETURN)
        }
    )
    reject(
        fixture { method ->
          method.visitLdcInsn(Type.getType("Ljava/lang/String;"))
          method.visitInsn(Opcodes.POP)
          method.visitInsn(Opcodes.RETURN)
        }
    )
    reject(
        fixture { method ->
          method.visitLdcInsn(
              Handle(Opcodes.H_INVOKESTATIC, "java/lang/Math", "abs", "(I)I", false)
          )
          method.visitInsn(Opcodes.POP)
          method.visitInsn(Opcodes.RETURN)
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
        fixture { method ->
          method.visitLdcInsn(ConstantDynamic("value", "Ljava/lang/String;", constantBootstrap))
          method.visitInsn(Opcodes.POP)
          method.visitInsn(Opcodes.RETURN)
        }
    )
  }

  @Test
  fun rejectsExceptionHandlersAndNonStaticMethods() {
    reject(fixture(methodAccess = Opcodes.ACC_PUBLIC))
    reject(
        fixture { method ->
          val start = Label()
          val end = Label()
          val handler = Label()
          method.visitTryCatchBlock(start, end, handler, "java/lang/Throwable")
          method.visitLabel(start)
          method.visitInsn(Opcodes.RETURN)
          method.visitLabel(end)
          method.visitLabel(handler)
          method.visitInsn(Opcodes.POP)
          method.visitInsn(Opcodes.RETURN)
        }
    )
  }

  @Test
  fun rejectsMalformedMismatchedAndReservedClasses() {
    reject(byteArrayOf(0, 1, 2, 3))
    reject(fixture(), expectedName = "example/Other")
    for (reserved in
        listOf(
            "java/lang/Shadow",
            "kotlinx/coroutines/Shadow",
            "org/pastalab/fray/runtime/Shadow",
        )) {
      reject(fixture(className = reserved), expectedName = reserved)
    }
  }
}
