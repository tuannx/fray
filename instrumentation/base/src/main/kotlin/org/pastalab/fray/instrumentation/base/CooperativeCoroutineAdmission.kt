package org.pastalab.fray.instrumentation.base

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode

/**
 * LAB-ONLY syntactic filter for a cooperative coroutine subset. It is not a JVM sandbox, ownership
 * proof, complete class-graph verifier or admission decision for production use.
 *
 * The caller must independently authenticate every class source and its dependency graph, prohibit
 * fallback/redefinition, and prove receiver, callback, context and spilled-field provenance. A name
 * or descriptor matching this filter does not prove such provenance. Rejection always means
 * unsupported; acceptance means only that this syntactic pass completed.
 */
object CooperativeCoroutineAdmission {
  private const val MAX_CLASS_BYTES = 1_000_000
  private const val MAX_METHODS = 100
  private const val MAX_GRAPH_CLASSES = 1000
  private const val MAX_FIELDS = 256
  private const val MAX_FRAME_SLOTS = 256
  private const val MAX_INSTRUCTIONS = 50_000
  private const val MAX_HANDLERS = 128
  private val forbiddenAppPrefixes =
      listOf(
          "java/",
          "javax/",
          "jdk/",
          "sun/",
          "com/sun/",
          "kotlin/",
          "kotlinx/",
          "org/junit/",
          "org/jetbrains/",
          "com/intellij/rt",
          "worker/org/gradle/",
          "com/github/ajalt",
          "org/mockito",
          "net/bytebuddy",
          "org/pastalab/fray/core/",
          "org/pastalab/fray/runtime/",
          "org/pastalab/fray/instrumentation/",
      )

  private data class Call(
      val opcode: Int,
      val owner: String,
      val name: String,
      val desc: String,
      val itf: Boolean = false,
  )

  private fun static(owner: String, name: String, desc: String) =
      Call(Opcodes.INVOKESTATIC, owner, name, desc)

  private fun virtual(owner: String, name: String, desc: String) =
      Call(Opcodes.INVOKEVIRTUAL, owner, name, desc)

  private fun special(owner: String, name: String, desc: String) =
      Call(Opcodes.INVOKESPECIAL, owner, name, desc)

  private fun iface(owner: String, name: String, desc: String) =
      Call(Opcodes.INVOKEINTERFACE, owner, name, desc, true)

  private val allowedTypes =
      setOf(
          "java/lang/Object",
          "java/lang/String",
          "java/lang/Integer",
          "java/lang/IllegalStateException",
          "java/lang/Iterable",
          "java/util/Collection",
          "java/util/Iterator",
          "java/util/List",
          "java/util/ArrayList",
          "java/util/concurrent/atomic/AtomicInteger",
          "kotlin/Unit",
          "kotlin/KotlinNothingValueException",
          "kotlin/collections/IntIterator",
          "kotlin/ranges/IntRange",
          "kotlin/coroutines/Continuation",
          "kotlin/coroutines/CoroutineContext",
          "kotlin/coroutines/jvm/internal/SuspendLambda",
          "kotlin/jvm/functions/Function2",
          "kotlinx/coroutines/CoroutineScope",
          "kotlinx/coroutines/CoroutineDispatcher",
          "kotlinx/coroutines/CoroutineStart",
          "kotlinx/coroutines/Job",
          "kotlinx/coroutines/Deferred",
          "kotlinx/coroutines/channels/Channel",
          "kotlinx/coroutines/channels/BufferOverflow",
          "kotlinx/coroutines/sync/Mutex",
          "kotlin/jvm/functions/Function1",
      )

