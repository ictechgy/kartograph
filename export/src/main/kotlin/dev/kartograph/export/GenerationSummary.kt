package dev.kartograph.export

import java.util.Collections

/** 저장 snapshot 전체의 생성 관측만 요약한다. 질의 시 live 검증이나 런타임 사용 주장이 아니다. */
public class GenerationSummary private constructor(
    private val revision: String?, private val totalReceipts: Int, private val totalDeclarations: Int,
    private val states: List<State>, private val rows: List<Row>,
) {
    private data class State(val basis: String, val status: String, val receipts: Int, val declarations: Int)
    private data class Row(val scope: String, val task: String, val variant: String, val receipt: String,
        val invocation: String, val originAttempt: String, val currentAttempt: String?, val basis: String, val status: String, val declarations: Int)

    internal fun value(): Map<String, Any?> = sortedMapOf(
        "version" to 1, "snapshotRevision" to revision,
        "observationTime" to "snapshotCapture", "liveRevalidated" to false,
        "summaryScope" to "entireLoadedSnapshot", "resultSelectionApplied" to false,
        "declarationCoverage" to "admittedGenerationEvidenceOnly",
        "meaning" to "Captured generated-source identity only; not runtime reachability, predicted failures, deletion approval or permission to skip tests.",
        "currentness" to "Generation task status was observed at snapshot capture; this query does not recheck live inputs or task execution.",
        "totalReceipts" to totalReceipts, "returnedReceipts" to rows.size, "omittedReceipts" to totalReceipts - rows.size,
        "totalDeclarations" to totalDeclarations, "declarationsInReturnedReceipts" to rows.sumOf { it.declarations },
        "declarationsInOmittedReceipts" to totalDeclarations - rows.sumOf { it.declarations }, "fullDetails" to "originalSnapshot",
        "rowLimit" to MAX_ROWS, "byteLimit" to MAX_BYTES, "truncated" to (rows.size < totalReceipts),
        "stateCounts" to states.map { sortedMapOf("originBasis" to it.basis, "currentGenerationStatus" to it.status,
            "receiptCount" to it.receipts, "declarationCount" to it.declarations) },
        "receipts" to rows.map { sortedMapOf("scope" to it.scope, "task" to it.task, "variant" to it.variant,
            "receiptSha256" to it.receipt, "invocation" to it.invocation, "originAttempt" to it.originAttempt,
            "currentAttempt" to it.currentAttempt, "originBasis" to it.basis, "currentGenerationStatus" to it.status,
            "declarationCount" to it.declarations) },
    )

    public companion object {
        public const val MAX_ROWS: Int = 32
        public const val MAX_BYTES: Int = 4096

        /** 실제 파일을 읽지 않고 기존 capture의 연결만 확인한 뒤 불변 scalar 요약을 만든다. */
        public fun from(snapshot: QuerySnapshot): GenerationSummary? {
            val evidence = snapshot.generationEvidence ?: return null
            evidence.requireMatches(snapshot.graph, requireNotNull(snapshot.provenance), requireNotNull(snapshot.scope))
            val declarations = evidence.declarations.groupingBy { it.scope to it.generatorTask }.eachCount()
            val receipts = evidence.receipts.associateBy { it.scope to it.task }
            val all = evidence.observations.sortedWith(compareBy({ it.originContext.scope }, { it.originContext.task })).map { observation ->
                val context = observation.originContext
                val receipt = receipts.getValue(context.scope to context.task)
                Row(context.scope, context.task, context.variant, context.receiptSha256, observation.invocation, receipt.attempt,
                    observation.currentAttempt, observation.originBasis, observation.currentGenerationStatus,
                    declarations[context.scope to context.task] ?: 0)
            }
            val states = all.groupBy { it.basis to it.status }.toSortedMap(compareBy({ it.first }, { it.second })).map { (key, values) ->
                State(key.first, key.second, values.size, values.sumOf { it.declarations })
            }
            val prefix = ArrayList<Row>()
            fun candidate() = GenerationSummary(snapshot.revision, all.size, evidence.declarations.size, states,
                Collections.unmodifiableList(ArrayList(prefix)))
            var result = candidate()
            // 고정 metadata와 모든 상태 합계는 항상 남기고, 행만 결정적 prefix로 줄인다.
            boundedJsonValue(result.value(), MAX_BYTES, "")
            for (row in all.take(MAX_ROWS)) {
                prefix += row
                val next = candidate()
                try { boundedJsonValue(next.value(), MAX_BYTES, "") }
                catch (_: QuerySnapshotSizeException) { break }
                result = next
            }
            return result
        }
    }
}
