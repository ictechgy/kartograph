package dev.kartograph.index

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.tree.analysis.SourceInterpreter
import org.objectweb.asm.tree.analysis.SourceValue

/** 소비되는 값의 역방향 의존에서만 field를 해석한다. 불완전한 의존 수집은 기존 전체 값 분석으로 되돌린다. */
internal class RuntimeFieldDemand {
    private val graphs = mutableMapOf<MethodNode, Map<AbstractInsnNode, Set<AbstractInsnNode>>?>()

    /** consumer 자체의 반환값이 아니라 그 입력에 필요한 field read 명령을 찾는다. */
    fun fieldsFeeding(owner: String, method: MethodNode, consumers: Collection<AbstractInsnNode>): Set<AbstractInsnNode>? {
        if (consumers.isEmpty()) return emptySet()
        val instructions = method.instructions.toArray()
        if (instructions.none(::isFieldRead)) return emptySet()
        if (!graphs.containsKey(method)) graphs[method] = dependencies(owner, method)
        val graph = graphs[method] ?: return null
        val pending = ArrayDeque(consumers.flatMap { graph[it].orEmpty() })
        val visited = mutableSetOf<AbstractInsnNode>()
        val fields = mutableSetOf<AbstractInsnNode>()
        while (pending.isNotEmpty()) {
            val instruction = pending.removeFirst()
            if (!visited.add(instruction)) continue
            if (isFieldRead(instruction)) fields += instruction
            pending.addAll(graph[instruction].orEmpty())
        }
        return fields
    }

    private fun dependencies(owner: String, method: MethodNode): Map<AbstractInsnNode, Set<AbstractInsnNode>>? {
        val parents = mutableMapOf<AbstractInsnNode, MutableSet<AbstractInsnNode>>()
        val unknown = InsnNode(Opcodes.NOP)
        var bounded = false
        var edgeCount = 0
        fun record(instruction: AbstractInsnNode, values: Collection<SourceValue>) {
            if (bounded) return
            val sources = parents.getOrPut(instruction) { mutableSetOf() }
            for (value in values) for (source in value.insns) {
                if (source === unknown || (sources.add(source) && ++edgeCount > 100_000)) {
                    bounded = true
                    return
                }
            }
        }
        val interpreter = object : SourceInterpreter(Opcodes.ASM9) {
            override fun copyOperation(insn: AbstractInsnNode, value: SourceValue): SourceValue {
                record(insn, listOf(value))
                return super.copyOperation(insn, value)
            }

            override fun unaryOperation(insn: AbstractInsnNode, value: SourceValue): SourceValue? {
                record(insn, listOf(value))
                return super.unaryOperation(insn, value)
            }

            override fun binaryOperation(insn: AbstractInsnNode, first: SourceValue, second: SourceValue): SourceValue? {
                record(insn, listOf(first, second))
                return super.binaryOperation(insn, first, second)
            }

            override fun ternaryOperation(insn: AbstractInsnNode, first: SourceValue, second: SourceValue, third: SourceValue): SourceValue? {
                record(insn, listOf(first, second, third))
                return super.ternaryOperation(insn, first, second, third)
            }

            override fun naryOperation(insn: AbstractInsnNode, values: MutableList<out SourceValue>): SourceValue? {
                record(insn, values)
                return super.naryOperation(insn, values)
            }

            override fun returnOperation(insn: AbstractInsnNode, value: SourceValue, expected: SourceValue) {
                record(insn, listOf(value))
                super.returnOperation(insn, value, expected)
            }

            override fun merge(first: SourceValue, second: SourceValue): SourceValue {
                if (unknown in first.insns || unknown in second.insns) return SourceValue(minOf(first.size, second.size), unknown)
                val merged = super.merge(first, second)
                if (merged.insns.size <= 64) return merged
                bounded = true
                return SourceValue(merged.size, unknown)
            }
        }
        try {
            Analyzer(interpreter).analyze(owner, method)
        } catch (_: AnalyzerException) {
            return null
        }
        return parents.takeUnless { bounded }
    }

    private fun isFieldRead(instruction: AbstractInsnNode): Boolean =
        (instruction is FieldInsnNode && instruction.opcode == Opcodes.GETSTATIC) ||
            (instruction is MethodInsnNode && instruction.owner == "java/lang/reflect/Field" && instruction.name == "get" &&
                instruction.desc == "(Ljava/lang/Object;)Ljava/lang/Object;")
}
