package trycatch

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.pastalab.fray.instrumentation.base.CooperativeClosedGraph
import org.pastalab.fray.instrumentation.base.PinnedCooperativeClass

/** Closed-declaration checks only; none of these cases establish runtime ownership. */
class CooperativeClosedGraphTest {
  private val root = "example/app/Fixture"
  private val helper = "example/app/Helper"

  @Test
  fun checksRuntimeClassIdentityAndRejectsMalformedInput() {
    assertEquals(root, CooperativeClosedGraph.checkedRuntimeClassName(clazz(root)))
    assertFailsWith<IllegalArgumentException> {
      CooperativeClosedGraph.checkedRuntimeClassName(byteArrayOf())
    }
    assertFailsWith<IllegalArgumentException> {
      CooperativeClosedGraph.checkedRuntimeClassName(byteArrayOf(1, 2, 3, 4))
    }
  }

  private fun clazz(
      name: String,
      access: Int = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
      body: (MethodVisitor) -> Unit = { it.visitInsn(Opcodes.RETURN) },
      addHelper: Boolean = false,
      addField: Boolean = false,
  ): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(
        Opcodes.V11,
        Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
        name,
        null,
        "java/lang/Object",
        null,
    )
    if (addField)
        writer
            .visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "I", null, null)
            .visitEnd()
    val method = writer.visitMethod(access, "run", "()V", null, null)
    method.visitCode()
    body(method)
    method.visitMaxs(8, 3)
    method.visitEnd()
    if (addHelper) {
      val second =
          writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "call", "()V", null, null)
      second.visitCode()
      second.visitInsn(Opcodes.RETURN)
      second.visitMaxs(0, 0)
      second.visitEnd()
    }
    writer.visitEnd()
    return writer.toByteArray()
  }

  private fun pin(name: String, bytes: ByteArray): PinnedCooperativeClass {
    val hash =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
          "%02x".format(it.toInt() and 0xff)
        }
    return PinnedCooperativeClass(name, hash, bytes)
  }

  @Test
  fun acceptsExplicitAppDeclarationAndDefaultDispatcherSignature() {
    val rootBytes =
        clazz(
            root,
            body = { m ->
              m.visitMethodInsn(Opcodes.INVOKESTATIC, helper, "call", "()V", false)
              m.visitMethodInsn(
                  Opcodes.INVOKESTATIC,
                  "kotlinx/coroutines/Dispatchers",
                  "getDefault",
                  "()Lkotlinx/coroutines/CoroutineDispatcher;",
                  false,
              )
              m.visitInsn(Opcodes.POP)
              m.visitInsn(Opcodes.RETURN)
            },
        )
    val original = rootBytes.copyOf()
    val graph =
        CooperativeClosedGraph.verify(
            listOf(pin(root, rootBytes), pin(helper, clazz(helper, addHelper = true))),
            root,
            "run",
        )
    assertEquals(setOf(root, helper), graph.classNames())
    rootBytes[0] = 0
    val returned = graph.classBytes(root)
    assertContentEquals(original, returned)
    returned[0] = 0
    assertContentEquals(original, graph.classBytes(root))
  }

  @Test
  fun rejectsInheritedObjectWaitAndNotifyThroughAppOwner() {
    for (method in listOf("wait", "notify", "notifyAll")) {
      val rootBytes =
          clazz(
              root,
              body = { m ->
                m.visitInsn(Opcodes.ACONST_NULL)
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, root, method, "()V", false)
                m.visitInsn(Opcodes.RETURN)
              },
          )
      // The older syntactic filter admits the symbolic app owner; closure must reject resolution
      // to Object's undeclared inherited monitor method.
      assertFailsWith<IllegalArgumentException> {
        CooperativeClosedGraph.verify(listOf(pin(root, rootBytes)), root, "run")
      }
    }
  }

  @Test
  fun rejectsMissingAppMethodsFieldsAndWrongInvocationKind() {
    for (body in
        listOf<(MethodVisitor) -> Unit>(
            { m ->
              m.visitMethodInsn(Opcodes.INVOKESTATIC, helper, "missing", "()V", false)
              m.visitInsn(Opcodes.RETURN)
            },
            { m ->
              m.visitInsn(Opcodes.ACONST_NULL)
              m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, helper, "call", "()V", false)
              m.visitInsn(Opcodes.RETURN)
            },
            { m ->
              m.visitFieldInsn(Opcodes.GETSTATIC, helper, "missing", "I")
              m.visitInsn(Opcodes.POP)
              m.visitInsn(Opcodes.RETURN)
            },
            { m ->
              m.visitInsn(Opcodes.ACONST_NULL)
              m.visitFieldInsn(Opcodes.GETFIELD, helper, "value", "I")
              m.visitInsn(Opcodes.POP)
              m.visitInsn(Opcodes.RETURN)
            },
        )) {
      assertFailsWith<IllegalArgumentException> {
        CooperativeClosedGraph.verify(
            listOf(
                pin(root, clazz(root, body = body)),
                pin(helper, clazz(helper, addHelper = true, addField = true)),
            ),
            root,
            "run",
        )
      }
    }
  }

  @Test
  fun rejectsDuplicateOrChangedPinsAndInvalidEntrypoints() {
    val bytes = clazz(root)
    val good = pin(root, bytes)
    assertFailsWith<IllegalArgumentException> {
      CooperativeClosedGraph.verify(listOf(good, good), root, "run")
    }
    assertFailsWith<IllegalArgumentException> {
      CooperativeClosedGraph.verify(listOf(good.copy(sha256 = "0".repeat(64))), root, "run")
    }
    assertFailsWith<IllegalArgumentException> {
      CooperativeClosedGraph.verify(listOf(good), root, "missing")
    }
    assertFailsWith<IllegalArgumentException> {
      CooperativeClosedGraph.verify(
          listOf(pin(root, clazz(root, access = Opcodes.ACC_PUBLIC))),
          root,
          "run",
      )
    }
    assertFailsWith<IllegalArgumentException> {
      CooperativeClosedGraph.verify(listOf(good), root, "<init>")
    }
  }

  @Test
  fun rejectsParentDelegatedNamespacesEvenWhenDeclaredAsApp() {
    for (prefix in
        listOf(
            "org/junit/",
            "org/jetbrains/",
            "com/intellij/rt",
            "worker/org/gradle/",
            "com/github/ajalt",
            "org/mockito",
            "net/bytebuddy",
            "org/pastalab/frayHidden/",
        )) {
      val name = prefix + "Fixture"
      assertFailsWith<IllegalArgumentException> {
        CooperativeClosedGraph.verify(listOf(pin(name, clazz(name))), name, "run")
      }
    }
  }
}
