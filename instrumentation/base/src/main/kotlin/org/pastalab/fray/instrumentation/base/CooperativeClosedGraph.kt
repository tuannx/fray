package org.pastalab.fray.instrumentation.base

import java.security.MessageDigest
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode

/** One class in a caller-supplied manifest. The expected digest must come from a trusted source. */
data class PinnedCooperativeClass(val name: String, val sha256: String, val bytes: ByteArray)

/**
 * Frozen output of the lab's syntactic and declaration checks. It is not authorization to execute
 * the graph: the caller must verify its archive inventory and all trusted dependency bytes, enforce
 * no app-class parent fallback or redefinition, and establish cooperative receiver/callback
 * provenance. This deliberately does not claim to sandbox arbitrary JVM bytecode.
 */
class VerifiedCooperativeGraph
internal constructor(
    private val snapshots: Map<String, ByteArray>,
    val entryClass: String,
    val entryMethod: String,
) {
  fun classNames(): Set<String> = snapshots.keys.toSet()

  /** Always return a defensive copy, including for classes already verified by this instance. */
  fun classBytes(name: String): ByteArray =
      snapshots[name]?.copyOf() ?: throw IllegalArgumentException("class is not in verified graph")
}

/**
 * Lab-only check of a caller-supplied complete app-class inventory. This does not read a jar, so
 * the caller must separately reject duplicate/shadowed entries, unknown app classes, services,
 * resources and multi-release/class-path behavior in the actual archive inventory. Every returned
 * byte array is the same frozen content that was checked; it must be used directly for definition.
 */
object CooperativeClosedGraph {
  private const val MAX_CLASSES = 1000
  private const val MAX_CLASS_BYTES = 1_000_000
  private const val MAX_RUNTIME_CLASS_BYTES = 8 * 1024 * 1024
  private const val MAX_TOTAL_BYTES = 32_000_000L
  private val digestPattern = Regex("[0-9a-f]{64}")
  private val methodPattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

  /** Read a bounded class identity inside the agent artifact, where ASM is relocated at runtime. */
  @JvmStatic
  fun checkedRuntimeClassName(bytes: ByteArray): String {
    require(bytes.isNotEmpty() && bytes.size <= MAX_RUNTIME_CLASS_BYTES) {
      "invalid runtime class size"
    }
    return try {
      ClassReader(bytes).className
    } catch (error: RuntimeException) {
      throw IllegalArgumentException("malformed runtime class", error)
    }
  }

  @JvmStatic
  fun verify(
      classes: List<PinnedCooperativeClass>,
      entryClass: String,
      entryMethod: String,
  ): VerifiedCooperativeGraph {
    require(classes.isNotEmpty() && classes.size <= MAX_CLASSES) { "invalid class count" }
    require(methodPattern.matches(entryMethod)) { "invalid entry method" }
    val snapshots = linkedMapOf<String, ByteArray>()
    var total = 0L
    for (item in classes) {
      require(digestPattern.matches(item.sha256) && item.bytes.size <= MAX_CLASS_BYTES) {
        "invalid class pin or size"
      }
      total += item.bytes.size
      require(total <= MAX_TOTAL_BYTES) { "class graph exceeds byte limit" }
      val bytes = item.bytes.copyOf()
      require(sha256(bytes) == item.sha256) { "class digest mismatch" }
      require(snapshots.putIfAbsent(item.name, bytes) == null) { "duplicate app class" }
    }
    require(entryClass in snapshots) { "entry class is absent" }
    val names = snapshots.keys.toSet()
    val nodes = linkedMapOf<String, ClassNode>()
    for ((name, bytes) in snapshots) {
      CooperativeCoroutineAdmission.verify(name, bytes, names)
      val node = ClassNode()
      try {
        ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
      } catch (error: RuntimeException) {
        throw IllegalArgumentException("malformed app class", error)
      }
      require(node.name == name) { "class name mismatch" }
      require(node.nestHostClass == null || node.nestHostClass in names) { "foreign nest host" }
      require(node.nestMembers.orEmpty().all { it in names }) { "foreign nest member" }
      require(node.outerClass == null || node.outerClass in names) { "foreign outer class" }
      require(node.methods.map { it.name to it.desc }.distinct().size == node.methods.size) {
        "duplicate method declaration"
      }
      require(node.fields.map { it.name to it.desc }.distinct().size == node.fields.size) {
        "duplicate field declaration"
      }
      nodes[name] = node
    }
    val entry =
        nodes.getValue(entryClass).methods.singleOrNull {
          it.name == entryMethod && it.desc == "()V"
        }
    require(
        entry != null &&
            entry.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC) ==
                (Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC)
    ) {
      "entry must be a public static no-arg void method"
    }

    // An app symbolic owner may otherwise resolve an undeclared call to an inherited Object or
    // SuspendLambda method, bypassing the external-call whitelist (e.g. App.wait()). Require an
    // exact declaration in the named app owner and check static/instance opcode consistency.
    for (node in nodes.values) for (method in node.methods) for (insn in
        method.instructions.toArray()) {
      if (insn is MethodInsnNode && insn.owner in names) {
        val declared =
            nodes.getValue(insn.owner).methods.singleOrNull {
              it.name == insn.name && it.desc == insn.desc
            }
        require(declared != null && !insn.itf && insn.name != "<clinit>") {
          "undeclared app method"
        }
        val isStatic = declared.access and Opcodes.ACC_STATIC != 0
        require(
            (insn.opcode == Opcodes.INVOKESTATIC && isStatic) ||
                (insn.opcode in setOf(Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL) && !isStatic)
        ) {
          "app method invocation kind mismatch"
        }
      } else if (insn is FieldInsnNode && insn.owner in names) {
        val declared =
            nodes.getValue(insn.owner).fields.singleOrNull {
              it.name == insn.name && it.desc == insn.desc
            }
        require(declared != null) { "undeclared app field" }
        val isStatic = declared.access and Opcodes.ACC_STATIC != 0
        require(
            (insn.opcode in setOf(Opcodes.GETSTATIC, Opcodes.PUTSTATIC) && isStatic) ||
                (insn.opcode in setOf(Opcodes.GETFIELD, Opcodes.PUTFIELD) && !isStatic)
        ) {
          "app field access kind mismatch"
        }
      }
    }
    return VerifiedCooperativeGraph(snapshots.toMap(), entryClass, entryMethod)
  }

  private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it.toInt() and 0xff)
      }
}
