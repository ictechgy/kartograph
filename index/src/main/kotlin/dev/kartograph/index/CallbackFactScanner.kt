package dev.kartograph.index

import dev.kartograph.core.CallbackArgument
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.NodeId
import dev.kartograph.core.ParameterUse
import dev.kartograph.core.ParameterUseKind
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.tree.analysis.BasicInterpreter
import org.objectweb.asm.tree.analysis.BasicValue
import org.objectweb.asm.tree.analysis.Frame
import org.objectweb.asm.tree.analysis.Interpreter
import org.objectweb.asm.tree.analysis.Value

/**
 * 메서드 본문의 값 흐름에서 콜백 전달 사실([CallbackArgument])과 콜백 파라미터의 쓰임([ParameterUse])을 관측한다.
 *
 * 값마다 "어디서 왔는가"(파라미터·지역 class 생성·invokedynamic 람다)의 집합을 ASM [Analyzer]로 전파한다. 지역 변수
 * 복사·`dup`·`checkcast`는 값을 바꾸지 않고, 분기에서 만나면 합집합이 된다. 호출 인자·수신 객체·필드 저장·반환 같은
 * 소비 지점에서 그 집합을 읽어 사실을 남긴다. 어떤 인자가 콜백인지, 어느 호출이 실제로 람다를 실행하는지는 판단하지
 * 않는다 — 여러 class를 이어 보는 정책은 analysis의 몫이다.
 *
 * Compose 컴파일러의 `ComposableLambdaKt.*(… block …)`만 라이브러리 모델로 둔다. 이 함수들은 `block`을 감싼
 * `ComposableLambda`를 돌려주고, 그 `invoke`는 같은 인자로 `block`의 `invoke`를 부른다. 그래서 반환값의 출처를
 * `block`의 출처로 잇는다.
 */
internal object CallbackFactScanner {
    /** 한 class의 메서드 본문에서 관측한 사실이다. */
    data class Facts(val arguments: List<CallbackArgument>, val uses: List<ParameterUse>)

    /** 합류 지점의 출처 집합 상한이다. 넘으면 그 메서드의 사실을 버린다(쓰임을 빠뜨린 채 완전하다고 표시하지 않는다). */
    private const val MAX_ORIGINS = 64

    fun scan(owner: String, methods: List<MethodNode>): Facts {
        val arguments = mutableListOf<CallbackArgument>()
        val uses = mutableListOf<ParameterUse>()
        methods.forEach { method ->
            val facts = scanMethod(owner, method) ?: return@forEach
            arguments += facts.arguments
            uses += facts.uses
        }
        return Facts(arguments, uses)
    }

    /** 분석할 가치가 있는 본문인지 싸게 거른다. 값 흐름 분석은 이 조건을 만족하는 메서드에만 한다. */
    fun isCandidate(method: MethodNode): Boolean {
        if (method.instructions.size() == 0) return false
        if (Type.getArgumentTypes(method.desc).any { it.sort == Type.OBJECT }) return true
        return method.instructions.any { insn -> insn.producesLambda() }
    }

    private fun AbstractInsnNode.producesLambda(): Boolean = when (this) {
        is TypeInsnNode -> opcode == Opcodes.NEW
        is FieldInsnNode -> isSingletonInstance()
        is InvokeDynamicInsnNode -> isLambdaMetafactory()
        else -> false
    }

    private fun scanMethod(owner: String, method: MethodNode): Facts? {
        if (!isCandidate(method)) return null
        val methodId = JvmNodeId.methodId(owner, method.name, method.desc)
        val interpreter = OriginInterpreter(method)
        val frames = try {
            Analyzer(interpreter).analyze(owner, method)
        } catch (_: AnalyzerException) {
            return null
        }
        if (interpreter.overflowed) return null
        val arguments = mutableListOf<CallbackArgument>()
        val uses = mutableListOf<ParameterUse>()
        method.instructions.forEachIndexed { index, insn ->
            val frame = frames[index] ?: return@forEachIndexed
            consume(methodId, insn, frame, arguments, uses)
        }
        val parameters = Type.getArgumentTypes(method.desc)
        val recorded = parameters.indices.filter { parameter -> isCallbackParameter(parameters[parameter], uses.filter { it.parameter == parameter }) }
        val declared = recorded.map { ParameterUse(methodId, it, ParameterUseKind.DECLARED) }
        return Facts(arguments, declared + uses.filter { it.parameter in recorded })
    }

