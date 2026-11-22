package dev.kartograph.index

import dev.kartograph.core.GraphNode
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.Visibility
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.KotlinDefaultBridge
import kotlin.Metadata
import kotlin.metadata.ClassKind
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmPackage
import kotlin.metadata.KmProperty
import kotlin.metadata.KmType
import kotlin.metadata.Visibility as KotlinVisibility
import kotlin.metadata.isData
import kotlin.metadata.isInline
import kotlin.metadata.isSuspend
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.kind
import kotlin.metadata.visibility
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature
import kotlin.metadata.jvm.syntheticMethodForAnnotations
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

internal object KotlinMetadataEnricher {
    fun enrich(facts: ClassFacts, metadataAnnotation: Metadata): ClassFacts {
        val metadata = KotlinClassMetadata.readLenient(metadataAnnotation)
        val nodes = facts.nodes.associateBy(GraphNode::id).toMutableMap()
        val runtimeEdges = mutableListOf<GraphEdge>()
        when (metadata) {
            is KotlinClassMetadata.Class -> enrichClass(facts.internalName, metadata.kmClass, nodes)
            is KotlinClassMetadata.FileFacade -> enrichPackage(facts.internalName, metadata.kmPackage, nodes)
            is KotlinClassMetadata.MultiFileClassPart -> enrichPackage(facts.internalName, metadata.kmPackage, nodes)
            // Multi-file facade는 part 선언으로 위임하는 합성 인프라다. part만 보고 대상으로 남긴다.
            is KotlinClassMetadata.MultiFileClassFacade ->
                nodes.patch(JvmNodeId.classId(facts.internalName)) { node -> node.copy(synthesized = true) }
            is KotlinClassMetadata.SyntheticClass -> {
                nodes.replaceAll { _, node -> node.copy(synthesized = true) }
                val classId = JvmNodeId.classId(facts.internalName)
                facts.nodes.filter { node -> node.kind == NodeKind.METHOD }.forEach { method ->
                    runtimeEdges += GraphEdge(classId, method.id, EdgeKind.REFERENCE, origin = dev.kartograph.core.EdgeOrigin.KOTLIN_METADATA)
                }
            }
            else -> Unit
        }
        val functions = when (metadata) {
            is KotlinClassMetadata.Class -> metadata.kmClass.functions
            is KotlinClassMetadata.FileFacade -> metadata.kmPackage.functions
            is KotlinClassMetadata.MultiFileClassPart -> metadata.kmPackage.functions
            else -> emptyList()
        }
        val properties = when (metadata) {
            is KotlinClassMetadata.Class -> metadata.kmClass.properties
            is KotlinClassMetadata.FileFacade -> metadata.kmPackage.properties
            is KotlinClassMetadata.MultiFileClassPart -> metadata.kmPackage.properties
            else -> emptyList()
        }
        enrichDefaultBridges(facts, functions, nodes)
        markJvmOverloads(functions, nodes)
        markSuspendInlineCopies(facts.internalName, functions, nodes)
        val isInterface = metadata is KotlinClassMetadata.Class && metadata.kmClass.kind == ClassKind.INTERFACE
        runtimeEdges += propertyAnnotationHolderEdges(facts.internalName, properties, isInterface)
        return facts.copy(
            nodes = facts.nodes.map { node -> nodes.getValue(node.id) },
            edges = facts.edges + runtimeEdges,
        )
    }

