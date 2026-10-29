package dev.kartograph.analysis

import dev.kartograph.core.AndroidLayoutDefinition
import dev.kartograph.core.AndroidLayoutInclude
import dev.kartograph.core.AndroidLayoutIncludeKind
import dev.kartograph.core.AndroidResourceBindingEvidence
import dev.kartograph.core.AndroidResourceEvidence
import dev.kartograph.core.AndroidResourceFieldOutcome
import dev.kartograph.core.AndroidResourceKey
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.NodeId
import dev.kartograph.core.SnapshotProvenance
import java.util.Collections

/** 같은 snapshot의 선택적 채널을 보존한다. null은 검사한 빈 inventory와 다르다. */
public class AndroidResourceImpactContext(
    public val facts: AndroidResourceEvidence? = null,
    public val bindings: AndroidResourceBindingEvidence? = null,
    provenance: SnapshotProvenance? = null,
    public val scope: String? = null,
) {
    public val provenance: SnapshotProvenance? = provenance?.copy(
        inputs = resourceImpactList(provenance.inputs),
        witnesses = resourceImpactList(provenance.witnesses.map { witness ->
            witness.copy(inputs = resourceImpactList(witness.inputs), outputs = resourceImpactList(witness.outputs),
                compilerEvidence = resourceImpactList(witness.compilerEvidence))
        }),
        memberScopes = resourceImpactList(provenance.memberScopes),
    )

    /** supplied binding 전체를 질의할 그래프에 다시 검증하며 현재 filesystem은 읽지 않는다. */
    public fun requireMatches(graph: CodeGraph) {
        if (facts != null && scope != null) require(facts.scope == scope) { "resource impact scope mismatch" }
        bindings?.requireMatches(
            requireNotNull(facts) { "resource impact binding requires facts" },
            requireNotNull(provenance) { "resource impact binding requires provenance" },
            graph,
            requireNotNull(scope) { "resource impact binding requires snapshot scope" },
        )
    }
}

/** CLI 예산과 독립된 고정 상한이며 전체 qualifier 묶음을 담지 못하면 seed를 거부한다. */
public data class AndroidResourceImpactLimits(
    val maxFiles: Int = 256,
    val maxResources: Int = 10_000,
    val maxDefinitions: Int = 10_000,
    val maxIncludes: Int = 20_000,
) {
    init {
        require(maxFiles in 1..256 && maxResources in 1..10_000 &&
            maxDefinitions in 1..10_000 && maxIncludes in 1..20_000) { "invalid resource impact limits" }
    }
}

/** ADMITTED도 저장 당시의 제한된 literal include 모델이며 runtime completeness가 아니다. */
public enum class AndroidResourceImpactStatus { ADMITTED, UNAVAILABLE, REFUSED }

/** ABSENT는 승인된 root 안에서 해당 XML 정의가 없다는 사실에만 사용한다. */
public enum class AndroidResourceImpactFileStatus {
    MATCHED, ABSENT, EVIDENCE_UNAVAILABLE, BINDING_UNAVAILABLE, OUTSIDE_ROOT, INVALID_PATH, BUDGET_EXCEEDED,
}

/** 원본 파일 선택과 리소스 키를 연결한다. 같은 이름의 다른 경로를 대신 선택하지 않는다. */
public class AndroidResourceImpactFile internal constructor(
    public val requested: String,
    public val status: AndroidResourceImpactFileStatus,
    definitions: List<AndroidLayoutDefinition> = emptyList(),
) {
    public val definitions: List<AndroidLayoutDefinition> = resourceImpactList(definitions)
    override fun equals(other: Any?): Boolean = other is AndroidResourceImpactFile &&
        requested == other.requested && status == other.status && definitions == other.definitions
    override fun hashCode(): Int = listOf(requested, status, definitions).hashCode()
}

/** 같은 키의 모든 qualifier 후보와 독립된 R field 결과다. overlay winner를 고르지 않는다. */
public class AndroidResourceImpactResource internal constructor(
    public val key: AndroidResourceKey,
    definitions: List<AndroidLayoutDefinition>,
    public val outcome: AndroidResourceFieldOutcome,
) {
    public val definitions: List<AndroidLayoutDefinition> = resourceImpactList(definitions)
    override fun equals(other: Any?): Boolean = other is AndroidResourceImpactResource &&
        key == other.key && definitions == other.definitions && outcome == other.outcome
    override fun hashCode(): Int = listOf(key, definitions, outcome).hashCode()
}

