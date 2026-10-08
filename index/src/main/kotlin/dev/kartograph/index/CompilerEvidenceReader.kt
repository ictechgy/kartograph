package dev.kartograph.index

import dev.kartograph.core.CompilerEvidence
import dev.kartograph.core.CompilerEvidenceSource
import dev.kartograph.core.CompilerReference
import dev.kartograph.core.CompilerReferenceKind
import dev.kartograph.core.NodeId
import dev.kartograph.core.ProcessorGeneration
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

/** 한 번 읽은 bounded raw bytes에서 파생한 strict UTF-8 text와 기존 file content fingerprint다. */
internal class BoundedCompilerEvidenceInput(
    val text: String,
    val fingerprint: String,
)

/** receipt와 parser가 공유하는 내부 file adapter다. 공개 reader ABI에는 포함하지 않는다. */
internal object BoundedCompilerEvidenceReader {
    fun read(file: Path): BoundedCompilerEvidenceInput {
        require(Files.isRegularFile(file) && !Files.isSymbolicLink(file)) { "compiler evidence must be a regular file" }
        val bytes = Files.newInputStream(file).use { it.readNBytes(CompilerEvidenceReader.MAX_BYTES + 1) }
        require(bytes.size <= CompilerEvidenceReader.MAX_BYTES) { "compiler evidence exceeds the byte limit" }
        val raw = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return BoundedCompilerEvidenceInput(strictUtf8(bytes),
            ContentFingerprint.values(listOf("file", raw)))
    }
}

/** 한도가 있는 compiler 원시 문서를 읽는다. 해석 성공과 빌드 증명은 별도 단계다. */
public object CompilerEvidenceReader {
    public const val MAX_BYTES: Int = 16 * 1024 * 1024
    public const val MAX_ROWS: Int = 200_000

    /** 파일이 늘어나거나 손상돼도 무제한 읽기나 부분 문서 수락으로 바꾸지 않는다. */
    public fun read(file: Path): CompilerEvidence {
        val envelope = parseEnvelope(BoundedCompilerEvidenceReader.read(file).text)
        require(envelope.callStats == null) { "extended compiler evidence requires the envelope reader" }
        return envelope.evidence
    }

    /** v1/v2 증거와 additive v3 CALL selector 행을 한 bounded 문서로 읽는다. */
    public fun readEnvelope(file: Path): CompilerEvidenceEnvelope {
        return parseEnvelope(BoundedCompilerEvidenceReader.read(file).text)
    }

    /** 버전·행 종류·개수·중복·인코딩을 검증하며 미지원 필드를 조용히 무시하지 않는다. */
    public fun parse(text: String): CompilerEvidence {
        val envelope = parseEnvelope(text)
        require(envelope.callStats == null) { "extended compiler evidence requires the envelope reader" }
        return envelope.evidence
    }