    /**
     * `@JvmOverloads`가 만든 overload를 compiler 생성물로 표시한다. 원본과 생성 overload 모두 `kotlin/jvm/JvmOverloads`를
     * class 파일에 남기지만 Kotlin metadata에는 원본 시그니처만 있다. 이름만으로 추측하지 않는다.
     * 생성 overload는 원본으로 위임하므로 Java 호출자의 도달성은 그대로 이어진다.
     *
     * compiler는 같은 owner metadata가 선언하고 class 파일에 `@JvmOverloads`를 남긴 함수에서 default 매개변수를 끝에서부터
     * 하나씩 뺀 시그니처만 만든다. 그 정확한 시그니처와 같은 method만 표시한다. companion `@JvmStatic` 함수가 바깥 class에
     * 남긴 정적 bridge는 바깥 class metadata에 원본이 없고, 바깥 class가 같은 이름의 더 긴 함수를 직접 선언해도 그 함수가
     * `@JvmOverloads`·default 매개변수로 그 시그니처를 만들지 않으면 표시하지 않는다.
     */
    private fun markJvmOverloads(functions: List<KmFunction>, nodes: MutableMap<NodeId, GraphNode>) {
        val annotated = nodes.values.mapNotNullTo(mutableSetOf()) { node -> node.overloadsSignature() }
        val generated = functions.flatMapTo(mutableSetOf()) { function -> generatedOverloads(function, annotated) }
        if (generated.isEmpty()) return
        nodes.replaceAll { _, node -> if (node.overloadsSignature() in generated) node.copy(synthesized = true) else node }
    }

    /** `@JvmOverloads`가 붙은 method의 `name(descriptor)`이다. 그 밖의 node는 null이다. */
    private fun GraphNode.overloadsSignature(): String? =
        if (kind == NodeKind.METHOD && JVM_OVERLOADS in annotations) {
            jvmSignature?.substringAfter('#', missingDelimiterValue = "")?.takeIf { it.isNotEmpty() }
        } else {
            null
        }

    /**
     * [function]이 `@JvmOverloads` 원본일 때 compiler가 만드는 overload 시그니처 목록이다. default 매개변수를 마지막 것부터
     * 1개, 2개, … 뺀다. JVM 인자 앞쪽의 context·extension receiver와 suspend 함수 끝의 `Continuation`처럼 value parameter가
     * 아닌 인자는 그대로 둔다.
     */
    private fun generatedOverloads(function: KmFunction, annotated: Set<String>): List<String> {
        val signature = function.signature ?: return emptyList()
        if (signature.name + signature.descriptor !in annotated) return emptyList()
        // value class 매개변수 등으로 이름이 바뀐 원본의 생성 overload는 원본 이름을 물려받지 않고 각자 다른 hash 이름을 받는다.
        // 원본 이름으로 계산하면 그 이름을 `@JvmName`으로 쓴 다른 직접 작성 함수를 생성물로 숨길 수 있어 아예 계산하지 않는다.
        if (isMangled(function.name, signature.name)) return emptyList()
        val arguments = Type.getArgumentTypes(signature.descriptor)
        // suspend 함수는 compiler가 JVM 인자 끝에 Continuation 하나를 더한다. 앞쪽 인자로 세면 value parameter 위치가 한 칸씩 밀린다.
        val trailing = if (function.isSuspend) 1 else 0
        val leading = arguments.size - function.valueParameters.size - trailing
        if (leading < 0) return emptyList()
        val defaulted = function.valueParameters.indices.filter { function.valueParameters[it].declaresDefaultValue }
        return (1..defaulted.size).map { droppedCount ->
            val dropped = defaulted.takeLast(droppedCount).mapTo(mutableSetOf()) { it + leading }
            val kept = arguments.filterIndexed { index, _ -> index !in dropped }
            signature.name + Type.getMethodDescriptor(Type.getReturnType(signature.descriptor), *kept.toTypedArray())
        }
    }

    /**
     * compiler가 Kotlin 이름 뒤에 `-hash`(value class 매개변수)를 붙인 JVM 이름인지 본다. value class 멤버의 `-impl`,
     * `@JvmName`으로 정한 이름, internal의 `$module` 접미사, `-`가 든 backtick 이름은 생성 overload도 같은 이름을 써서 해당하지 않는다.
     */
    private fun isMangled(kotlinName: String, jvmName: String): Boolean =
        jvmName != kotlinName && jvmName != "$kotlinName-impl" && jvmName.startsWith("$kotlinName-")

    private const val JVM_OVERLOADS = "kotlin/jvm/JvmOverloads"