/** 저장 당시 admission을 재현하는 identity다. 현재 build 신선도 주장은 담지 않는다. */
public data class AndroidResourceImpactBinding(
    val resourceFactsSha256: String,
    val producerArtifact: String,
    val producerSha256: String,
    val output: InputFingerprint,
)

/** 파일→리소스/include→field 증거를 JVM 경로와 분리한 한 revision의 불변 선택 결과다. */
public class AndroidResourceImpactSelection internal constructor(
    public val status: AndroidResourceImpactStatus,
    public val scope: String?,
    public val namespace: String?,
    public val binding: AndroidResourceImpactBinding?,
    public val requestedFileCount: Int,
    files: List<AndroidResourceImpactFile>,
    resources: List<AndroidResourceImpactResource>,
    includes: List<AndroidLayoutInclude>,
    public val coverage: String,
    limitations: List<String>,
    public val limits: AndroidResourceImpactLimits,
) {
    public val files: List<AndroidResourceImpactFile> = resourceImpactList(files)
    public val resources: List<AndroidResourceImpactResource> = resourceImpactList(resources)
    public val includes: List<AndroidLayoutInclude> = resourceImpactList(includes)
    public val limitations: List<String> = resourceImpactList(limitations.distinct().sorted())
    public val selectedFields: Set<NodeId> = Collections.unmodifiableSet(
        resources.mapNotNull { it.outcome.field }.toSortedSet(),
    )
    override fun equals(other: Any?): Boolean = other is AndroidResourceImpactSelection &&
        status == other.status && scope == other.scope && namespace == other.namespace && binding == other.binding &&
        requestedFileCount == other.requestedFileCount && files == other.files && resources == other.resources &&
        includes == other.includes && coverage == other.coverage && limitations == other.limitations && limits == other.limits
    override fun hashCode(): Int = listOf(status, scope, namespace, binding, requestedFileCount, files,
        resources, includes, coverage, limitations, limits).hashCode()
}

/** 승인된 정확한 XML 정의에서 기록된 literal include의 역방향 closure만 선택하는 순수 질의다. */
public object AndroidResourceImpact {
    /** manifest 등 기존 retention 파일을 새 layout 채널로 바꾸지 않는다. */
    public fun isResourceFile(file: String, contexts: Iterable<AndroidResourceImpactContext?> = emptyList()): Boolean =
        contexts.any { context -> context?.facts?.definitions?.any { it.file.path == file } == true } ||
            (file.endsWith(".xml") && Regex("layout(?:-[A-Za-z0-9+_-]+)?").matches(
                file.substringBeforeLast('/', "").substringAfterLast('/'),
            ))