    /** v3 stats/위치 행까지 보존하면서 v1/v2와 같은 strict 교환 형식 검증을 수행한다. */
    public fun parseEnvelope(text: String): CompilerEvidenceEnvelope {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "compiler evidence exceeds the byte limit" }
        val rows = text.lineSequence().filter(String::isNotEmpty).take(MAX_ROWS + 1).toList()
        require(rows.size <= MAX_ROWS) { "compiler evidence exceeds the row limit" }
        val version = rows.firstOrNull()?.substringAfter("format\tkartograph-compiler-evidence\t", "")
        require(version in setOf("1", "2", "3")) { "unsupported compiler evidence format" }
        val headers = mutableMapOf<String, String>()
        val sources = mutableListOf<CompilerEvidenceSource>()
        val references = mutableListOf<CompilerReference>()
        val generated = mutableListOf<CompilerEvidenceSource>()
        var callStats: CompilerCallPositionStats? = null
        val callPositions = mutableListOf<UnverifiedCompilerCallPosition>()
        val callKeys = mutableSetOf<CallPositionKey>()
        for (row in rows.drop(1)) {
            val fields = row.split('\t')
            when (fields[0]) {
                "collector", "compiler", "token", "artifact", "unmapped" -> {
                    require(fields.size == 2 && fields[0] !in headers) { "invalid compiler evidence header" }
                    headers[fields[0]] = fields[1]
                }
                "source" -> {
                    require(fields.size == 3) { "invalid compiler source inventory row" }
                    val source = CompilerEvidenceSource(decode(fields[1]), fields[2])
                    if (version == "3") requireCanonicalV3SourcePath(source.path)
                    sources += source
                }
                "processor", "processorArtifact" -> {
                    require(version == "2" && fields.size == 2 && fields[0] !in headers) { "invalid processor evidence header" }
                    headers[fields[0]] = if (fields[0] == "processor") decode(fields[1]) else fields[1]
                }
                "generated" -> {
                    require(version == "2" && fields.size == 3) { "invalid processor output row" }
                    generated += CompilerEvidenceSource(decode(fields[1]), fields[2])
                }
                "edge" -> {
                    require(fields.size == 4) { "invalid compiler reference row" }
                    val kind = when (fields[3]) {
                        "constant" -> CompilerReferenceKind.CONSTANT
                        "binding" -> CompilerReferenceKind.BINDING
                        else -> throw IllegalArgumentException("unsupported compiler reference kind")
                    }
                    references += CompilerReference(NodeId(decode(fields[1])), NodeId(decode(fields[2])), kind)
                }
                "callStats" -> {
                    require(version == "3" && fields.size == 5 && callStats == null) { "invalid compiler call position stats row" }
                    callStats = CompilerCallPositionStats(
                        canonicalInt(fields[1]), canonicalInt(fields[2]), canonicalInt(fields[3]), canonicalInt(fields[4]),
                    )
                }
                "call" -> {
                    require(version == "3" && fields.size == 9) { "invalid compiler call position row" }
                    val position = UnverifiedCompilerCallPosition(
                        NodeId(decode(fields[1])), NodeId(decode(fields[2])), CompilerEvidenceSource(decode(fields[3]), fields[4]),
                        canonicalInt(fields[5]), canonicalInt(fields[6]), canonicalInt(fields[7]), canonicalInt(fields[8]),
                    )
                    requireCanonicalV3SourcePath(position.file.path)
                    val key = CallPositionKey(position.source, position.target, position.file.path, position.offsetUtf16)
                    require(callKeys.add(key)) { "duplicate or conflicting compiler call position" }
                    callPositions += position
                }
                else -> throw IllegalArgumentException("unsupported compiler evidence row")
            }
        }
        val processorFormat = version == "2"
        require((headers["collector"] == "javac-processors") == processorFormat) { "compiler evidence version does not match collector" }
        require(headers.keys == setOf("collector", "compiler", "token", "artifact", "unmapped") +
            if (processorFormat) setOf("processor", "processorArtifact") else emptySet()) { "incomplete compiler evidence headers" }
        val unmapped = if (version == "3") canonicalInt(headers.getValue("unmapped")) else
            headers.getValue("unmapped").toIntOrNull()
                ?: throw IllegalArgumentException("invalid unmapped compiler reference count")
        val evidence = CompilerEvidence(headers.getValue("collector"), headers.getValue("compiler"), headers.getValue("token"),
            headers.getValue("artifact"), sources.sortedBy { it.path }, unmapped,
            references.distinct().sortedWith(compareBy({ it.source }, { it.target }, { it.kind.name })),
            if (processorFormat) ProcessorGeneration(headers.getValue("processor"), headers.getValue("processorArtifact"), generated.sortedBy { it.path }) else null)
        if (version == "3") {
            require(evidence.collector in setOf("javac-constants", "kotlin-constants")) { "v3 requires a constants collector" }
            val stats = requireNotNull(callStats) { "v3 requires compiler call position stats" }
            require(stats.emitted == callPositions.size) { "emitted compiler call position count does not match rows" }
            val inventory = evidence.sources.associateBy { it.path }
            callPositions.forEach { position ->
                require(inventory[position.file.path] == position.file) { "compiler call position source does not match inventory" }
            }
        } else {
            require(callStats == null && callPositions.isEmpty()) { "legacy compiler evidence cannot contain call positions" }
        }
        return CompilerEvidenceEnvelope(evidence, callStats, callPositions.sortedWith(CALL_POSITION_COMPARATOR))
    }

    private fun canonicalInt(value: String): Int {
        require(value == "0" || value.matches(CANONICAL_INTEGER)) { "noncanonical compiler evidence integer" }
        return value.toIntOrNull() ?: throw IllegalArgumentException("compiler evidence integer exceeds the supported range")
    }

    private fun requireCanonicalV3SourcePath(path: String) {
        require(path.split('/').none { it == "." }) { "v3 compiler source path is not canonical" }
    }

    private fun decode(value: String): String {
        val bytes = try { Base64.getUrlDecoder().decode(value) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("invalid compiler evidence encoding")
        }
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value) { "noncanonical compiler evidence encoding" }
        return strictUtf8(bytes).also { require(it.isNotEmpty() && it.none { char -> char.code < 32 }) { "invalid compiler evidence value" } }
    }

    private data class CallPositionKey(val source: NodeId, val target: NodeId, val path: String, val offset: Int)

    private val CALL_POSITION_COMPARATOR = compareBy<UnverifiedCompilerCallPosition>(
        { it.source }, { it.target }, { it.file.path }, { it.offsetUtf16 }, { it.endOffsetUtf16 },
        { it.line }, { it.column }, { it.file.sha256 },
    )
    private val CANONICAL_INTEGER = Regex("[1-9][0-9]*")
}

private fun strictUtf8(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: java.nio.charset.CharacterCodingException) {
    throw IllegalArgumentException("compiler evidence is not valid UTF-8")
}
