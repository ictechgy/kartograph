package dev.kartograph.analysis

import dev.kartograph.core.ClassHierarchy
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.KeepDeclarationKind
import dev.kartograph.core.KeepMemberCondition
import dev.kartograph.core.KeepMemberKind
import dev.kartograph.core.KeepRule
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.RetentionReason
import dev.kartograph.core.SourceLocation
import dev.kartograph.core.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KeepRuleRetentionTest {
    @Test
    fun `matches ProGuard stars without crossing package separators`() {
        val graph = graph(
            node("dev/app/Direct"),
            node("dev/app/nested/Nested"),
            node("dev/api/Contract", NodeKind.INTERFACE),
        )
        val rules = listOf(
            rule(KeepDeclarationKind.CLASS, "dev.app.*", line = 1),
            rule(KeepDeclarationKind.INTERFACE, "dev.api.?ontract", line = 2),
        )

        val evidence = KeepRuleRetention.find(graph, rules)

        assertEquals(
            listOf(NodeId("class:dev/api/Contract"), NodeId("class:dev/app/Direct")),
            evidence.map { item -> item.nodeId },
        )
        assertEquals(setOf(RetentionReason.KEEP_RULE), evidence.map { item -> item.reason }.toSet())
    }

    @Test
    fun `matches double stars and transitive supertypes`() {
        val graph = graph(
            node("dev/base/Base"),
            node("dev/base/Middle", supertypes = setOf("dev/base/Base")),
            node("dev/app/feature/Child", supertypes = setOf("dev/base/Middle")),
            node(
                "dev/app/feature/Hidden",
                supertypes = setOf("dev/base/Middle"),
                jvmVisibility = Visibility.PRIVATE,
            ),
            node("dev/other/Child", supertypes = setOf("dev/base/Middle")),
        )
        val keepRule = rule(
            KeepDeclarationKind.CLASS,
            "dev.app.**",
            extendsPattern = "dev.base.Base",
            requiredJvmVisibilities = setOf(Visibility.PUBLIC),
            line = 7,
        )

        val evidence = KeepRuleRetention.find(graph, listOf(keepRule))

        assertEquals(listOf(NodeId("class:dev/app/feature/Child")), evidence.map { item -> item.nodeId })
        assertEquals(SourceLocation("rules.pro", 7), evidence.single().location)
    }

    @Test
    fun `standalone star crosses packages and class includes every JVM class type`() {
        val graph = graph(
            node("deep/package/Implementation"),
            node("deep/package/Contract", NodeKind.INTERFACE),
            node("deep/package/Choice", NodeKind.ENUM),
        )

        val evidence = KeepRuleRetention.find(
            graph,
            listOf(rule(KeepDeclarationKind.CLASS, "*", line = 3)),
        )

        assertEquals(graph.nodeIds, evidence.map { item -> item.nodeId })
    }

    @Test
    fun `access modifiers use JVM visibility instead of Kotlin source visibility`() {
        val graph = graph(
            node(
                "dev/app/InternalInSource",
                sourceVisibility = Visibility.INTERNAL,
                jvmVisibility = Visibility.PUBLIC,
            ),
        )
        val publicRule = rule(
            KeepDeclarationKind.CLASS,
            "dev.app.*",
            requiredJvmVisibilities = setOf(Visibility.PUBLIC),
            line = 4,
        )

        assertEquals(graph.nodeIds, KeepRuleRetention.find(graph, listOf(publicRule)).map { it.nodeId })
    }

    @Test
    fun `deduplicates identical evidence when the same rule file is passed twice`() {
        val graph = graph(node("dev/app/Repeated"))
        val keepRule = rule(KeepDeclarationKind.CLASS, "dev.app.Repeated", line = 8)

        val evidence = KeepRuleRetention.find(graph, listOf(keepRule, keepRule))

        assertEquals(1, evidence.size)
    }

    @Test
    fun `matches a direct external supertype without its class file`() {
        val graph = graph(node("dev/app/Screen", supertypes = setOf("android/app/Activity")))
        val keepRule = rule(
            KeepDeclarationKind.CLASS,
            "*",
            extendsPattern = "android.app.Activity",
            line = 9,
        )

        assertEquals(graph.nodeIds, KeepRuleRetention.find(graph, listOf(keepRule)).map { it.nodeId })
    }

    @Test
    fun `refuses a verdict when an external transitive hierarchy is unavailable`() {
        val graph = graph(
            node("dev/app/UploadWorker", supertypes = setOf("androidx/work/CoroutineWorker")),
        )
        val keepRule = rule(
            KeepDeclarationKind.CLASS,
            "*",
            extendsPattern = "androidx.work.ListenableWorker",
            line = 10,
        )

        val error = assertFailsWith<IllegalStateException> {
            KeepRuleRetention.find(graph, listOf(keepRule))
        }

        assertEquals(incompleteHierarchyMessage(10), error.message)
    }

    @Test
    fun `does not trust an indexed target across a missing intermediate supertype`() {
        val graph = graph(
            node("dev/app/BaseActivity"),
            node("dev/app/MyScreen", supertypes = setOf("com/library/MissingActivity")),
        )
        val keepRule = rule(
            KeepDeclarationKind.CLASS,
            "dev.app.MyScreen",
            extendsPattern = "dev.app.BaseActivity",
            line = 11,
        )

        val error = assertFailsWith<IllegalStateException> {
            KeepRuleRetention.find(graph, listOf(keepRule))
        }

        assertEquals(incompleteHierarchyMessage(11), error.message)
    }

    @Test
    fun `matches a target through dependency hierarchy without adding dependency graph nodes`() {
        val graph = graph(node("dev/app/Leaf", supertypes = setOf("dev/library/Intermediate")))
        val dependencyHierarchy = ClassHierarchy(
            mapOf("dev/library/Intermediate" to setOf("dev/library/Target")),
        )
        val keepRule = rule(
            KeepDeclarationKind.CLASS,
            "dev.app.Leaf",
            extendsPattern = "dev.library.Target",
            line = 12,
        )

        val evidence = KeepRuleRetention.find(graph, listOf(keepRule), dependencyHierarchy)

        assertEquals(listOf(NodeId("class:dev/app/Leaf")), evidence.map { it.nodeId })
        assertEquals(listOf(NodeId("class:dev/app/Leaf")), graph.nodeIds)
    }

    @Test
    fun `matches class annotation patterns against bytecode annotation names`() {
        val graph = graph(
            node("dev/app/Annotated", annotations = setOf("dev/annotation/Marker")),
            node("dev/app/Plain"),
        )
        val keepRule = KeepRule(
            declarationKind = KeepDeclarationKind.CLASS,
            classNamePattern = "dev.app.*",
            location = SourceLocation("rules.pro", 13),
            requiredAnnotationPattern = "dev.annotation.*",
        )

        val evidence = KeepRuleRetention.find(graph, listOf(keepRule))

        assertEquals(listOf(NodeId("class:dev/app/Annotated")), evidence.map { it.nodeId })
    }

    @Test
    fun `retains a class and only members matching every conditional specification`() {
        val owner = node("dev/app/Conditional")
        val entryPoint = GraphNode(
            id = NodeId("method:dev/app/Conditional#entry()V"),
            name = "entry",
            kind = NodeKind.METHOD,
            annotations = setOf("dev/annotation/EntryPoint"),
        )
        val plainMethod = GraphNode(
            id = NodeId("method:dev/app/Conditional#plain()V"),
            name = "plain",
            kind = NodeKind.METHOD,
        )
        val graph = CodeGraph(
            nodes = listOf(owner, entryPoint, plainMethod),
            edges = listOf(
                GraphEdge(owner.id, entryPoint.id, EdgeKind.MEMBER),
                GraphEdge(owner.id, plainMethod.id, EdgeKind.MEMBER),
            ),
        )
        val keepRule = KeepRule(
            declarationKind = KeepDeclarationKind.CLASS,
            classNamePattern = "dev.app.*",
            location = SourceLocation("rules.pro", 14),
            memberConditions = listOf(
                KeepMemberCondition(KeepMemberKind.METHODS, "dev.annotation.EntryPoint"),
            ),
        )

        val evidence = KeepRuleRetention.find(graph, listOf(keepRule))

        assertEquals(listOf(owner.id, entryPoint.id), evidence.map { it.nodeId })
    }

    @Test
    fun `requires every conditional member specification`() {
        val owner = node("dev/app/Conditional")
        val firstEntry = GraphNode(
            id = NodeId("method:dev/app/Conditional#first()V"),
            name = "first",
            kind = NodeKind.METHOD,
            annotations = setOf("dev/annotation/First"),
        )
        val graph = CodeGraph(
            nodes = listOf(owner, firstEntry),
            edges = listOf(GraphEdge(owner.id, firstEntry.id, EdgeKind.MEMBER)),
        )
        val keepRule = KeepRule(
            declarationKind = KeepDeclarationKind.CLASS,
            classNamePattern = "dev.app.*",
            location = SourceLocation("rules.pro", 15),
            memberConditions = listOf(
                KeepMemberCondition(KeepMemberKind.METHODS, "dev.annotation.First"),
                KeepMemberCondition(KeepMemberKind.METHODS, "dev.annotation.Missing"),
            ),
        )

        assertEquals(emptyList(), KeepRuleRetention.find(graph, listOf(keepRule)))
    }

    @Test
    fun `rejecting member conditions avoid unnecessary incomplete hierarchy failures`() {
        val graph = graph(
            node("dev/app/Conditional", supertypes = setOf("external/library/Middle")),
        )
        val keepRule = KeepRule(
            declarationKind = KeepDeclarationKind.CLASS,
            classNamePattern = "dev.app.*",
            extendsPattern = "external.library.Target",
            location = SourceLocation("rules.pro", 16),
            memberConditions = listOf(
                KeepMemberCondition(KeepMemberKind.METHODS, "dev.annotation.Missing"),
            ),
        )

        assertEquals(emptyList(), KeepRuleRetention.find(graph, listOf(keepRule)))
    }

    @Test
    fun `matches required and forbidden JVM modifiers`() {
        val graph = graph(
            node("dev/app/FinalEntry", jvmModifiers = setOf(JvmModifier.FINAL)),
            node("dev/app/OpenEntry"),
        )
        val keepRule = KeepRule(
            declarationKind = KeepDeclarationKind.CLASS,
            classNamePattern = "dev.app.*Entry",
            location = SourceLocation("rules.pro", 17),
            requiredJvmModifiers = setOf(JvmModifier.FINAL),
            forbiddenJvmModifiers = setOf(JvmModifier.ABSTRACT),
        )

        val evidence = KeepRuleRetention.find(graph, listOf(keepRule))

        assertEquals(listOf(NodeId("class:dev/app/FinalEntry")), evidence.map { it.nodeId })
    }

    @Test
    fun `ordinary member signature matches name descriptor and JVM visibility`() {
        val owner = node("dev/app/Callback")
        val matching = GraphNode(
            id = NodeId("method:dev/app/Callback#onEvent(Ljava/lang/String;)V"),
            name = "onEvent",
            kind = NodeKind.METHOD,
            jvmSignature = "dev/app/Callback#onEvent(Ljava/lang/String;)V",
            jvmVisibility = Visibility.PUBLIC,
        )
        val overload = GraphNode(
            id = NodeId("method:dev/app/Callback#onEvent(I)V"),
            name = "onEvent",
            kind = NodeKind.METHOD,
            jvmSignature = "dev/app/Callback#onEvent(I)V",
            jvmVisibility = Visibility.PUBLIC,
        )
        val graph = CodeGraph(
            listOf(owner, matching, overload),
            listOf(
                GraphEdge(owner.id, matching.id, EdgeKind.MEMBER),
                GraphEdge(owner.id, overload.id, EdgeKind.MEMBER),
            ),
        )
        val keepRule = KeepRule(
            declarationKind = KeepDeclarationKind.CLASS,
            classNamePattern = "dev.app.Callback",
            location = SourceLocation("rules.pro", 18),
            memberConditions = listOf(
                KeepMemberCondition(
                    kind = KeepMemberKind.METHODS,
                    requiredJvmVisibilities = setOf(Visibility.PUBLIC),
                    namePattern = "onEvent",
                    jvmDescriptor = "(Ljava/lang/String;)V",
                ),
            ),
        )

        assertEquals(listOf(owner.id, matching.id), KeepRuleRetention.find(graph, listOf(keepRule)).map { it.nodeId })
    }

    @Test
    fun `exact member descriptor does not match a node without a JVM signature`() {
        val owner = node("dev/app/Callback")
        val unknownSignature = GraphNode(
            id = NodeId("method:dev/app/Callback#onEvent"),
            name = "onEvent",
            kind = NodeKind.METHOD,
            jvmVisibility = Visibility.PUBLIC,
        )
        val graph = CodeGraph(
            listOf(owner, unknownSignature),
            listOf(GraphEdge(owner.id, unknownSignature.id, EdgeKind.MEMBER)),
        )
        val keepRule = KeepRule(
            declarationKind = KeepDeclarationKind.CLASS,
            classNamePattern = "dev.app.Callback",
            location = SourceLocation("rules.pro", 19),
            memberConditions = listOf(
                KeepMemberCondition(
                    kind = KeepMemberKind.METHODS,
                    namePattern = "onEvent",
                    jvmDescriptor = "(Ljava/lang/String;)V",
                ),
            ),
        )

        assertEquals(emptyList(), KeepRuleRetention.find(graph, listOf(keepRule)))
    }

    @Test
    fun `member modifier conditions require a matching JVM method`() {
        val owner = node("dev/app/NativeBridge")
        val nativeMethod = GraphNode(
            id = NodeId("method:dev/app/NativeBridge#dispatch(J)I"),
            name = "dispatch",
            kind = NodeKind.METHOD,
            jvmModifiers = setOf(JvmModifier.NATIVE),
        )
        val regularMethod = GraphNode(
            id = NodeId("method:dev/app/NativeBridge#fallback(J)I"),
            name = "fallback",
            kind = NodeKind.METHOD,
        )
        val graph = CodeGraph(
            listOf(owner, nativeMethod, regularMethod),
            listOf(
                GraphEdge(owner.id, nativeMethod.id, EdgeKind.MEMBER),
                GraphEdge(owner.id, regularMethod.id, EdgeKind.MEMBER),
            ),
        )
        val keepRule = KeepRule(
            declarationKind = KeepDeclarationKind.CLASS,
            classNamePattern = "dev.app.NativeBridge",
            location = SourceLocation("rules.pro", 20),
            memberConditions = listOf(
                KeepMemberCondition(
                    kind = KeepMemberKind.METHODS,
                    requiredJvmModifiers = setOf(JvmModifier.NATIVE),
                ),
            ),
        )

        assertEquals(listOf(owner.id, nativeMethod.id), KeepRuleRetention.find(graph, listOf(keepRule)).map { it.nodeId })
    }

    @Test
    fun `plain kept members do not make class retention conditional`() {
        val owner = node("dev/app/Controller")
        val constructor = GraphNode(
            id = NodeId("method:dev/app/Controller#<init>()V"),
            name = "<init>",
            kind = NodeKind.CONSTRUCTOR,
            jvmSignature = "dev/app/Controller#<init>()V",
        )
        val method = GraphNode(
            id = NodeId("method:dev/app/Controller#run()V"),
            name = "run",
            kind = NodeKind.METHOD,
            jvmSignature = "dev/app/Controller#run()V",
        )
        val graph = CodeGraph(
            listOf(owner, constructor, method),
            listOf(
                GraphEdge(owner.id, constructor.id, EdgeKind.MEMBER),
                GraphEdge(owner.id, method.id, EdgeKind.MEMBER),
            ),
        )
        val keepRule = KeepRule(
            declarationKind = KeepDeclarationKind.CLASS,
            classNamePattern = "dev.app.Controller",
            location = SourceLocation("rules.pro", 21),
            keptMembers = listOf(
                KeepMemberCondition(KeepMemberKind.CONSTRUCTORS, namePattern = "<init>"),
            ),
        )

        assertEquals(listOf(owner.id, constructor.id), KeepRuleRetention.find(graph, listOf(keepRule)).map { it.nodeId })
    }

    private fun graph(vararg nodes: GraphNode): CodeGraph = CodeGraph(nodes.asList(), emptyList())

    private fun node(
        internalName: String,
        kind: NodeKind = NodeKind.CLASS,
        supertypes: Set<String> = emptySet(),
        annotations: Set<String> = emptySet(),
        jvmModifiers: Set<JvmModifier> = emptySet(),
        sourceVisibility: Visibility = Visibility.PUBLIC,
        jvmVisibility: Visibility = sourceVisibility,
    ): GraphNode = GraphNode(
        id = NodeId("class:$internalName"),
        name = internalName.substringAfterLast('/'),
        kind = kind,
        jvmSignature = internalName,
        visibility = sourceVisibility,
        jvmVisibility = jvmVisibility,
        supertypes = supertypes,
        annotations = annotations,
        jvmModifiers = jvmModifiers,
    )

    private fun rule(
        kind: KeepDeclarationKind,
        pattern: String,
        extendsPattern: String? = null,
        requiredJvmVisibilities: Set<Visibility> = emptySet(),
        line: Int,
    ): KeepRule = KeepRule(
        kind,
        pattern,
        extendsPattern,
        SourceLocation("rules.pro", line),
        requiredJvmVisibilities,
    )

    private fun incompleteHierarchyMessage(line: Int): String =
        "dependency hierarchy is incomplete for keep rule at rules.pro:$line; " +
            "pass every dependency directory or JAR with --classpath"
}