    /**
     * 콜백일 수 있는 파라미터다. 선언 타입이 함수형 타입이거나, 본문이 그 값에 함수형 타입 메서드를 부르거나, 선언 타입
     * 자신의 인터페이스 메서드를 부르는(SAM 호출일 수 있는) 파라미터다. 나머지 파라미터는 기록하지 않는다.
     */
    private fun isCallbackParameter(type: Type, uses: List<ParameterUse>): Boolean {
        if (type.sort != Type.OBJECT) return false
        if (isFunctionalOwner(type.internalName)) return true
        return uses.any { use ->
            use.kind == ParameterUseKind.RECEIVER && use.target != null && ownerOf(use.target!!).let { owner ->
                isFunctionalOwner(owner) || (use.invocation == InvocationKind.INTERFACE && owner == type.internalName)
            }
        }
    }

    private fun consume(methodId: NodeId, insn: AbstractInsnNode, frame: Frame<OriginValue>, arguments: MutableList<CallbackArgument>, uses: MutableList<ParameterUse>) {
        when (insn) {
            is MethodInsnNode -> if (!isComposableLambdaWrapper(insn)) consumeCall(methodId, insn, frame, arguments, uses)
            is InvokeDynamicInsnNode -> consumeIndy(methodId, insn, frame, arguments, uses)
            is FieldInsnNode -> if (insn.opcode == Opcodes.PUTFIELD || insn.opcode == Opcodes.PUTSTATIC) {
                frame.top(0).parameters().forEach { uses += ParameterUse(methodId, it, ParameterUseKind.FIELD, JvmNodeId.fieldId(insn.owner, insn.name, insn.desc)) }
            }
            else -> when (insn.opcode) {
                Opcodes.ARETURN -> frame.top(0).parameters().forEach { uses += ParameterUse(methodId, it, ParameterUseKind.RETURN) }
                Opcodes.AASTORE -> frame.top(0).parameters().forEach { uses += ParameterUse(methodId, it, ParameterUseKind.ARRAY) }
            }
        }
    }

    private fun consumeCall(methodId: NodeId, insn: MethodInsnNode, frame: Frame<OriginValue>, arguments: MutableList<CallbackArgument>, uses: MutableList<ParameterUse>) {
        val target = JvmNodeId.methodId(insn.owner, insn.name, insn.desc)
        val kind = invocationKind(insn.opcode)
        val count = Type.getArgumentTypes(insn.desc).size
        repeat(count) { position ->
            val value = frame.top(count - 1 - position)
            value.parameters().forEach { uses += ParameterUse(methodId, it, ParameterUseKind.ARGUMENT, target, kind, position) }
            value.lambdas().forEach { arguments += CallbackArgument(methodId, target, kind, position, it.id, it.samMethod) }
        }
        if (insn.opcode != Opcodes.INVOKESTATIC && insn.name != "<init>") {
            frame.top(count).parameters().forEach { uses += ParameterUse(methodId, it, ParameterUseKind.RECEIVER, target, kind) }
        }
    }

    /**
     * LambdaMetafactory 람다의 캡처 인자는 구현 메서드의 앞쪽 파라미터가 된다. 정적 구현이면 캡처 순서가 곧 파라미터
     * 위치이고, 수신 객체를 묶는 메서드 참조면 첫 캡처가 수신 객체이므로 한 칸씩 당긴다. 수신 객체로 묶인 파라미터와
     * 모델이 없는 bootstrap의 인자는 [ParameterUseKind.OTHER]로 남긴다.
     */
    private fun consumeIndy(methodId: NodeId, insn: InvokeDynamicInsnNode, frame: Frame<OriginValue>, arguments: MutableList<CallbackArgument>, uses: MutableList<ParameterUse>) {
        val count = Type.getArgumentTypes(insn.desc).size
        val implementation = if (insn.isLambdaMetafactory()) insn.bsmArgs[1] as Handle else null
        val shift = if (implementation == null || implementation.tag == Opcodes.H_INVOKESTATIC || implementation.tag == Opcodes.H_NEWINVOKESPECIAL) 0 else 1
        repeat(count) { capture ->
            val value = frame.top(count - 1 - capture)
            val position = capture - shift
            if (implementation == null || position < 0) {
                value.parameters().forEach { uses += ParameterUse(methodId, it, ParameterUseKind.OTHER) }
                return@repeat
            }
            val body = JvmNodeId.methodId(implementation.owner, implementation.name, implementation.desc)
            value.parameters().forEach { uses += ParameterUse(methodId, it, ParameterUseKind.CAPTURE, body, InvocationKind.BOOTSTRAP, position) }
            value.lambdas().forEach { arguments += CallbackArgument(methodId, body, InvocationKind.BOOTSTRAP, position, it.id, it.samMethod) }
        }
    }