  private val allowedCalls =
      setOf(
          special("java/lang/Object", "<init>", "()V"),
          special("java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V"),
          special("kotlin/KotlinNothingValueException", "<init>", "()V"),
          special("java/util/ArrayList", "<init>", "(I)V"),
          special("java/util/concurrent/atomic/AtomicInteger", "<init>", "()V"),
          special("kotlin/ranges/IntRange", "<init>", "(II)V"),
          special(
              "kotlin/coroutines/jvm/internal/SuspendLambda",
              "<init>",
              "(ILkotlin/coroutines/Continuation;)V",
          ),
          virtual("java/util/concurrent/atomic/AtomicInteger", "get", "()I"),
          virtual("java/util/concurrent/atomic/AtomicInteger", "set", "(I)V"),
          virtual("java/util/ArrayList", "add", "(Ljava/lang/Object;)Z"),
          virtual("kotlin/collections/IntIterator", "nextInt", "()I"),
          static("java/lang/Integer", "parseInt", "(Ljava/lang/String;)I"),
          static("java/lang/String", "valueOf", "(I)Ljava/lang/String;"),
          static("kotlin/ResultKt", "throwOnFailure", "(Ljava/lang/Object;)V"),
          static(
              "kotlin/coroutines/intrinsics/IntrinsicsKt",
              "getCOROUTINE_SUSPENDED",
              "()Ljava/lang/Object;",
          ),
          static("kotlin/coroutines/jvm/internal/Boxing", "boxInt", "(I)Ljava/lang/Integer;"),
          static(
              "kotlin/coroutines/jvm/internal/SpillingKt",
              "nullOutSpilledVariable",
              "(Ljava/lang/Object;)Ljava/lang/Object;",
          ),
          static(
              "kotlin/collections/CollectionsKt",
              "collectionSizeOrDefault",
              "(Ljava/lang/Iterable;I)I",
          ),
          static("kotlin/collections/CollectionsKt", "sumOfInt", "(Ljava/lang/Iterable;)I"),
          static(
              "kotlinx/coroutines/BuildersKt",
              "runBlocking\$default",
              "(Lkotlin/coroutines/CoroutineContext;Lkotlin/jvm/functions/Function2;ILjava/lang/Object;)Ljava/lang/Object;",
          ),
          static(
              "kotlinx/coroutines/BuildersKt",
              "async\$default",
              "(Lkotlinx/coroutines/CoroutineScope;Lkotlin/coroutines/CoroutineContext;Lkotlinx/coroutines/CoroutineStart;Lkotlin/jvm/functions/Function2;ILjava/lang/Object;)Lkotlinx/coroutines/Deferred;",
          ),
          static(
              "kotlinx/coroutines/BuildersKt",
              "launch\$default",
              "(Lkotlinx/coroutines/CoroutineScope;Lkotlin/coroutines/CoroutineContext;Lkotlinx/coroutines/CoroutineStart;Lkotlin/jvm/functions/Function2;ILjava/lang/Object;)Lkotlinx/coroutines/Job;",
          ),
          static(
              "kotlinx/coroutines/Dispatchers",
              "getDefault",
              "()Lkotlinx/coroutines/CoroutineDispatcher;",
          ),
          static(
              "kotlinx/coroutines/AwaitKt",
              "awaitAll",
              "(Ljava/util/Collection;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
          ),
          static(
              "kotlinx/coroutines/sync/MutexKt",
              "Mutex\$default",
              "(ZILjava/lang/Object;)Lkotlinx/coroutines/sync/Mutex;",
          ),
          static(
              "kotlinx/coroutines/channels/ChannelKt",
              "Channel\$default",
              "(ILkotlinx/coroutines/channels/BufferOverflow;Lkotlin/jvm/functions/Function1;ILjava/lang/Object;)Lkotlinx/coroutines/channels/Channel;",
          ),
          static(
              "kotlinx/coroutines/JobKt",
              "cancelAndJoin",
              "(Lkotlinx/coroutines/Job;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
          ),
          static(
              "kotlinx/coroutines/DelayKt",
              "awaitCancellation",
              "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
          ),
          iface("java/lang/Iterable", "iterator", "()Ljava/util/Iterator;"),
          iface("java/util/Iterator", "hasNext", "()Z"),
          iface("java/util/Iterator", "next", "()Ljava/lang/Object;"),
          iface("java/util/Collection", "add", "(Ljava/lang/Object;)Z"),
          iface(
              "kotlinx/coroutines/Job",
              "join",
              "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
          ),
          iface(
              "kotlinx/coroutines/sync/Mutex",
              "lock",
              "(Ljava/lang/Object;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
          ),
          iface("kotlinx/coroutines/sync/Mutex", "unlock", "(Ljava/lang/Object;)V"),
          iface(
              "kotlinx/coroutines/channels/Channel",
              "send",
              "(Ljava/lang/Object;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
          ),
          iface(
              "kotlinx/coroutines/channels/Channel",
              "receive",
              "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
          ),
      )

