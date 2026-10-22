package dev.kartograph.index

import dev.kartograph.core.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.util.Base64

/** raw inventory의 파싱 결과이며 성공 task 검증이나 현재 source 인증을 대신하지 않는다. */
public data class CompilerCallerInventoryEnvelope(
    val evidence: CompilerEvidence,
    val outputRoot: String,
    val bodies: List<CompilerSourceBody>,
    val outputs: List<CompilerInventoryOutput>,
    val methods: List<CompilerInventoryMethod>,
    val evidenceSha256: String,
    val sourceBodyCounts: Map<String, Int>,
) {
    internal fun bound(scope: String, artifact: String, authoredEvidenceSha256: String): CompilerCallerInventory = CompilerCallerInventory(
        scope, artifact, outputRoot, evidence.sources, bodies, outputs, methods,
        if (evidence.collector == "kotlin-constants") CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE else CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE,
        evidence.compilerVersion, evidence.inputToken, evidence.artifactSha256, evidenceSha256, authoredEvidenceSha256, sourceBodyCounts)
}

/** 별도 v1 source/backend inventory를 엄격하고 bounded하게 읽는다. */
public object CompilerCallerInventoryReader {
    public const val HEADER: String = "format\tkartograph-source-caller-inventory\t1"
    public const val PROFILE: String = "source-backend-accounting-v1"
    public fun read(path: Path): CompilerCallerInventoryEnvelope {
        val input = BoundedCompilerEvidenceReader.read(path)
        return parse(input.text, input.fingerprint)
    }
    public fun parse(text: String, evidenceSha256: String): CompilerCallerInventoryEnvelope {
        require(text.length <= CompilerEvidenceReader.MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= CompilerEvidenceReader.MAX_BYTES) { "caller inventory exceeds byte limit" }
        val rows = text.lineSequence().filter { it.isNotEmpty() }.take(CompilerEvidenceReader.MAX_ROWS + 1).toList()
        require(rows.size <= CompilerEvidenceReader.MAX_ROWS && rows.firstOrNull() == HEADER) { "unsupported caller inventory format" }
        val common = mutableListOf("format\tkartograph-compiler-evidence\t1", "unmapped\t0")
        val bodies = linkedMapOf<String, List<String>>()
        val links = linkedMapOf<String, MutableList<NodeId>>()
        val outputs = mutableListOf<CompilerInventoryOutput>()
        val methods = mutableListOf<CompilerInventoryMethod>()
        val bodyCounts = linkedMapOf<String, Int>()
        var root: String? = null
        var profile = false
        for (row in rows.drop(1)) {
            val f = row.split('\t')
            when (f[0]) {
                "collector", "compiler", "token", "artifact", "source" -> common += row
                "profile" -> { require(!profile && f == listOf("profile", PROFILE)) { "unsupported caller inventory profile" }; profile = true }
                "output-root" -> { require(f.size == 2 && root == null) { "invalid inventory root" }; root = decode(f[1]) }
                "body-count" -> { require(f.size == 3 && bodyCounts.putIfAbsent(decode(f[1]), integer(f[2])) == null) { "duplicate source body count" } }
                "body" -> { require(f.size == 8 && bodies.putIfAbsent(f[1], f) == null) { "invalid or duplicate inventory body" } }
                "body-method" -> { require(f.size == 3) { "invalid source/backend link" }; links.getOrPut(f[1]) { mutableListOf() } += NodeId(decode(f[2])) }
                "output" -> { require(f.size == 4) { "invalid inventory output" }; outputs += CompilerInventoryOutput(decode(f[1]), f[2], if (f[3] == "-") null else decode(f[3])) }
                "backend" -> {
                    require(f.size == 7) { "invalid inventory method" }
                    methods += CompilerInventoryMethod(NodeId(decode(f[1])), integer(f[2]), bool(f[3]), enumValueOf<CompilerBackendOrigin>(f[4]),
                        if (f[5] == "-") null else f[5], decode(f[6]))
                }
                else -> throw IllegalArgumentException("unsupported caller inventory row")
            }
        }
        require(profile && root != null && links.keys.all { it in bodies }) { "caller inventory is missing required rows" }
        val evidence = CompilerEvidenceReader.parse(common.joinToString("\n", postfix = "\n"))
        require(evidence.collector in setOf("javac-constants", "kotlin-constants")) { "unsupported inventory collector" }
        val sources = evidence.sources.associateBy { it.path }
        val selected = bodies.map { (key, f) -> CompilerSourceBody(key, requireNotNull(sources[decode(f[2])]) { "inventory body source is missing" },
            position(f[3]), position(f[4]), enumValueOf<CompilerSourceBodyKind>(f[5]), bool(f[6]), decode(f[7]), links[key].orEmpty()) }
        val envelope = CompilerCallerInventoryEnvelope(evidence, root, selected, outputs, methods, evidenceSha256, bodyCounts)
        envelope.bound("unbound", "unbound", "0".repeat(64))
        return envelope
    }
    private fun integer(value: String): Int {
        require(value == "0" || value.matches(Regex("[1-9][0-9]*"))) { "noncanonical inventory integer" }
        return requireNotNull(value.toIntOrNull()) { "inventory integer exceeds range" }
    }
    private fun position(value: String): Int? = if (value == "-1") null else integer(value)
    private fun bool(value: String): Boolean = when (value) { "true" -> true; "false" -> false; else -> throw IllegalArgumentException("invalid inventory boolean") }
    private fun decode(value: String): String {
        require(value.length <= 16_384) { "inventory value exceeds limit" }
        val bytes = Base64.getUrlDecoder().decode(value)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value) { "noncanonical inventory encoding" }
        val decoded = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString() } catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("inventory is not UTF-8") }
        require(decoded.isNotEmpty() && decoded.none { it.code < 32 }) { "invalid inventory value" }
        return decoded
    }
}

/** fresh-output과 전체 source accounting을 지원한 producer의 token-bound opt-in marker다. */
public object CompilerCallerInventoryOptions {
    public fun enabledInput(): InputFingerprint = InputFingerprint("options", "compiler-caller-inventory",
        ContentFingerprint.values(listOf("callerInventory=true", CompilerCallerInventoryReader.PROFILE, "fresh-task-output-v1", "exact-declaration-sources-v1")))
    public fun isEnabled(inputs: List<InputFingerprint>): Boolean =
        inputs.filter { it.role == "options" && it.path == "compiler-caller-inventory" }.singleOrNull() == enabledInput()
}
