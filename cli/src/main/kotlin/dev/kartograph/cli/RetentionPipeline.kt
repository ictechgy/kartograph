package dev.kartograph.cli

import dev.kartograph.analysis.DefaultRetention
import dev.kartograph.analysis.KeepRuleRetention
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.analysis.ReachabilityResult
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.Finding
import dev.kartograph.core.KeepRule
import dev.kartograph.core.NodeId
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason
import dev.kartograph.export.FindingConfidence
import dev.kartograph.index.AndroidManifestScanner
import dev.kartograph.index.AndroidXmlScanner
import dev.kartograph.index.ClassFileIndexer
import dev.kartograph.index.KeepRuleScanner
import dev.kartograph.index.RuntimeLimitationScanner
import java.nio.file.Path

/** dead와 why가 같은 입력 옵션 집합을 요구하도록 공유하는 보존 분석 입력이다. */
internal data class RetentionInputs(
    val classRoots: List<Path>,
    val generatedClassRoots: List<Path>,
    val testClassRoots: List<Path>,
    val projectRoot: Path,
    val manifest: Path,
    val resources: Path,
    val namespace: String,
    val keepRules: List<Path>,
    val classpath: List<Path>,
    val serviceResources: List<Path>,
    val includePrivateMembers: Boolean,
)

/** 보존 분석 결과와 소스 파일별로 측정한 미해결 runtime 채널 관측을 함께 운반한다. */
internal data class RetentionAnalysisResult(
    val graph: CodeGraph,
    val reachability: ReachabilityResult,
    val unresolvedChannelsBySource: Map<String, Int>,
    val unmatchedKeepRules: List<KeepRule>,
)

/** dead와 why가 같은 그래프·같은 근거를 만들게 하는 공통 조립 단계다. */
internal object RetentionPipeline {
    fun analyze(inputs: RetentionInputs): RetentionAnalysisResult {
        val indexed = ClassFileIndexer().indexWithObservations(
            inputs.classRoots,
            inputs.classpath,
            inputs.serviceResources,
            inputs.generatedClassRoots,
        )
        val graph = indexed.graph
        val inputEvidence = buildList {
            addAll(AndroidManifestScanner(inputs.projectRoot).scan(inputs.manifest, inputs.namespace))
            addAll(AndroidXmlScanner(inputs.projectRoot).scan(inputs.resources))
        }
        val keepRules = KeepRuleScanner(inputs.projectRoot, inputs.includePrivateMembers)
            .scan(inputs.keepRules)
        val evidence = DefaultRetention.find(
            graph,
            inputEvidence,
            keepRules,
            indexed.hierarchy,
            includePrivateMembers = inputs.includePrivateMembers,
        )
        return RetentionAnalysisResult(
            graph,
            ReachabilityAnalyzer.analyze(graph, evidence),
            RuntimeLimitationScanner.sourceChannelCounts(indexed),
            KeepRuleRetention.unmatched(keepRules, evidence),
        )
    }

    fun markTestOnly(
        findings: List<Finding>,
        graph: CodeGraph,
        classRoots: List<Path>,
        testClassRoots: List<Path>,
    ): List<Finding> {
        if (findings.isEmpty() || testClassRoots.isEmpty()) return findings
        val testReachable = testReachableNodeIds(graph, classRoots, testClassRoots)
        return findings.map { finding -> if (finding.nodeId in testReachable) finding.copy(testOnly = true) else finding }
    }

    fun isTestOnly(graph: CodeGraph, inputs: RetentionInputs, nodeId: NodeId): Boolean =
        inputs.testClassRoots.isNotEmpty() &&
            nodeId in testReachableNodeIds(graph, inputs.classRoots, inputs.testClassRoots)

    /** 위치가 있는 finding을 같은 소스 파일의 측정된 runtime 채널 관측으로 등급화한다. */
    fun confidenceOf(location: dev.kartograph.core.SourceLocation?, channelsBySource: Map<String, Int>): FindingConfidence {
        val sourceFile = location?.path?.substringAfterLast('/') ?: return FindingConfidence.UNMEASURED
        val channels = channelsBySource[sourceFile] ?: return FindingConfidence.UNMEASURED
        return if (channels > 0) FindingConfidence.REVIEW else FindingConfidence.STATIC
    }

    // test→production cross edge를 보존하려면 production과 test root를 함께 index해야 한다.
    // 따로 index하면 combined 조립 시 dangling 제거로 test→production 간선이 유실된다.
    private fun testReachableNodeIds(graph: CodeGraph, classRoots: List<Path>, testClassRoots: List<Path>): Set<NodeId> {
        val combined = ClassFileIndexer().index(classRoots + testClassRoots)
        // seed는 combined에만 있고 production graph에는 없는 노드, 즉 test 전용 노드다.
        // classRoots가 먼저 index되므로 production 노드는 항상 graph.nodes에 있어 seed에서 빠진다.
        // 같은 FQN이 production·test 양쪽에 있으면 첫 root(production) 사실이 우선해 test 사본 간선이 가려질 수 있고,
        // 같은 root를 --classes와 --test-classes 양쪽에 넘기면 seed가 비어 표시 없이 성공한다(문서화된 경계).
        val testSideRoots = combined.nodeIds.filter { it !in graph.nodes }
            .map { RetentionEvidence(it, RetentionReason.RUNTIME_ENTRY_POINT, null) }
        return ReachabilityAnalyzer.analyze(combined, testSideRoots).reachableNodeIds
    }
}