  private val constructibleTypes =
      setOf(
          "java/lang/IllegalStateException",
          "java/util/ArrayList",
          "java/util/concurrent/atomic/AtomicInteger",
          "kotlin/KotlinNothingValueException",
          "kotlin/ranges/IntRange",
      )

  @JvmStatic
  fun verify(expectedName: String, bytes: ByteArray, applicationClasses: Set<String>) {
    if (
        applicationClasses.isEmpty() ||
            applicationClasses.size > MAX_GRAPH_CLASSES ||
            expectedName !in applicationClasses ||
            applicationClasses.any {
              it.isEmpty() ||
                  it.contains('.') ||
                  forbiddenAppPrefixes.any(it::startsWith) ||
                  (it.startsWith("org/pastalab/fray") && !it.startsWith("org/pastalab/fray/test/"))
            } ||
            bytes.size > MAX_CLASS_BYTES
    )
        reject("invalid application graph")
    try {
      ClassReader(bytes)
          .accept(
              Filter(expectedName, applicationClasses),
              ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
          )
    } catch (e: IllegalArgumentException) {
      throw e
    } catch (e: RuntimeException) {
      throw IllegalArgumentException("malformed class", e)
    }
  }

  private fun reject(reason: String): Nothing = throw IllegalArgumentException(reason)

  private fun allowedType(type: Type, apps: Set<String>, voidAllowed: Boolean): Boolean =
      when (type.sort) {
        Type.VOID -> voidAllowed
        in Type.BOOLEAN..Type.DOUBLE -> true
        Type.OBJECT -> type.internalName in allowedTypes || type.internalName in apps
        else -> false // Arrays, method types and unknown sorts require a separate model.
      }

  private fun allowedMethodDescriptor(desc: String, apps: Set<String>): Boolean {
    val method = Type.getMethodType(desc)
    return allowedType(method.returnType, apps, true) &&
        method.argumentTypes.all { allowedType(it, apps, false) }
  }

