package dev.kartograph.index

import dev.kartograph.core.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.util.Base64

/** 별도 authored v1 문서다. rows의 fingerprint는 parser 입력의 식별이며 인증은 완료 receipt가 담당한다. */
public data class CompilerAuthoredCallEnvelope(
    val evidence: CompilerEvidence,
    val callers: List<CompilerAuthoredCaller>,
)

/** 기존 v1/v2/v3 reader가 authored completeness를 잘못 수락하지 않게 분리한 strict reader다. */
public object CompilerAuthoredCallReader {
    public const val HEADER: String = "format\tkartograph-authored-source-calls\t1"
    public const val PROFILE: String = "ordinary-body-v1"

    public fun read(path: Path): CompilerAuthoredCallEnvelope {
        val input = BoundedCompilerEvidenceReader.read(path)
        return parse(input.text, input.fingerprint)
    }

    public fun parse(text: String, evidenceSha256: String): CompilerAuthoredCallEnvelope {
        require(text.length <= CompilerEvidenceReader.MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= CompilerEvidenceReader.MAX_BYTES) {
            "authored evidence exceeds byte limit"
        }
        val rows = text.lineSequence().filter { it.isNotEmpty() }.take(CompilerEvidenceReader.MAX_ROWS + 1).toList()
        require(rows.size <= CompilerEvidenceReader.MAX_ROWS && rows.firstOrNull() == HEADER) { "unsupported authored evidence format" }
        val common = mutableListOf("format\tkartograph-compiler-evidence\t1")
        val callerRows = linkedMapOf<NodeId, List<String>>()
        val calls = linkedMapOf<NodeId, MutableList<CompilerAuthoredCall>>()
        var profile = false
        for (row in rows.drop(1)) {
            val fields = row.split('\t')
            when (fields[0]) {
                "collector", "compiler", "token", "artifact", "source" -> common += row
                "unmapped" -> { require(fields.size == 2) { "invalid authored unmapped row" }; integer(fields[1]); common += row }
                "profile" -> { require(!profile && fields == listOf("profile", PROFILE)) { "unsupported authored syntax profile" }; profile = true }
                "caller" -> {
                    require(fields.size == 9) { "invalid authored caller row" }
                    require(callerRows.putIfAbsent(NodeId(decode(fields[1])), fields) == null) { "duplicate authored caller coverage" }
                }
                "authored" -> {
                    require(fields.size == 7) { "invalid authored call row" }
                    val caller = NodeId(decode(fields[1]))
                    calls.getOrPut(caller) { mutableListOf() } += CompilerAuthoredCall(NodeId(decode(fields[2])),
                        enumValueOf<AuthoredCallOrigin>(fields[3]), if (fields[4] == "-") null else decode(fields[4]),
                        integer(fields[5]), integer(fields[6]))
                }
                else -> throw IllegalArgumentException("unsupported authored evidence row")
            }
        }
        require(profile && calls.keys.all(callerRows::containsKey)) { "authored evidence has missing coverage" }
        val evidence = CompilerEvidenceReader.parse(common.joinToString("\n", postfix = "\n"))
        require(evidence.collector in setOf("kotlin-constants", "javac-constants")) { "unsupported authored collector" }
        require(evidence.collector != "kotlin-constants" || evidence.compilerVersion == "2.4.20") { "unsupported authored Kotlin compiler" }
        require(evidence.collector != "javac-constants" || evidence.compilerVersion.substringBefore('.') in setOf("17", "21")) { "unsupported authored javac compiler" }
        require(evidence.sources.isNotEmpty() && evidence.sources.all { SourceDeclarationEvidence.isPortablePath(it.path) }) {
            "authored source inventory is missing or noncanonical"
        }
        val inventory = evidence.sources.associateBy { it.path }
        val basis = if (evidence.collector == "kotlin-constants") CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE
            else CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE
        val callers = callerRows.map { (caller, fields) ->
            val source = requireNotNull(inventory[decode(fields[2])]) { "authored caller source is absent from inventory" }
            val reasons = if (fields[8] == "NONE") emptyList() else fields[8].split(',').map { enumValueOf<AuthoredCallReason>(it) }
            val selected = calls[caller].orEmpty()
            require(selected.all { it.targetSourcePath == null || it.targetSourcePath in inventory }) { "authored target source is absent from inventory" }
            CompilerAuthoredCaller(caller, source, integer(fields[3]), integer(fields[4]), basis, evidence.compilerVersion,
                integer(fields[6]), integer(fields[7]), enumValueOf<AuthoredCallCoverage>(fields[5]), reasons, selected,
                evidence.inputToken, evidence.artifactSha256, evidenceSha256)
        }.sortedBy { it.caller }
        return CompilerAuthoredCallEnvelope(evidence, callers)
    }

    private fun integer(value: String): Int {
        require(value == "0" || value.matches(Regex("[1-9][0-9]*"))) { "noncanonical authored integer" }
        return requireNotNull(value.toIntOrNull()) { "authored integer exceeds range" }
    }
    private fun decode(value: String): String {
        require(value.length <= 16_384) { "authored value exceeds limit" }
        val bytes = Base64.getUrlDecoder().decode(value)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value) { "noncanonical authored encoding" }
        val result = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString() } catch (_: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("authored evidence is not UTF-8")
        }
        require(result.isNotEmpty() && result.none { it.code < 32 }) { "invalid authored value" }
        return result
    }
}

/** 완료 producer가 새 profile 옵션과 엄격한 입력 layout을 확인한 marker다. */
public object CompilerAuthoredCallOptions {
    public fun enabledInput(): InputFingerprint = InputFingerprint("options", "compiler-authored-calls",
        ContentFingerprint.values(listOf("authoredCalls=true", CompilerAuthoredCallReader.PROFILE, "utf8-native-sdk-v1")))
    public fun isEnabled(inputs: List<InputFingerprint>): Boolean =
        inputs.filter { it.role == "options" && it.path == "compiler-authored-calls" }.singleOrNull() == enabledInput()
}
