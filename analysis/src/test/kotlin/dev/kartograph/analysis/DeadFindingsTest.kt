package dev.kartograph.analysis

import dev.kartograph.core.*
import kotlin.test.Test
import kotlin.test.assertEquals

class DeadFindingsTest {
    @Test
    fun `member scope excludes runtime generated native public and dead owner noise`() {
        val owner = GraphNode(NodeId("class:Owner"), "Owner", NodeKind.CLASS)
        val dead = owner.copy(id = NodeId("class:Dead"), name = "Dead")
        val generated = owner.copy(id = NodeId("class:Generated"), name = "Generated", synthesized = true)
        fun member(name: String, kind: NodeKind = NodeKind.METHOD) = GraphNode(
            NodeId("method:Owner#$name"), name, kind, visibility = Visibility.PRIVATE,
        )
        val wanted = member("wanted")
        val property = member("property", NodeKind.PROPERTY)
        val excluded = listOf(
            member("public").copy(visibility = Visibility.PUBLIC),
            member("jvmPublic").copy(jvmVisibility = Visibility.PUBLIC),
            member("native").copy(jvmModifiers = setOf(JvmModifier.NATIVE)),
            member("constructor", NodeKind.CONSTRUCTOR),
            member("synthetic").copy(synthesized = true),
            member("constant", NodeKind.FIELD).copy(attributes = setOf(NodeAttribute.COMPILE_TIME_CONSTANT)),
            member("used"),
        )
        val inline = member("inlined").copy(attributes = setOf(NodeAttribute.INLINE_FUNCTION))
        val serialization = member("readObject")
        val privateThis = member("privateThis", NodeKind.FUNCTION).copy(visibility = Visibility.PRIVATE_TO_THIS)
        val ambiguous = member("ambiguous")
        val orphan = member("orphan")
        val deadMember = member("deadOwner")
        val generatedMember = member("generatedOwner")
        val owned = listOf(wanted, property, inline, serialization, privateThis, ambiguous) + excluded
        val graph = CodeGraph(listOf(owner, dead, generated, orphan, deadMember, generatedMember) + owned,
            owned.map { GraphEdge(owner.id, it.id, EdgeKind.MEMBER) } + listOf(
                GraphEdge(dead.id, deadMember.id, EdgeKind.MEMBER),
                GraphEdge(generated.id, generatedMember.id, EdgeKind.MEMBER),
                GraphEdge(dead.id, ambiguous.id, EdgeKind.MEMBER),
            ))
        val result = ReachabilityAnalyzer.analyze(graph, listOf(owner, generated, excluded.last()).map {
            RetentionEvidence(it.id, RetentionReason.KEEP_RULE, null)
        })
        assertEquals(listOf(dead.id), DeadFindings.collect(graph, result).map { it.nodeId })
        assertEquals(listOf(dead.id, property.id, wanted.id, privateThis.id).sorted(),
            DeadFindings.collect(graph, result, true).map { it.nodeId })
    }

    @Test
    fun `unused framework and constant owners stay conservative roots and disclose why`() {
        // SOUND-1·리뷰: 미사용이어도 보고하지 않는 보수적 보존 경로(DI·직렬화·runtime 진입·상수 owner)를 고정하고,
        // 그 누락을 dead의 정적 한계로 알리는지 확인한다.
        fun type(name: String, kind: NodeKind = NodeKind.CLASS, annotations: Set<String> = emptySet()) =
            GraphNode(NodeId("class:app/$name"), name, kind, annotations = annotations)
        fun member(owner: String, name: String, kind: NodeKind, annotation: String? = null) =
            GraphNode(NodeId("method:app/$owner#$name()V"), name, kind, annotations = setOfNotNull(annotation))
        val injectable = type("Repository")
        val constructor = member("Repository", "<init>", NodeKind.CONSTRUCTOR, "javax/inject/Inject")
        val dto = type("UserDto")
        val exposed = GraphNode(NodeId("field:app/UserDto#name:Ljava/lang/String;"), "name", NodeKind.FIELD,
            annotations = setOf("com/google/gson/annotations/Expose"))
        val converters = type("Converters")
        val convert = member("Converters", "fromTimestamp", NodeKind.METHOD, "androidx/room/TypeConverter")
        val codec = type("DateCodec")
        val component = type("AppComponent", NodeKind.INTERFACE, setOf("dagger/Component"))
        val constants = type("Keys", NodeKind.OBJECT)
        val tag = GraphNode(NodeId("field:app/Keys#TAG:Ljava/lang/String;"), "TAG", NodeKind.FIELD,
            attributes = setOf(NodeAttribute.COMPILE_TIME_CONSTANT))
        val unused = type("Unused")
        val graph = CodeGraph(listOf(injectable, constructor, dto, exposed, converters, convert, codec, component, constants, tag, unused),
            listOf(
                GraphEdge(injectable.id, constructor.id, EdgeKind.MEMBER),
                GraphEdge(dto.id, exposed.id, EdgeKind.MEMBER),
                GraphEdge(converters.id, convert.id, EdgeKind.MEMBER),
                GraphEdge(convert.id, codec.id, EdgeKind.REFERENCE),
                GraphEdge(constants.id, tag.id, EdgeKind.MEMBER),
            ))

        val evidence = DefaultRetention.find(graph, emptyList(), emptyList())
        val result = ReachabilityAnalyzer.analyze(graph, evidence)

        assertEquals(listOf(unused.id), DeadFindings.collect(graph, result).map { it.nodeId })
        assertEquals(listOf(RetentionReason.DEPENDENCY_INJECTION), result.retentionEvidenceFor(injectable.id).map { it.reason })
        assertEquals(listOf(RetentionReason.DEPENDENCY_INJECTION), result.retentionEvidenceFor(component.id).map { it.reason })
        assertEquals(listOf(RetentionReason.SERIALIZATION), result.retentionEvidenceFor(dto.id).map { it.reason })
        assertEquals(listOf(RetentionReason.SERIALIZATION), result.retentionEvidenceFor(converters.id).map { it.reason })
        assertEquals(true, codec.id in result.reachableNodeIds)
        assertEquals(listOf(RetentionReason.INLINE_CONSTANT), result.retentionEvidenceFor(constants.id).map { it.reason })
        assertEquals(true, AnalysisLimitation.INLINE_CONSTANT_REFERENCES in AnalysisLimitation.entries)
        assertEquals(
            "owners retained by supported dependency-injection, serialization or runtime-entry annotations " +
                "(such as @Inject constructors, @Expose fields, @TypeConverter methods or @Component types) and all " +
                "their members are roots without proof that generated code or a framework uses them",
            AnalysisLimitation.FRAMEWORK_ANNOTATION_OWNERS.description,
        )
        assertEquals(AnalysisLimitation.entries.last(), AnalysisLimitation.FRAMEWORK_ANNOTATION_OWNERS)
    }