    private fun invocationKind(opcode: Int): InvocationKind = when (opcode) {
        Opcodes.INVOKESTATIC -> InvocationKind.STATIC
        Opcodes.INVOKESPECIAL -> InvocationKind.SPECIAL
        Opcodes.INVOKEINTERFACE -> InvocationKind.INTERFACE
        else -> InvocationKind.VIRTUAL
    }

    private fun Frame<OriginValue>.top(depth: Int): OriginValue = getStack(stackSize - 1 - depth)

    private fun ownerOf(method: NodeId): String = method.value.removePrefix("method:").substringBefore('#')

    /** Kotlin 함수 타입·JDK 함수형 인터페이스다. analysis의 lambda 등급 판정과 같은 집합이다. */
    private fun isFunctionalOwner(owner: String): Boolean = FUNCTIONAL_OWNERS.matches(owner)

    private val FUNCTIONAL_OWNERS = Regex("kotlin/jvm/functions/.+|kotlin/Function|kotlin/jvm/internal/FunctionBase|kotlin/reflect/K(Suspend)?Function\\d*|" +
        "java/util/function/.+|java/lang/Runnable|java/util/concurrent/Callable")

    /** Kotlin이 캡처 없는 class 기반 람다에 만드는 `INSTANCE` 싱글턴 읽기다. 이름이 아니라 필드 타입이 소유 class인지로 본다. */
    private fun FieldInsnNode.isSingletonInstance(): Boolean = opcode == Opcodes.GETSTATIC && name == "INSTANCE" && desc == "L$owner;"

    private fun InvokeDynamicInsnNode.isLambdaMetafactory(): Boolean = bsm.owner == "java/lang/invoke/LambdaMetafactory" &&
        (bsm.name == "metafactory" || bsm.name == "altMetafactory") && bsmArgs.size >= 3 && bsmArgs[0] is Type && bsmArgs[1] is Handle

    /** `ComposableLambdaKt`의 정적 생성 함수다. `Object` 파라미터 하나(`block`)를 받아 `ComposableLambda(N)`을 돌려준다. */
    private fun isComposableLambdaWrapper(insn: MethodInsnNode): Boolean = insn.opcode == Opcodes.INVOKESTATIC &&
        insn.owner == COMPOSABLE_LAMBDA_OWNER && Type.getReturnType(insn.desc).descriptor in COMPOSABLE_LAMBDA_TYPES &&
        Type.getArgumentTypes(insn.desc).count { it.descriptor == "Ljava/lang/Object;" } == 1

    private const val COMPOSABLE_LAMBDA_OWNER = "androidx/compose/runtime/internal/ComposableLambdaKt"
    private val COMPOSABLE_LAMBDA_TYPES = setOf(
        "Landroidx/compose/runtime/internal/ComposableLambda;", "Landroidx/compose/runtime/internal/ComposableLambdaN;",
    )

    /** 값의 출처 하나다. */
    private sealed interface Origin

    /** descriptor의 0부터 센 파라미터 위치다. */
    private data class ParameterOrigin(val parameter: Int) : Origin

    /** 람다 값이다. [id]는 지역 class 정점 또는 invokedynamic 구현 메서드다. */
    private data class LambdaOrigin(val id: NodeId, val samMethod: String?) : Origin