    /**
     * public·protected·internal `suspend inline` 함수마다 compiler가 inliner용으로 남기는 사본 `<JVM 이름>$$forInline`을
     * 생성물로 표시한다. 사본은 상태 기계가 없는 본문이라 호출되지 않고, 다른 class의 inline 호출 지점으로 복사될 뿐이다.
     * 같은 owner metadata가 suspend inline으로 선언한 함수의 JVM 이름에 접미사를 붙이고 descriptor까지 같은 method만 본다.
     * metadata가 함수로 선언한 method(`@JvmName`으로 같은 이름을 쓴 직접 작성 함수)는 표시하지 않는다.
     */
    private fun markSuspendInlineCopies(owner: String, functions: List<KmFunction>, nodes: MutableMap<NodeId, GraphNode>) {
        val declared = functions.mapNotNullTo(mutableSetOf()) { function -> function.signature?.let { it.name + it.descriptor } }
        functions.filter { function -> function.isSuspend && function.isInline }.forEach { function ->
            val signature = function.signature ?: return@forEach
            val copyName = signature.name + FOR_INLINE_SUFFIX
            if (copyName + signature.descriptor in declared) return@forEach
            nodes.patch(JvmNodeId.methodId(owner, copyName, signature.descriptor)) { node -> node.copy(synthesized = true) }
        }
    }

    private const val FOR_INLINE_SUFFIX = "\$\$forInline"

    /**
     * property 대상 어노테이션은 property가 아니라 metadata `syntheticMethodForAnnotations`가 가리키는 합성 method
     * (`get<Name>$annotations`)에 기록된다. 그 method는 호출되지 않아, property를 써도 어노테이션 class가 도달 불가로 보였다.
     * 같은 property의 getter·setter·backing field에서 그 method로 metadata 간선을 이어 property가 도달하면 어노테이션도 도달하게
     * 한다. interface property의 합성 method는 `-jvm-default` 호환 mode에서 `DefaultImpls`에, 아니면 interface 자신에 있으므로 두
     * 위치를 모두 후보로 둔다. 양 끝 정점이 그래프에 없는 간선은 그래프를 만들 때 버려진다.
     */
    private fun propertyAnnotationHolderEdges(owner: String, properties: List<KmProperty>, isInterface: Boolean): List<GraphEdge> {
        val holderOwners = if (isInterface) listOf(owner, owner + DEFAULT_IMPLS_SUFFIX) else listOf(owner)
        return properties.flatMap { property ->
            val holder = property.syntheticMethodForAnnotations ?: return@flatMap emptyList()
            val members = listOfNotNull(
                property.getterSignature?.let { JvmNodeId.methodId(owner, it.name, it.descriptor) },
                property.setterSignature?.let { JvmNodeId.methodId(owner, it.name, it.descriptor) },
                property.fieldSignature?.let { JvmNodeId.fieldId(owner, it.name, it.descriptor) },
            )
            members.flatMap { member ->
                holderOwners.map { holderOwner ->
                    GraphEdge(member, JvmNodeId.methodId(holderOwner, holder.name, holder.descriptor), EdgeKind.REFERENCE,
                        origin = dev.kartograph.core.EdgeOrigin.KOTLIN_METADATA)
                }
            }
        }
    }

    private const val DEFAULT_IMPLS_SUFFIX = "\$DefaultImpls"