  private class Filter(val expected: String, val apps: Set<String>) : ClassVisitor(Opcodes.ASM9) {
    private var count = 0
    private var fieldCount = 0
    private var superClass = ""

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) {
      val major = version and 0xffff
      if (
          version ushr 16 != 0 ||
              major !in 55..69 ||
              name != expected ||
              access and Opcodes.ACC_FINAL == 0 ||
              access and
                  (Opcodes.ACC_INTERFACE or
                      Opcodes.ACC_ANNOTATION or
                      Opcodes.ACC_ENUM or
                      Opcodes.ACC_MODULE or
                      Opcodes.ACC_ABSTRACT or
                      Opcodes.ACC_RECORD) != 0 ||
              superName !in
                  setOf("java/lang/Object", "kotlin/coroutines/jvm/internal/SuspendLambda") ||
              !(interfaces ?: emptyArray()).all { it == "kotlin/jvm/functions/Function2" }
      )
          reject("unsupported class hierarchy")
      superClass = superName!!
    }

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?,
    ): org.objectweb.asm.FieldVisitor? {
      if (
          ++fieldCount > MAX_FIELDS ||
              !allowedType(Type.getType(descriptor), apps, false) ||
              value != null &&
                  value !is Int &&
                  value !is Long &&
                  value !is Float &&
                  value !is Double &&
                  value !is String
      )
          reject("unsupported field")
      return null
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): org.objectweb.asm.MethodVisitor {
      if (
          ++count > MAX_METHODS ||
              access and (Opcodes.ACC_NATIVE or Opcodes.ACC_ABSTRACT or Opcodes.ACC_SYNCHRONIZED) !=
                  0 ||
              !allowedMethodDescriptor(descriptor, apps) ||
              (exceptions != null && exceptions.any { it !in allowedTypes && it !in apps })
      )
          reject("unsupported method declaration")
      return object : MethodNode(Opcodes.ASM9, access, name, descriptor, signature, exceptions) {
        override fun visitEnd() {
          super.visitEnd()
          verifyMethod(this, apps)
          if (name == "<init>" || name == "<clinit>") {
            verifyInitializer(this, expected, superClass)
          }
        }
      }
    }

    override fun visitEnd() {
      if (count == 0) reject("empty class")
    }
  }

  /**
   * Fray does not instrument fields in initializers. Admit only compiler-shaped initialization:
   * captured arguments copied directly into this new object, a trusted direct super constructor, or
   * a Kotlin object singleton created and published by its own class initializer. No calls,
   * shared-field reads/writes or alternate control flow may run before publication.
   */
  private fun verifyInitializer(method: MethodNode, owner: String, superClass: String) {
    val code = method.instructions.toArray().filter { it.opcode >= 0 }
    if (method.tryCatchBlocks.isNotEmpty()) reject("initializer handlers are unsupported")
    if (method.name == "<clinit>") {
      if (code.size == 1 && code[0].opcode == Opcodes.RETURN) return
      val newObject = code.getOrNull(0) as? TypeInsnNode
      val constructor = code.getOrNull(2) as? MethodInsnNode
      val publication = code.getOrNull(3) as? FieldInsnNode
      if (
          code.size != 5 ||
              newObject?.opcode != Opcodes.NEW ||
              newObject.desc != owner ||
              code[1].opcode != Opcodes.DUP ||
              constructor?.opcode != Opcodes.INVOKESPECIAL ||
              constructor.owner != owner ||
              constructor.name != "<init>" ||
              constructor.desc != "()V" ||
              constructor.itf ||
              publication?.opcode != Opcodes.PUTSTATIC ||
              publication.owner != owner ||
              publication.desc != "L$owner;" ||
              code[4].opcode != Opcodes.RETURN
      )
          reject("unsupported class initializer")
      return
    }

    var index = 0
    var superCalled = false
    while (index < code.size) {
      if (code[index].opcode == Opcodes.RETURN && index == code.lastIndex && superCalled) return
      val self = code.getOrNull(index) as? VarInsnNode
      if (self?.opcode != Opcodes.ALOAD || self.`var` != 0) reject("unsupported constructor")
      val next = code.getOrNull(index + 1)
      if (next is VarInsnNode && next.opcode in Opcodes.ILOAD..Opcodes.ALOAD && next.`var` > 0) {
        val field = code.getOrNull(index + 2) as? FieldInsnNode
        if (field?.opcode != Opcodes.PUTFIELD || field.owner != owner)
            reject("unsupported constructor field access")
        index += 3
      } else if (superClass == "java/lang/Object") {
        val call = next as? MethodInsnNode
        if (
            superCalled ||
                call?.opcode != Opcodes.INVOKESPECIAL ||
                call.owner != superClass ||
                call.name != "<init>" ||
                call.desc != "()V" ||
                call.itf
        )
            reject("unsupported constructor super call")
        superCalled = true
        index += 2
      } else {
        val argument = code.getOrNull(index + 2) as? VarInsnNode
        val call = code.getOrNull(index + 3) as? MethodInsnNode
        if (
            superCalled ||
                next?.opcode != Opcodes.ICONST_2 ||
                argument?.opcode != Opcodes.ALOAD ||
                argument.`var` <= 0 ||
                call?.opcode != Opcodes.INVOKESPECIAL ||
                call.owner != superClass ||
                call.name != "<init>" ||
                call.desc != "(ILkotlin/coroutines/Continuation;)V" ||
                call.itf
        )
            reject("unsupported constructor super call")
        superCalled = true
        index += 4
      }
    }
    reject("incomplete constructor")
  }

  private fun verifyMethod(method: MethodNode, apps: Set<String>) {
    if (
        method.maxLocals > MAX_FRAME_SLOTS ||
            method.maxStack > MAX_FRAME_SLOTS ||
            method.instructions.size() > MAX_INSTRUCTIONS ||
            method.tryCatchBlocks.size > MAX_HANDLERS
    ) {
      reject("method exceeds admission limits")
    }
    for (insn in method.instructions.toArray()) when (insn) {
      is MethodInsnNode -> {
        val appCall =
            insn.owner in apps &&
                insn.opcode in
                    setOf(Opcodes.INVOKESTATIC, Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL) &&
                !insn.itf &&
                allowedMethodDescriptor(insn.desc, apps)
        if (
            !appCall &&
                Call(insn.opcode, insn.owner, insn.name, insn.desc, insn.itf) !in allowedCalls
        )
            reject("unsupported call: ${insn.owner}.${insn.name}${insn.desc}")
      }
      is FieldInsnNode -> {
        val local =
            insn.opcode in Opcodes.GETSTATIC..Opcodes.PUTFIELD &&
                insn.owner in apps &&
                allowedType(Type.getType(insn.desc), apps, false)
        val unit =
            insn.opcode == Opcodes.GETSTATIC &&
                insn.owner == "kotlin/Unit" &&
                insn.name == "INSTANCE" &&
                insn.desc == "Lkotlin/Unit;"
        if (!local && !unit) reject("unsupported field reference")
      }
      is TypeInsnNode -> {
        val permitted =
            when (insn.opcode) {
              Opcodes.NEW -> insn.desc in apps || insn.desc in constructibleTypes
              Opcodes.CHECKCAST,
              Opcodes.INSTANCEOF -> insn.desc in apps || insn.desc in allowedTypes
              else -> false
            }
        if (!permitted) reject("unsupported type operation")
      }
      is LdcInsnNode ->
          if (
              insn.cst !is Int &&
                  insn.cst !is Long &&
                  insn.cst !is Float &&
                  insn.cst !is Double &&
                  insn.cst !is String
          )
              reject("unsupported constant")
      is InvokeDynamicInsnNode -> reject("invokedynamic is unsupported")
      is MultiANewArrayInsnNode -> reject("arrays are unsupported")
      is IntInsnNode ->
          if (insn.opcode != Opcodes.BIPUSH && insn.opcode != Opcodes.SIPUSH)
              reject("unsupported integer operation")
      is VarInsnNode ->
          if (
              insn.`var` !in 0 until MAX_FRAME_SLOTS ||
                  (insn.opcode !in Opcodes.ILOAD..Opcodes.ALOAD &&
                      insn.opcode !in Opcodes.ISTORE..Opcodes.ASTORE)
          )
              reject("unsupported local operation")
      is JumpInsnNode ->
          if (
              insn.opcode !in Opcodes.IFEQ..Opcodes.IF_ACMPNE &&
                  insn.opcode != Opcodes.GOTO &&
                  insn.opcode != Opcodes.IFNULL &&
                  insn.opcode != Opcodes.IFNONNULL
          )
              reject("unsupported branch")
      is InsnNode ->
          if (
              insn.opcode != Opcodes.NOP &&
                  insn.opcode != Opcodes.ACONST_NULL &&
                  insn.opcode !in Opcodes.ICONST_M1..Opcodes.DCONST_1 &&
                  insn.opcode !in Opcodes.POP..Opcodes.SWAP &&
                  insn.opcode !in Opcodes.IADD..Opcodes.LXOR &&
                  insn.opcode !in Opcodes.I2L..Opcodes.DCMPG &&
                  insn.opcode !in Opcodes.IRETURN..Opcodes.RETURN &&
                  insn.opcode != Opcodes.ATHROW
          )
              reject("unsupported opcode")
      is IincInsnNode -> if (insn.`var` !in 0 until MAX_FRAME_SLOTS) reject("unsupported increment")
      is TableSwitchInsnNode,
      is LookupSwitchInsnNode -> Unit
      is LabelNode,
      is LineNumberNode,
      is FrameNode -> Unit
      else -> reject("unsupported instruction node")
    }
    for (handler in method.tryCatchBlocks) {
      if (handler.type != null || !isUnlockRethrow(handler.handler))
          reject("unsupported exception handler")
    }
  }

  /** Accept only the Kotlin withLock finally shape: save throwable; unlock; rethrow same value. */
  private fun isUnlockRethrow(label: AbstractInsnNode): Boolean {
    fun nextReal(node: AbstractInsnNode?): AbstractInsnNode? {
      var cursor = node
      while (cursor != null && cursor.opcode < 0) cursor = cursor.next
      return cursor
    }
    val store = nextReal(label) as? VarInsnNode ?: return false
    if (store.opcode != Opcodes.ASTORE) return false
    val mutex = nextReal(store.next) as? VarInsnNode ?: return false
    val owner = nextReal(mutex.next) as? VarInsnNode ?: return false
    val call = nextReal(owner.next) as? MethodInsnNode ?: return false
    val thrown = nextReal(call.next) as? VarInsnNode ?: return false
    val end = nextReal(thrown.next) ?: return false
    return mutex.opcode == Opcodes.ALOAD &&
        owner.opcode == Opcodes.ALOAD &&
        Call(call.opcode, call.owner, call.name, call.desc, call.itf) ==
            iface("kotlinx/coroutines/sync/Mutex", "unlock", "(Ljava/lang/Object;)V") &&
        thrown.opcode == Opcodes.ALOAD &&
        thrown.`var` == store.`var` &&
        end.opcode == Opcodes.ATHROW
  }
}
