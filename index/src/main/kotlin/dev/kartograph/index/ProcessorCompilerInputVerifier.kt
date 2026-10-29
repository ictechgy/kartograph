package dev.kartograph.index

import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.ProcessorCompilerInputs
import dev.kartograph.core.ProcessorOutputConfiguration
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Base64
import java.io.IOException
import java.nio.file.InvalidPathException

/** Gradle task가 쓴 입력 목록의 로컬 binding과 공개 지문을 구분한다. 명령은 실행하지 않는다. */
public object ProcessorCompilerInputVerifier {
    private data class Entry(val input: InputFingerprint, val path: Path, val kind: String)
    private data class Record(val token: String, val metadata: ProcessorCompilerInputs, val entries: List<Entry>)

    /** 입력의 등장·삭제까지 snapshot provenance가 대조하도록 빈 슬롯도 watch로 보존한다. */
    public fun trackedFiles(project: Path, config: ProcessorOutputConfiguration): List<Pair<String, Path>> {
        val selected = config.compilerInputs ?: return emptyList()
        return listOf("processorCompilerInputs" to project.resolve(selected)) + read(project, config).entries.map { it.input.role to it.path }
    }

    /** 완료 token·관찰 원문·모든 선언 파일 bytes가 일치할 때만 compiler 입력 관찰을 반환한다. */
    public fun verify(project: Path, config: ProcessorOutputConfiguration, token: String, expected: ProcessorCompilerInputs?): ProcessorCompilerInputs? {
        require((config.compilerInputs != null) == (expected != null)) { "processor compiler input evidence is missing or misplaced" }
        if (expected == null) return null
        val record = read(project, config)
        require(record.token == token && record.metadata == expected) { "compiler input invocation changed" }
        record.entries.forEach { entry ->
            when (entry.kind) {
                "missing" -> require(Files.notExists(entry.path, NOFOLLOW_LINKS)) { "previously absent compiler input appeared" }
                "file" -> require(Files.isRegularFile(entry.path, NOFOLLOW_LINKS)) { "compiler input file kind changed" }
                "directory" -> require(Files.isDirectory(entry.path, NOFOLLOW_LINKS)) { "compiler input directory kind changed" }
            }
            val current = if (entry.kind == "missing") ContentFingerprint.values(listOf("missing-file")) else ContentFingerprint.hash(entry.path)
            require(current == entry.input.sha256) { "compiler task input bytes changed" }
        }
        return record.metadata
    }

    private fun read(project: Path, config: ProcessorOutputConfiguration): Record = try { parse(project, config) }
    catch (_: IOException) { throw IllegalArgumentException("compiler input observations cannot be read") }
    catch (_: InvalidPathException) { throw IllegalArgumentException("invalid compiler input binding") }

    private fun parse(project: Path, config: ProcessorOutputConfiguration): Record {
        val root = project.toRealPath()
        val file = root.resolve(requireNotNull(config.compilerInputs))
        require(file.toFile().canonicalFile.toPath() == file && Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= 32L * 1024 * 1024) { "compiler input observations unavailable or oversized" }
        val bytes = Files.newInputStream(file, NOFOLLOW_LINKS).use { it.readNBytes(32 * 1024 * 1024 + 1) }
        require(bytes.size <= 32 * 1024 * 1024) { "compiler input observations oversized" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val rows = text.reader().buffered().use { it.lineSequence().take(100_006).toList() }
        require(rows.size <= 100_005 && rows.firstOrNull() == "format\tkartograph-processor-compiler-inputs\t1") { "invalid compiler input observations" }
        val headers = mutableMapOf<String, String>()
        val entries = mutableListOf<Entry>()
        for (row in rows.drop(1)) {
            val fields = row.split('\t')
            if (fields.size == 5 && fields[0] == "file") {
                val name = decode(fields[1]); val path = Path.of(decode(fields[2])); val kind = fields[3]
                require(kind in setOf("file", "directory", "missing") && path.isAbsolute && path.normalize() == path && path.toFile().canonicalFile.toPath() == path) { "invalid compiler input binding" }
                require(if (name.startsWith("external/")) Regex("external/compiler-input-[0-9]+").matches(name) && !path.startsWith(root)
                    else name.startsWith("project/") && root.resolve(name.removePrefix("project/")).normalize() == path) { "compiler input identity does not match binding" }
                val input = InputFingerprint(if (kind == "missing") "file-watch" else "processorCompilerInput", name, fields[4])
                val controls = listOfNotNull(config.token, config.receipt, config.observations, config.compilerInputs, config.compilerInputs?.plus(".pending"))
                require(controls.none { root.resolve(it).startsWith(path) }) { "compiler controls overlap task inputs" }
                require(when (kind) {
                    "file" -> Files.isRegularFile(path, NOFOLLOW_LINKS)
                    "directory" -> Files.isDirectory(path, NOFOLLOW_LINKS)
                    else -> Files.notExists(path, NOFOLLOW_LINKS)
                }) { "compiler input kind changed" }
                if (kind == "directory") Files.walk(path).use { stream ->
                    var count = 0
                    stream.forEach { child ->
                        require(!Files.isSymbolicLink(child) && (Files.isDirectory(child, NOFOLLOW_LINKS) || Files.isRegularFile(child, NOFOLLOW_LINKS))) { "unsupported compiler input entry" }
                        if (Files.isRegularFile(child, NOFOLLOW_LINKS)) require(++count <= 100_000) { "compiler input inventory exceeds limit" }
                    }
                }
                entries += Entry(input, path, kind)
            } else {
                require(fields.size == 2 && fields[0] in setOf("scope", "task", "token", "propertiesSha256") && fields[0] !in headers) { "unknown or duplicate compiler input observation" }
                headers[fields[0]] = fields[1]
            }
        }
        require(headers.keys == setOf("scope", "task", "token", "propertiesSha256") && headers["scope"] == config.scope && entries.isNotEmpty()) { "compiler input invocation mismatch" }
        val token = headers.getValue("token")
        InputFingerprint("token", "token", token)
        val metadata = ProcessorCompilerInputs(headers.getValue("task"), entries.map { it.input }, headers.getValue("propertiesSha256"),
            ContentFingerprint.values(listOf("gradle-declared-inputs-v1", config.scope, headers.getValue("task"), token,
                headers.getValue("propertiesSha256"), entries.size.toString()) + entries.flatMap { listOf(it.input.path, it.kind, it.input.sha256) }))
        return Record(token, metadata, entries)
    }

    private fun decode(value: String): String {
        val bytes = Base64.getUrlDecoder().decode(value)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value) { "noncanonical compiler input identity" }
        return text
    }
}