    // 이름이나 int 꼬리만으로 추측하면 default expression이 호출한 overload를 원본으로 오인한다.
    // 같은 owner의 metadata JVM signature와 value parameter 수, 실제 CALL을 모두 요구한다.
    private fun enrichDefaultBridges(facts: ClassFacts, functions: List<KmFunction>, nodes: MutableMap<NodeId, GraphNode>) {
        val defaulted = functions.filter { function -> function.valueParameters.any { it.declaresDefaultValue } }
        if (defaulted.isEmpty()) return
        val candidates = linkedMapOf<NodeId, MutableSet<KotlinDefaultBridge>>()
        val calls = facts.calls.groupBy { it.caller }
        defaulted.forEach { function ->
            val signature = function.signature ?: return@forEach
            val targetId = JvmNodeId.methodId(facts.internalName, signature.name, signature.descriptor)
            val target = nodes[targetId] ?: return@forEach
            val valueCount = function.valueParameters.size
            if (valueCount !in 1..255) return@forEach
            val maskCount = (valueCount + 31) / 32
            val arguments = buildList {
                if (JvmModifier.STATIC !in target.jvmModifiers) add(Type.getObjectType(facts.internalName))
                addAll(Type.getArgumentTypes(signature.descriptor))
                repeat(maskCount) { add(Type.INT_TYPE) }
                add(Type.getType("Ljava/lang/Object;"))
            }
            val descriptor = Type.getMethodDescriptor(Type.getReturnType(signature.descriptor), *arguments.toTypedArray())
            val bridgeId = JvmNodeId.methodId(facts.internalName, signature.name + "\$default", descriptor)
            val bridge = nodes[bridgeId] ?: return@forEach
            if (bridge.kotlinSourceName != null || JvmModifier.STATIC !in bridge.jvmModifiers ||
                JvmModifier.SYNTHETIC !in bridge.jvmModifiers || calls[bridgeId].orEmpty().none { it.target == targetId }) return@forEach
            candidates.getOrPut(bridgeId) { linkedSetOf() } += KotlinDefaultBridge(targetId, valueCount, maskCount)
        }
        candidates.forEach { (bridge, associations) ->
            associations.singleOrNull()?.let { association -> nodes.patch(bridge) { it.copy(kotlinDefaultBridge = association) } }
        }
    }

    private fun enrichClass(owner: String, kmClass: KmClass, nodes: MutableMap<NodeId, GraphNode>) {
        nodes.patch(JvmNodeId.classId(owner)) { node ->
            node.copy(
                kind = kmClass.kind.toNodeKind(),
                visibility = kmClass.visibility.toCoreVisibility(),
                attributes = node.attributes.withDataClass(kmClass.isData),
            )
        }
        kmClass.functions.forEach { function -> enrichFunction(owner, function, nodes) }
        kmClass.constructors.forEach { constructor ->
            val signature = constructor.signature ?: return@forEach
            nodes.patch(JvmNodeId.methodId(owner, signature.name, signature.descriptor)) { node ->
                node.copy(visibility = constructor.visibility.toCoreVisibility())
            }
        }
        kmClass.properties.forEach { property -> enrichProperty(owner, property, nodes) }
    }

    private fun enrichPackage(owner: String, kmPackage: KmPackage, nodes: MutableMap<NodeId, GraphNode>) {
        nodes.patch(JvmNodeId.classId(owner)) { node ->
            node.copy(synthesized = true, attributes = node.attributes + NodeAttribute.FILE_FACADE)
        }
        kmPackage.functions.forEach { function -> enrichFunction(owner, function, nodes) }
        kmPackage.properties.forEach { property ->
            enrichProperty(owner, property, nodes)
            markPropertyAccessors(owner, property, nodes)
        }
    }

    // top-level property 접근자는 property를 대신하는 인프라이므로 따로 보고하지 않게 표시한다.
    private fun markPropertyAccessors(owner: String, property: KmProperty, nodes: MutableMap<NodeId, GraphNode>) {
        listOfNotNull(property.getterSignature, property.setterSignature).forEach { signature ->
            nodes.patch(JvmNodeId.methodId(owner, signature.name, signature.descriptor)) { node ->
                node.copy(attributes = node.attributes + NodeAttribute.PROPERTY_ACCESSOR)
            }
        }
    }

    private fun enrichFunction(owner: String, function: KmFunction, nodes: MutableMap<NodeId, GraphNode>) {
        val signature = function.signature ?: return
        val receiverType = function.receiverParameterType?.classifierName()
        nodes.patch(JvmNodeId.methodId(owner, signature.name, signature.descriptor)) { node ->
            node.copy(
                kotlinSourceName = function.name,
                visibility = function.visibility.toCoreVisibility(),
                attributes = node.attributes.withExtensionFunction(receiverType != null).let { attributes ->
                    if (function.isInline) attributes + NodeAttribute.INLINE_FUNCTION else attributes
                },
                extensionReceiverType = receiverType,
            )
        }
    }