    @Test
    fun `file facade top-level declarations are reported conservatively`() {
        val facade = GraphNode(NodeId("class:FacadeKt"), "FacadeKt", NodeKind.CLASS,
            synthesized = true, attributes = setOf(NodeAttribute.FILE_FACADE))
        fun topLevel(
            name: String,
            visibility: Visibility = Visibility.PUBLIC,
            attributes: Set<NodeAttribute> = emptySet(),
            modifiers: Set<JvmModifier> = setOf(JvmModifier.STATIC),
            synthesized: Boolean = false,
        ) = GraphNode(
            NodeId("method:FacadeKt#$name()V"), name, NodeKind.METHOD, visibility = visibility,
            jvmVisibility = if (visibility == Visibility.PRIVATE) Visibility.PRIVATE else Visibility.PUBLIC,
            jvmModifiers = modifiers, attributes = attributes, synthesized = synthesized,
        )
        val root = GraphNode(NodeId("class:Root"), "Root", NodeKind.CLASS)
        val used = topLevel("used")
        val unused = topLevel("unused")
        val inlined = topLevel("inlined", attributes = setOf(NodeAttribute.INLINE_FUNCTION))
        val accessor = topLevel("getProperty", attributes = setOf(NodeAttribute.PROPERTY_ACCESSOR))
        val native = topLevel("nativeCall", modifiers = setOf(JvmModifier.STATIC, JvmModifier.NATIVE))
        val privateUnused = topLevel("privateUnused", visibility = Visibility.PRIVATE)
        val syntheticBridge = topLevel("bridge", synthesized = true)
        val launcherMain = topLevel("main")
        val suspendMain = GraphNode(NodeId("method:FacadeKt#main(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
            "main", NodeKind.METHOD, visibility = Visibility.PUBLIC, jvmVisibility = Visibility.PUBLIC,
            jvmModifiers = setOf(JvmModifier.STATIC))
        val facadeInit = GraphNode(NodeId("method:FacadeKt#<init>()V"), "<init>", NodeKind.CONSTRUCTOR,
            visibility = Visibility.PRIVATE, jvmVisibility = Visibility.PRIVATE)
        val regularPublicMethod = GraphNode(NodeId("method:Root#orphan()V"), "orphan", NodeKind.METHOD,
            visibility = Visibility.PUBLIC, jvmVisibility = Visibility.PUBLIC)
        val facadeMembers = listOf(
            used, unused, inlined, accessor, native, privateUnused, syntheticBridge, launcherMain,
            suspendMain, facadeInit,
        )
        val graph = CodeGraph(
            listOf(root, facade, regularPublicMethod) + facadeMembers,
            facadeMembers.map { GraphEdge(facade.id, it.id, EdgeKind.MEMBER) } + listOf(
                GraphEdge(root.id, used.id, EdgeKind.CALL),
                GraphEdge(root.id, regularPublicMethod.id, EdgeKind.MEMBER),
            ),
        )
        val result = ReachabilityAnalyzer.analyze(
            graph, listOf(RetentionEvidence(root.id, RetentionReason.KEEP_RULE, null)),
        )

        assertEquals(listOf(unused.id), DeadFindings.collect(graph, result).map { it.nodeId })
        assertEquals(listOf(privateUnused.id, unused.id),
            DeadFindings.collect(graph, result, true).map { it.nodeId })
    }
}
