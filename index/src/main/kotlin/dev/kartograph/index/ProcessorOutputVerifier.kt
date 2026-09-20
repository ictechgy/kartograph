package dev.kartograph.index

import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.ProcessorOutput
import dev.kartograph.core.ProcessorOutputConfiguration
import dev.kartograph.core.ProcessorOutputReceipt
import dev.kartograph.core.ProcessorOutputs
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.InvalidPathException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Base64

/** 명시된 입력·raw 관찰·생성 bytes를 다시 읽어 완료 receipt와 대조한다. 명령은 실행하지 않는다. */
public object ProcessorOutputVerifier {
    /** 같은 설정의 경로 별칭을 중복 관찰로 가져오지 않는다. */
    public fun configurationPaths(paths: List<Path>): List<Path> = readable {
        require(paths.size <= 256) { "too many processor configurations" }
        paths.map { it.toRealPath() }.also {
            require(it.distinct().size == it.size) { "duplicate processor configurations" }
        }
    }
    /** snapshot의 전후 capture가 같은 파일 집합을 추적하도록 입력과 출력 root를 제공한다. */
    public fun trackedFiles(project: Path, config: ProcessorOutputConfiguration, receipt: ProcessorOutputReceipt? = null): List<Path> = readable {
        val root = project.toRealPath()
        require(Path.of(config.project).toRealPath() == root) { "processor configuration belongs to a different project" }
        val controls = listOf(config.token, config.observations, config.receipt).map { locate(root, it) }
        val inputs = config.inputs.map { locate(root, it) } + listOf(Path.of(config.collectorJar).toRealPath(), Path.of(config.processorJar).toRealPath())
        val outputs = config.outputRoots.map { locate(root, it) }
        require(controls.none { control -> (inputs + outputs).any { control.startsWith(it) } }) { "processor controls overlap inputs or output roots" }
        // output root 전체를 추적하면 그 안에 쓰는 snapshot 자신까지 hash하게 된다.
        // receipt가 실제로 관찰한 출력 파일만 추적하며 미관찰 파일의 완전성은 주장하지 않는다.
        (inputs + controls.drop(1) + receipt?.observation?.outputs.orEmpty().map { locate(root, it.path) }).distinct()
    }

    /** stale·partial·다른 scope의 기록은 부분 관찰로 내보내지 않는다. */
    public fun verify(project: Path, scope: String, config: ProcessorOutputConfiguration, receipt: ProcessorOutputReceipt): ProcessorOutputs = readable {
        val root = project.toRealPath()
        trackedFiles(root, config)
        require(scope == config.scope && scope == receipt.scope) { "processor receipt belongs to a different snapshot scope" }
        val contract = ContentFingerprint.values(config.contractValues())
        val current = config.inputs.map { InputFingerprint("processorInput", it, inventory(locate(root, it))) } +
            listOf("collectorJar" to config.collectorJar, "processorJar" to config.processorJar).map { (name, path) ->
                InputFingerprint("processorInput", "external/$name", ContentFingerprint.hash(Path.of(path).toRealPath()))
            }
        val token = ContentFingerprint.values(listOf(contract) + current.flatMap { listOf(it.path, it.sha256) })
        require(receipt.configurationSha256 == contract && receipt.inputs == current && receipt.token == token) { "processor receipt inputs or configuration are stale" }
        val raw = locate(root, config.observations)
        require(Files.isRegularFile(raw, NOFOLLOW_LINKS) && Files.size(raw) <= 16L * 1024 * 1024) { "processor raw evidence is missing or oversized" }
        val bytes = Files.newInputStream(raw, NOFOLLOW_LINKS).use { it.readNBytes(16 * 1024 * 1024 + 1) }
        require(bytes.size <= 16 * 1024 * 1024) { "processor raw evidence is oversized" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val rows = text.reader().buffered().use { it.lineSequence().take(100_007).toList() }
        require(rows.size <= 100_006 && rows.firstOrNull() == "format\tkartograph-processor-outputs\t1") { "invalid processor observation format" }
        val headers = linkedMapOf<String, String>()
        val outputs = mutableListOf<ProcessorOutput>()
        val outputRoots = config.outputRoots.map { locate(root, it) }.toSet()
        rows.drop(1).forEach { line ->
            val parts = line.split('\t')
            if (parts.size == 5 && parts[0] == "output") {
                val output = ProcessorOutput(decode(parts[3]), parts[1], parts[2], parts[4])
                val path = locate(root, output.path)
                require(generateSequence(path) { it.parent }.any { it in outputRoots }) { "processor output escapes declared roots" }
                require(Files.isRegularFile(path, NOFOLLOW_LINKS) && ContentFingerprint.hash(path) ==
                    ContentFingerprint.values(listOf("file", output.sha256))) { "processor output changed" }
                outputs += output
            } else {
                require(parts.size == 2 && parts[0] in setOf("kind", "token", "processor", "processorArtifact", "collectorArtifact") && parts[0] !in headers) { "unknown or duplicate processor observation row" }
                headers[parts[0]] = parts[1]
            }
        }
        val processorArtifact = ContentFingerprint.hash(Path.of(config.processorJar).toRealPath())
        val collectorArtifact = ContentFingerprint.hash(Path.of(config.collectorJar).toRealPath())
        require(headers == mapOf("kind" to config.kind, "token" to token, "processor" to encode(config.processor),
            "processorArtifact" to processorArtifact, "collectorArtifact" to collectorArtifact)) { "processor invocation identity mismatch" }
        val observed = ProcessorOutputs(scope, config.kind, config.processor, processorArtifact, collectorArtifact,
            outputs.sortedBy { it.path }, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
        require(observed == receipt.observation.copy(outputs = receipt.observation.outputs.sortedBy { it.path })) { "processor receipt observations changed" }
        observed
    }

    private inline fun <T> readable(action: () -> T): T = try { action() }
    catch (_: IOException) { throw IllegalArgumentException("processor evidence input is unavailable; recapture with accessible paths") }
    catch (_: InvalidPathException) { throw IllegalArgumentException("processor evidence contains an invalid local path") }

    private fun inventory(path: Path): String {
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) Files.walk(path).use { stream ->
            var files = 0
            stream.forEach {
                require(!Files.isSymbolicLink(it) && (Files.isDirectory(it, NOFOLLOW_LINKS) || Files.isRegularFile(it, NOFOLLOW_LINKS))) { "unsupported processor input" }
                if (Files.isRegularFile(it, NOFOLLOW_LINKS)) require(++files <= 100_000) { "processor input inventory exceeds limit" }
            }
        }
        return ContentFingerprint.hash(path)
    }

    private fun locate(root: Path, name: String): Path {
        val path = root.resolve(name).normalize()
        require(path.startsWith(root)) { "processor path escapes project" }
        var current = path
        while (current.startsWith(root)) {
            require(!Files.isSymbolicLink(current)) { "symbolic processor path" }
            if (current == root) break
            current = current.parent
        }
        return path
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decode(value: String): String = Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8).also {
        require(encode(it) == value) { "noncanonical processor output identity" }
    }
}