    /** context 전체를 검증한 다음 revision 안에서 한 번만 index와 closure를 만든다. */
    public fun select(
        graph: CodeGraph,
        context: AndroidResourceImpactContext?,
        files: List<String>,
        limits: AndroidResourceImpactLimits = AndroidResourceImpactLimits(),
    ): AndroidResourceImpactSelection {
        context?.requireMatches(graph)
        val facts = context?.facts
        val bindings = context?.bindings
        val binding = bindings?.let {
            AndroidResourceImpactBinding(it.resourceFactsSha256, it.producerArtifact, it.producerSha256, it.output)
        }
        val limitations = mutableListOf(
            "resource-impact-currentness: saved resource bindings do not check current files or build freshness",
            "resource-impact-model: exact layout definitions and recorded literal includes are potential impact; runtime inflation and overlay selection are not modeled",
        )
        fun result(
            status: AndroidResourceImpactStatus,
            receipts: List<AndroidResourceImpactFile>,
            resources: List<AndroidResourceImpactResource> = emptyList(),
            includes: List<AndroidLayoutInclude> = emptyList(),
            coverage: String = "partial",
        ) = AndroidResourceImpactSelection(
            status, context?.scope ?: facts?.scope, facts?.namespace, binding, files.size,
            receipts, resources, includes, coverage, limitations, limits,
        )
        fun refuse(receipts: List<AndroidResourceImpactFile>, reason: String): AndroidResourceImpactSelection {
            limitations += "resource-impact-budget: $reason; no resource fields were selected"
            return result(AndroidResourceImpactStatus.REFUSED, receipts.map {
                AndroidResourceImpactFile(it.requested, AndroidResourceImpactFileStatus.BUDGET_EXCEEDED)
            })
        }
        if (files.size > limits.maxFiles) {
            return refuse(files.sorted().take(limits.maxFiles).distinct().map {
                AndroidResourceImpactFile(it, AndroidResourceImpactFileStatus.BUDGET_EXCEEDED)
            }, "${files.size} requested files exceed ${limits.maxFiles}")
        }
        val requested = files.distinct().sorted()
        if (facts == null || bindings == null) {
            val unavailable = if (facts == null) AndroidResourceImpactFileStatus.EVIDENCE_UNAVAILABLE
                else AndroidResourceImpactFileStatus.BINDING_UNAVAILABLE
            limitations += "resource-impact-unavailable: " + if (facts == null) "resource evidence was not captured" else "resource ownership bindings were not captured"
            return result(AndroidResourceImpactStatus.UNAVAILABLE, requested.map {
                AndroidResourceImpactFile(it, if (portableLayoutPath(it)) unavailable else AndroidResourceImpactFileStatus.INVALID_PATH)
            }, coverage = "unknown")
        }
        val byPath = facts.definitions.groupBy { it.file.path }
        val byKey = facts.definitions.groupBy { it.key }
        val byId = facts.definitions.associateBy { it.id }
        val outcomes = bindings.outcomes.associateBy { it.key }
        val reverseIncludes = facts.includes.filter { it.kind == AndroidLayoutIncludeKind.LITERAL }.groupBy { it.target }
        val nonliteral = facts.includes.count { it.kind != AndroidLayoutIncludeKind.LITERAL }
        if (nonliteral > 0) limitations += "resource-impact-include-coverage: $nonliteral nonliteral include site(s) can hide additional parents of changed layouts"
        val receipts = requested.map { file ->
            val definitions = byPath[file].orEmpty()
            val state = when {
                !portableLayoutPath(file) -> AndroidResourceImpactFileStatus.INVALID_PATH
                definitions.isNotEmpty() -> AndroidResourceImpactFileStatus.MATCHED
                facts.roots.any { root ->
                    file.startsWith(root.input.path + "/") && layoutRelativePath(file.removePrefix(root.input.path + "/"))
                } -> AndroidResourceImpactFileStatus.ABSENT
                else -> AndroidResourceImpactFileStatus.OUTSIDE_ROOT
            }
            AndroidResourceImpactFile(file, state, if (state == AndroidResourceImpactFileStatus.MATCHED) definitions else emptyList())
        }
        val keys = linkedSetOf<AndroidResourceKey>()
        val queue = ArrayDeque<AndroidResourceKey>()
        var definitionCount = 0
        fun add(key: AndroidResourceKey): Boolean {
            if (key in keys) return true
            val count = byKey.getValue(key).size
            if (keys.size >= limits.maxResources || count > limits.maxDefinitions - definitionCount) return false
            keys += key
            queue += key
            definitionCount += count
            return true
        }
        for (receipt in receipts) for (definition in receipt.definitions) {
            if (!add(definition.key)) return refuse(receipts, "resource or qualifier definition limit reached")
        }
        val links = linkedMapOf<String, AndroidLayoutInclude>()
        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            for (include in reverseIncludes[key].orEmpty()) {
                if (include.id in links) continue
                if (links.size >= limits.maxIncludes) return refuse(receipts, "literal include limit reached")
                val parent = byId.getValue(include.sourceDefinitionId).key
                if (!add(parent)) return refuse(receipts, "resource or qualifier definition limit reached")
                links[include.id] = include
            }
        }
        val resources = keys.sortedWith(compareBy({ it.namespace }, { it.type }, { it.name })).map { key ->
            AndroidResourceImpactResource(key, byKey.getValue(key), outcomes.getValue(key))
        }
        val unavailable = resources.count { it.outcome.unavailable != null }
        if (unavailable > 0) limitations += "resource-impact-field-coverage: $unavailable selected resource key(s) have unavailable R field bindings"
        // 승인된 inventory도 지원하는 layout/literal include 모델의 부분 coverage만 나타낸다.
        return result(AndroidResourceImpactStatus.ADMITTED, receipts, resources, links.values.sortedBy { it.id })
    }

    private fun portableLayoutPath(path: String): Boolean =
        path.length in 1..4_096 && !path.startsWith('/') && !path.startsWith("external/") &&
            '\\' !in path && ':' !in path && path.none { it.code < 32 } &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
            layoutRelativePath(path.split('/').takeLast(2).joinToString("/"))

    private fun layoutRelativePath(path: String): Boolean =
        Regex("layout(?:-[A-Za-z0-9+_-]+)?/[a-z_][a-z0-9_]{0,127}\\.xml").matches(path)
}

private fun <T> resourceImpactList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
