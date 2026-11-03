package dev.kartograph.cli

import dev.kartograph.analysis.DefaultRetention
import dev.kartograph.analysis.InputHints
import dev.kartograph.analysis.KeepRuleRetention
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.analysis.ReachabilityResult
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.Finding
import dev.kartograph.core.InputHint
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
    val externalRetentions: List<RetentionEvidence> = emptyList(),
    val externalRetentionContexts: List<dev.kartograph.export.ExternalRetentionDocumentContext> = emptyList(),
    val resourceRoots: List<Path> = listOf(resources),
    val lenientKeepRules: Boolean = false,
    val bindingOverlayRoots: List<Path> = emptyList(),
    val bindingLimit: Int = 1_000,
)

/** 보존 분석 결과와 소스 파일별로 측정한 미해결 runtime 채널 관측을 함께 운반한다. */
internal data class RetentionAnalysisResult(
    val graph: CodeGraph,
    val reachability: ReachabilityResult,
    val unresolvedChannelsBySource: Map<String, Int>,
    val unmatchedKeepRules: List<KeepRule>,
    val inputHints: List<InputHint>,
    val externalRetentionContexts: List<dev.kartograph.export.ExternalRetentionDocumentContext> = emptyList(),
    val keepDiagnostics: List<String> = emptyList(),
    val sourceDiagnostics: List<String> = emptyList(),
    val sourcePaths: Map<NodeId, String> = emptyMap(),
    val bindingReview: dev.kartograph.analysis.AndroidBindingReviewResult? = null,
)

/** dead와 why가 같은 그래프·같은 근거를 만들게 하는 공통 조립 단계다. */
internal object RetentionPipeline {
    fun analyze(inputs: RetentionInputs): RetentionAnalysisResult {
        val indexed = ClassFileIndexer(callbackFacts = false).indexWithObservations(
            inputs.classRoots,
            inputs.classpath,
            inputs.serviceResources,
            inputs.generatedClassRoots,
        )
        val paths = dev.kartograph.index.SourcePathIndex.resolve(indexed.graph, inputs.projectRoot)
        val graph = dev.kartograph.index.SourceDeclarationLocations.enrich(indexed.graph, inputs.projectRoot, paths.byNodeId, false)
        ExternalRetentionFiles.requireMatches(graph, inputs.externalRetentions, inputs.externalRetentionContexts)
        val manifestEvidence = AndroidManifestScanner(inputs.projectRoot).scan(inputs.manifest, inputs.namespace)
        val inputEvidence = buildList {
            addAll(manifestEvidence)
            inputs.resourceRoots.distinct().sorted().forEach { addAll(AndroidXmlScanner(inputs.projectRoot, null, inputs.namespace).scan(it)) }
            addAll(inputs.externalRetentions)
        }
        val scanner = KeepRuleScanner(inputs.projectRoot, inputs.includePrivateMembers, inputs.lenientKeepRules)
        val keepRules = scanner.scan(inputs.keepRules)
        val evidence = DefaultRetention.find(
            graph,
            inputEvidence,
            keepRules,
            indexed.hierarchy,
            includePrivateMembers = inputs.includePrivateMembers,
            lenientKeepRules = inputs.lenientKeepRules,
        )
        val reachability = ReachabilityAnalyzer.analyze(graph, evidence)
        val bindings = dev.kartograph.analysis.AndroidBindingReview.analyze(graph, indexed.hierarchy,
            dev.kartograph.index.AndroidBindingScanner.scan(graph, inputs.classRoots, inputs.resourceRoots, inputs.projectRoot,
                inputs.bindingOverlayRoots), reachability, inputs.bindingLimit)
        return RetentionAnalysisResult(
            graph,
            reachability,
            RuntimeLimitationScanner.sourceChannelCounts(indexed),
            KeepRuleRetention.unmatched(keepRules, evidence),
            InputHints.detect(
                keepRuleInputs = inputs.keepRules.size,
                classpathInputs = inputs.classpath.size,
                manifestEvidenceCount = manifestEvidence.size,
                graph = graph,
            ) + if (evidence.any { it.reason == RetentionReason.UNKNOWN_KEEP_RULE }) listOf(InputHint.UNKNOWN_KEEP_RULES) else emptyList(),
            inputs.externalRetentionContexts,
            scanner.diagnostics,
            paths.limitations + bindings.diagnostics,
            paths.byNodeId,
            bindings,
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

    /** [FindingConfidences.of]에 위임한다. CLI 명령과 Gradle plugin이 같은 규칙을 쓴다. */
    fun confidenceOf(location: dev.kartograph.core.SourceLocation?, channelsBySource: Map<String, Int>): FindingConfidence =
        dev.kartograph.export.FindingConfidences.of(location, channelsBySource)

    /** [FindingConfidences.of]에 위임한다. 런타임 관측 class면 runtime-observed로 승격한다. */
    fun confidenceOf(
        finding: Finding,
        graph: CodeGraph,
        channelsBySource: Map<String, Int>,
        observedClasses: Set<String>,
    ): FindingConfidence = dev.kartograph.export.FindingConfidences.of(finding, graph, channelsBySource, observedClasses)

    // test→production cross edge를 보존하려면 production과 test root를 함께 index해야 한다.
    // 따로 index하면 combined 조립 시 dangling 제거로 test→production 간선이 유실된다.
    private fun testReachableNodeIds(graph: CodeGraph, classRoots: List<Path>, testClassRoots: List<Path>): Set<NodeId> {
        // production root를 먼저 index해야 production 노드가 test 전용 seed에서 빠진다. 판정 정책은 analysis가 소유한다.
        val combined = ClassFileIndexer(callbackFacts = false).index(classRoots + testClassRoots)
        return dev.kartograph.analysis.TestOnlyReachability.reachableFromTestSide(graph, combined)
    }
}