    private fun enrichProperty(owner: String, property: KmProperty, nodes: MutableMap<NodeId, GraphNode>) {
        val signature = property.fieldSignature ?: return
        nodes.patch(JvmNodeId.fieldId(owner, signature.name, signature.descriptor)) { node ->
            node.copy(
                name = property.name,
                kind = NodeKind.PROPERTY,
                visibility = property.visibility.toCoreVisibility(),
            )
        }
    }

    private fun MutableMap<NodeId, GraphNode>.patch(id: NodeId, transform: (GraphNode) -> GraphNode) {
        val node = this[id] ?: return
        this[id] = transform(node)
    }
}

internal class MetadataAnnotationValues : AnnotationVisitor(Opcodes.ASM9) {
    private var kind: Int? = null
    private var metadataVersion: IntArray? = null
    private val data1 = mutableListOf<String>()
    private val data2 = mutableListOf<String>()
    private var extraString: String? = null
    private var packageName: String? = null
    private var extraInt: Int? = null

    override fun visit(name: String, value: Any) {
        when (name) {
            "k" -> kind = value as Int
            "mv" -> metadataVersion = value as IntArray
            "xs" -> extraString = value as String
            "pn" -> packageName = value as String
            "xi" -> extraInt = value as Int
        }
    }

    override fun visitArray(name: String): AnnotationVisitor? = when (name) {
        "d1" -> StringArrayVisitor(data1)
        "d2" -> StringArrayVisitor(data2)
        else -> null
    }

    fun toMetadata(): Metadata? {
        if (kind == null) return null
        return kotlin.metadata.jvm.Metadata(
            kind = kind,
            metadataVersion = metadataVersion,
            data1 = data1.toTypedArray(),
            data2 = data2.toTypedArray(),
            extraString = extraString,
            packageName = packageName,
            extraInt = extraInt,
        )
    }
}

private class StringArrayVisitor(private val values: MutableList<String>) : AnnotationVisitor(Opcodes.ASM9) {
    override fun visit(name: String?, value: Any) {
        values += value as String
    }
}

private fun ClassKind.toNodeKind(): NodeKind = when (this) {
    ClassKind.INTERFACE -> NodeKind.INTERFACE
    ClassKind.ENUM_CLASS, ClassKind.ENUM_ENTRY -> NodeKind.ENUM
    ClassKind.ANNOTATION_CLASS -> NodeKind.ANNOTATION_CLASS
    ClassKind.OBJECT, ClassKind.COMPANION_OBJECT -> NodeKind.OBJECT
    ClassKind.CLASS -> NodeKind.CLASS
}

private fun KotlinVisibility.toCoreVisibility(): Visibility = when (this) {
    KotlinVisibility.PUBLIC -> Visibility.PUBLIC
    KotlinVisibility.PROTECTED -> Visibility.PROTECTED
    KotlinVisibility.INTERNAL -> Visibility.INTERNAL
    KotlinVisibility.PRIVATE -> Visibility.PRIVATE
    KotlinVisibility.PRIVATE_TO_THIS -> Visibility.PRIVATE_TO_THIS
    KotlinVisibility.LOCAL -> Visibility.LOCAL
}

private fun Set<NodeAttribute>.withDataClass(isData: Boolean): Set<NodeAttribute> =
    if (isData) this + NodeAttribute.DATA_CLASS else this

private fun Set<NodeAttribute>.withExtensionFunction(isExtension: Boolean): Set<NodeAttribute> =
    if (isExtension) this + NodeAttribute.EXTENSION_FUNCTION else this

private fun KmType.classifierName(): String = when (val value = classifier) {
    is KmClassifier.Class -> value.name
    is KmClassifier.TypeAlias -> value.name
    is KmClassifier.TypeParameter -> "type-parameter:${value.id}"
}