    /** 크기와 출처 집합만 가진 분석 값이다. 기본 연산의 크기는 [BasicInterpreter]에 맡긴다. */
    private data class OriginValue(private val width: Int, val origins: Set<Origin>) : Value {
        override fun getSize(): Int = width

        fun parameters(): List<Int> = origins.filterIsInstance<ParameterOrigin>().map { it.parameter }.sorted()

        fun lambdas(): List<LambdaOrigin> = origins.filterIsInstance<LambdaOrigin>().sortedBy { it.id.value + it.samMethod.orEmpty() }
    }

    private class OriginInterpreter(method: MethodNode) : Interpreter<OriginValue>(Opcodes.ASM9) {
        private val basic = BasicInterpreter()
        private val parameterByLocal: Map<Int, Int> = buildMap {
            var local = if (method.access and Opcodes.ACC_STATIC != 0) 0 else 1
            Type.getArgumentTypes(method.desc).forEachIndexed { index, type -> put(local, index); local += type.size }
        }
        var overflowed = false
            private set

        private fun plain(value: BasicValue?): OriginValue? = value?.let { OriginValue(it.size, emptySet()) }

        private fun basicOf(value: OriginValue): BasicValue = if (value.size == 2) BasicValue.LONG_VALUE else BasicValue.REFERENCE_VALUE

        override fun newValue(type: Type?): OriginValue? = plain(basic.newValue(type))

        override fun newParameterValue(isInstanceMethod: Boolean, local: Int, type: Type): OriginValue {
            val parameter = parameterByLocal[local]
            val origins = if (parameter != null && type.sort == Type.OBJECT) setOf<Origin>(ParameterOrigin(parameter)) else emptySet()
            return OriginValue(type.size, origins)
        }

        override fun newOperation(insn: AbstractInsnNode): OriginValue {
            val lambda = when {
                insn is TypeInsnNode && insn.opcode == Opcodes.NEW -> LambdaOrigin(JvmNodeId.classId(insn.desc), null)
                insn is FieldInsnNode && insn.isSingletonInstance() -> LambdaOrigin(JvmNodeId.classId(insn.owner), null)
                else -> null
            }
            val size = requireNotNull(basic.newOperation(insn)).size
            return OriginValue(size, setOfNotNull(lambda))
        }

        override fun copyOperation(insn: AbstractInsnNode, value: OriginValue): OriginValue = value

        override fun unaryOperation(insn: AbstractInsnNode, value: OriginValue): OriginValue? =
            if (insn.opcode == Opcodes.CHECKCAST) value else plain(basic.unaryOperation(insn, basicOf(value)))

        override fun binaryOperation(insn: AbstractInsnNode, value1: OriginValue, value2: OriginValue): OriginValue? =
            plain(basic.binaryOperation(insn, basicOf(value1), basicOf(value2)))

        override fun ternaryOperation(insn: AbstractInsnNode, value1: OriginValue, value2: OriginValue, value3: OriginValue): OriginValue? = null

        override fun naryOperation(insn: AbstractInsnNode, values: MutableList<out OriginValue>): OriginValue? {
            if (insn is MethodInsnNode && isComposableLambdaWrapper(insn)) {
                val block = Type.getArgumentTypes(insn.desc).indexOfFirst { it.descriptor == "Ljava/lang/Object;" }
                return OriginValue(1, values[block].origins)
            }
            if (insn is InvokeDynamicInsnNode && insn.isLambdaMetafactory()) {
                val implementation = insn.bsmArgs[1] as Handle
                val sam = insn.name + (insn.bsmArgs[0] as Type).descriptor
                return OriginValue(1, setOf(LambdaOrigin(JvmNodeId.methodId(implementation.owner, implementation.name, implementation.desc), sam)))
            }
            return plain(basic.naryOperation(insn, values.map(::basicOf)))
        }

        override fun returnOperation(insn: AbstractInsnNode, value: OriginValue, expected: OriginValue) = Unit

        override fun merge(value1: OriginValue, value2: OriginValue): OriginValue {
            if (value1.size != value2.size) return OriginValue(1, emptySet())
            if (value1.origins.containsAll(value2.origins)) return value1
            val merged = value1.origins + value2.origins
            if (merged.size > MAX_ORIGINS) overflowed = true
            return OriginValue(value1.size, merged)
        }
    }
}
