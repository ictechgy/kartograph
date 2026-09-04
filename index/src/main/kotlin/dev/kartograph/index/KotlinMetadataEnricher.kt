package dev.kartograph.index

import dev.kartograph.core.GraphNode
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.Visibility
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
import kotlin.metadata.kind
import kotlin.metadata.visibility
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.signature
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Opcodes

internal object KotlinMetadataEnricher {
    fun enrich(facts: ClassFacts, metadataAnnotation: Metadata): ClassFacts {
        val metadata = KotlinClassMetadata.readLenient(metadataAnnotation)
        val nodes = facts.nodes.associateBy(GraphNode::id).toMutableMap()
        val runtimeEdges = mutableListOf<GraphEdge>()
        when (metadata) {
            is KotlinClassMetadata.Class -> enrichClass(facts.internalName, metadata.kmClass, nodes)
            is KotlinClassMetadata.FileFacade -> enrichPackage(facts.internalName, metadata.kmPackage, nodes)
            is KotlinClassMetadata.MultiFileClassPart -> enrichPackage(facts.internalName, metadata.kmPackage, nodes)
            is KotlinClassMetadata.SyntheticClass -> {
                nodes.replaceAll { _, node -> node.copy(synthesized = true) }
                val classId = JvmNodeId.classId(facts.internalName)
                facts.nodes.filter { node -> node.kind == NodeKind.METHOD }.forEach { method ->
                    runtimeEdges += GraphEdge(classId, method.id, EdgeKind.REFERENCE)
                }
            }
            else -> Unit
        }
        return facts.copy(
            nodes = facts.nodes.map { node -> nodes.getValue(node.id) },
            edges = facts.edges + runtimeEdges,
        )
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
        nodes.patch(JvmNodeId.classId(owner)) { node -> node.copy(synthesized = true) }
        kmPackage.functions.forEach { function -> enrichFunction(owner, function, nodes) }
        kmPackage.properties.forEach { property -> enrichProperty(owner, property, nodes) }
    }

    private fun enrichFunction(owner: String, function: KmFunction, nodes: MutableMap<NodeId, GraphNode>) {
        val signature = function.signature ?: return
        val receiverType = function.receiverParameterType?.classifierName()
        nodes.patch(JvmNodeId.methodId(owner, signature.name, signature.descriptor)) { node ->
            node.copy(
                visibility = function.visibility.toCoreVisibility(),
                attributes = node.attributes.withExtensionFunction(receiverType != null),
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
