package dev.kartograph.gradle

import dev.kartograph.core.*
import dev.kartograph.export.ExternalInputBindingsCodec
import dev.kartograph.export.GenerationReceiptCodec
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.GeneratedSourceIndexer
import dev.kartograph.index.IndexedClasses
import dev.kartograph.index.ProvenanceVerifier
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

/** 저장 그래프를 대체하지 않고 현재 task의 index에 같은 생성 출처를 다시 연결한다. */
internal class SafeArgsLiveCapture(
    private val project: Path,
    private val scope: String,
    private val snapshotFile: () -> Path,
    private val bindingsFile: () -> Path,
    private val maximumBytes: () -> Int,
    private val current: () -> SafeArgsGenerationLifecycle.Result,
) {
    data class Result(val graph: CodeGraph, val evidence: GenerationEvidence?, val limitations: List<String>,
        val verifyBeforeWrite: () -> Unit = {})
    class Prepared(private val apply: (IndexedClasses, CodeGraph, List<Path>) -> Result) {
        fun attribute(indexed: IndexedClasses, graph: CodeGraph, roots: List<Path>): Result = apply(indexed, graph, roots)
    }

    /** 첫 byte 검사는 indexing 전에 수행하고 이후 검사도 같은 receipt에 결박한다. */
    fun prepare(): Prepared {
        return try {
            val observed = current()
            require(observed.reason == null && observed.receipt != null) { "current generation is unavailable" }
            val snapshotPath = snapshotFile()
            val bindingsPath = bindingsFile()
            val limit = maximumBytes()
            val savedBytes = read(snapshotPath, limit)
            val bindingBytes = read(bindingsPath, 4 * 1024 * 1024)
            val saved = QuerySnapshotCodec.parse(text(savedBytes), limit)
            val evidence = requireNotNull(saved.generationEvidence) { "snapshot generation is unavailable" }
            val provenance = requireNotNull(saved.provenance)
            require(saved.scope == scope && evidence.receipts.size == 1 && evidence.observations.size == 1) { "live generation scope mismatch" }
            val receipt = GenerationReceiptCodec.parse(observed.receipt)
            require(evidence.receipts.single() == receipt) { "current generation differs from snapshot" }
            val observation = evidence.observations.single()
            require(observation.originContext == observed.originContext && observation.currentContext == observed.currentContext &&
                observation.invocation == observed.invocation && observation.currentAttempt == observed.currentAttempt &&
                observation.originBasis == observed.originBasis && observation.currentGenerationStatus == observed.currentGenerationStatus) {
                "current generation observation differs from snapshot"
            }
            val bindings = ExternalInputBindingsCodec.parse(text(bindingBytes)).mapValues { Path.of(it.value) }
            val projectRoot = project.toRealPath()
            fun locate(input: InputFingerprint): Path = (if (input.path.startsWith("external/"))
                requireNotNull(bindings[input.path]) else projectRoot.resolve(input.path)).toRealPath()
            val selected = provenance.inputs.filter { it.role == "classes" }.map(::locate)
            require(selected.size == selected.distinct().size) { "ambiguous witnessed snapshot roots" }
            fun verify() {
                require(current() == observed) { "generation changed during live analysis" }
                require(read(snapshotPath, limit).contentEquals(savedBytes) && read(bindingsPath, 4 * 1024 * 1024).contentEquals(bindingBytes)) {
                    "generation snapshot changed during live analysis"
                }
                require(ProvenanceVerifier.verify(provenance, projectRoot, scope, bindings).status == "matched") {
                    "generation proof inputs changed during live analysis"
                }
            }
            verify()
            Prepared { indexed, graph, roots ->
                try {
                    val liveRoots = roots.map { it.toRealPath() }
                    require(liveRoots.isNotEmpty() && liveRoots.distinct().size == liveRoots.size && liveRoots.all { it in selected }) {
                        "live selected roots are outside the witnessed snapshot"
                    }
                    verify()
                    val attributed = GeneratedSourceIndexer.attribute(projectRoot, indexed, graph, roots, provenance, scope, evidence.receipts, bindings)
                    val liveObservation = observation.copy(currentDeclarationsSha256 = GenerationDigests.declarations(attributed.declarations, provenance))
                    val liveEvidence = GenerationEvidence(attributed.receipts, attributed.declarations, listOf(liveObservation))
                    liveEvidence.requireMatches(attributed.graph, provenance, scope)
                    verify()
                    Result(attributed.graph, liveEvidence, attributed.limitations +
                        "generation-live-observation: ${observation.originBasis}/${observation.currentGenerationStatus}; " +
                        "${attributed.declarations.size} declarations in current task roots; complete snapshot proof revalidated, " +
                        "snapshot roots=${selected.size}, live roots=${roots.size}; scope=$scope; receipt=${GenerationDigests.receipt(receipt)}; " +
                        "liveRevalidated=true; classification only", ::verify)
                } catch (_: java.io.IOException) { unavailable(graph, "proof-input-unavailable") }
                catch (_: IllegalArgumentException) { unavailable(graph, "proof-unverified-or-changed") }
                catch (_: IllegalStateException) { unavailable(graph, "proof-unavailable") }
            }
        } catch (_: java.io.IOException) {
            Prepared { _, graph, _ -> unavailable(graph, "proof-input-unavailable") }
        } catch (_: IllegalArgumentException) {
            Prepared { _, graph, _ -> unavailable(graph, "proof-unverified-or-changed") }
        } catch (_: IllegalStateException) {
            Prepared { _, graph, _ -> unavailable(graph, "proof-unavailable") }
        }
    }

    private fun unavailable(graph: CodeGraph, reason: String) = Result(graph, null, listOf("generation-live-unverified: $reason"))
    private fun read(path: Path, limit: Int): ByteArray {
        require(limit in 1..QuerySnapshotCodec.maximumBytes(QuerySnapshotCodec.MAX_MIB)) { "invalid live proof byte limit" }
        require(Files.size(path) <= limit) { "live proof input exceeds byte limit" }
        return Files.newInputStream(path).use { it.readNBytes(limit + 1) }.also { require(it.size <= limit) { "live proof input grew beyond byte limit" } }
    }
    private fun text(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}
