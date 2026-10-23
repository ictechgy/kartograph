package dev.kartograph.gradle

import dev.kartograph.core.InputFingerprint
import dev.kartograph.export.McpJsonCodec
import dev.kartograph.index.ContentFingerprint
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** 지원된 실제 task/provider의 바이트를 읽는다. private mapping은 성공 증거와 분리된 입력이다. */
internal class SafeArgsTaskBytes(private val project: Path, private val task: Task) {
    private val type = generateSequence(task.javaClass as Class<*>?) { it.superclass }
        .firstOrNull { it.name == TASK_CLASS } ?: throw IllegalArgumentException("unsupported SafeArgs task")

    data class Inputs(val files: List<InputFingerprint>, val outputRoot: String, val mappingPath: String, val paths: Map<String, Path>) {
        val digest: String get() = ContentFingerprint.values(listOf(outputRoot, mappingPath) + files.flatMap { listOf(it.role, it.path, it.sha256) })
    }
    data class Outputs(val files: List<InputFingerprint>, val mapping: InputFingerprint?, val mappingText: String?) {
        val digest: String get() = ContentFingerprint.values(files.flatMap { listOf(it.path, it.sha256) } +
            listOf(mapping?.path.orEmpty(), mapping?.sha256.orEmpty()))
        val cold: Boolean get() = files.isEmpty() && mapping == null
    }
    data class Source(val input: String, val path: String, val qualifiedName: String, val sha256: String)

    /** 외부 JAR는 실제 CodeSource 파일을 읽고 hash 기반 logical slot으로 내보낸다. basename으로 join하지 않는다. */
    fun inputs(): Inputs {
        val root = project.toRealPath()
        val nav = (get("getNavigationFiles") as FileCollection).files.map { it.toPath() }.sortedBy { portable(root, it) }
        require(nav.size in 1..100_000) { "SafeArgs navigation inventory is empty or excessive" }
        val declared = task.inputs.files.files.map { it.toPath().toRealPath() }.toSet()
        require(declared == nav.map { it.toRealPath() }.toSet()) { "unsupported SafeArgs task file inputs" }
        val generateKotlin = property("getGenerateKotlin")
        require(generateKotlin == false && property("getUseAndroidX") == true) { "only AndroidX Java SafeArgs is supported" }
        val options = listOf("getRFilePackage", "getApplicationId").map { key ->
            val value = property(key)
            require(value is String && value.length <= 1024 && value.none { it.code < 32 || it.code == 127 }) { "unsupported SafeArgs option" }
            value
        } + listOf("true", "false")
        val generator = type.classLoader.loadClass("androidx.navigation.safe.args.generator.NavSafeArgsGenerator")
        val paths = linkedMapOf<String, Path>()
        val artifacts = listOf(type to PLUGIN_SHA, generator to GENERATOR_SHA).map { (clazz, expected) ->
            artifact(clazz, "generatorArtifact", paths).also { require(it.sha256 == expected) { "unsupported SafeArgs generator artifact" } }
        }
        val action = task.actions.singleOrNull { it.javaClass.name == DEFAULT_ACTION }
            ?: throw IllegalArgumentException("unsupported SafeArgs generator action")
        val actionArtifact = artifact(action.javaClass, "generatorActionArtifact", paths)
        requireSupportedActionArtifact(actionArtifact.sha256)
        var total = 0L
        val navigation = nav.map { file ->
            val data = bytes(file, 4 * MIB)
            total += data.size
            require(total <= 64 * MIB) { "SafeArgs navigation bytes exceed limit" }
            InputFingerprint("navigation", portable(root, file), data.sha()).also { paths[it.path] = file }
        }
        return Inputs((navigation + artifacts + actionArtifact + InputFingerprint("generatorOptions", "generation/options",
            ContentFingerprint.values(options))).sortedWith(compareBy({ it.role }, { it.path })),
            portable(root, (get("getOutputDir") as DirectoryProperty).get().asFile.toPath(), allowMissing = true),
            portable(root, (get("getIncrementalFolder") as DirectoryProperty).get().asFile.toPath().resolve("file_mappings.json"), allowMissing = true), paths)
    }

    fun outputs(input: Inputs): Outputs {
        val root = project.toRealPath()
        val output = root.resolve(input.outputRoot)
        val entries = ArrayList<InputFingerprint>()
        var total = 0L
        var visited = 0
        if (Files.exists(output, NOFOLLOW_LINKS)) {
            require(Files.isDirectory(output, NOFOLLOW_LINKS)) { "invalid SafeArgs output directory" }
            Files.walk(output).use { paths -> paths.forEach { file ->
                require(++visited <= 100_000) { "SafeArgs output entries exceed limit" }
                portable(root, file)
                if (!Files.isDirectory(file, NOFOLLOW_LINKS)) {
                    require(entries.size < 100_000) { "SafeArgs output inventory exceeds limit" }
                    val data = bytes(file, 4 * MIB)
                    total += data.size
                    require(total <= 64 * MIB) { "SafeArgs output bytes exceed limit" }
                    entries += InputFingerprint("generatedSource", portable(root, file), data.sha())
                }
            } }
        }
        val mapping = root.resolve(input.mappingPath)
        val data = if (Files.exists(mapping, NOFOLLOW_LINKS)) bytes(mapping, MIB) else null
        if (data != null) portable(root, mapping)
        return Outputs(entries.sortedBy { it.path }, data?.let { InputFingerprint("generationMapping", input.mappingPath, it.sha()) },
            data?.toString(Charsets.UTF_8))
    }

    /** 전체 inventory 중 mapping에 실제 열거된 Java 파일만 생성기 소유 후보로 연결한다. */
    fun sources(input: Inputs, output: Outputs): List<Source> {
        val rows = McpJsonCodec.parse(requireNotNull(output.mappingText) { "SafeArgs mapping is unavailable" }) as? List<*>
            ?: throw IllegalArgumentException("unsupported SafeArgs mapping")
        require(rows.size <= 100_000) { "SafeArgs mapping exceeds limit" }
        val navigation = input.files.filter { it.role == "navigation" }.map { it.path }.toSet()
        val outputs = output.files.associateBy { it.path }
        val seenInputs = mutableSetOf<String>()
        val sources = ArrayList<Source>()
        for (raw in rows) {
            val row = raw as? Map<*, *> ?: throw IllegalArgumentException("invalid SafeArgs mapping row")
            require(row.keys == setOf("navFile", "javaFiles")) { "unsupported SafeArgs mapping fields" }
            val nav = (row["navFile"] as? String)?.replace('\\', '/') ?: throw IllegalArgumentException("invalid SafeArgs input")
            InputFingerprint("navigation", nav, "0".repeat(64))
            require(nav in navigation && seenInputs.add(nav)) { "SafeArgs mapping input is outside navigation inventory" }
            val names = row["javaFiles"] as? List<*> ?: throw IllegalArgumentException("invalid SafeArgs Java mapping")
            require(sources.size.toLong() + names.size <= 100_000) { "SafeArgs source mapping exceeds limit" }
            for (name in names) {
                require(name is String && name.length <= 1024 && QUALIFIED.matches(name)) { "unsupported SafeArgs Java name" }
                val path = "${input.outputRoot}/${name.replace('.', '/')}.java"
                val file = requireNotNull(outputs[path]) { "mapped SafeArgs source is absent" }
                sources += Source(nav, path, name, file.sha256)
            }
        }
        require(seenInputs == navigation && sources.map { it.path }.distinct().size == sources.size) { "incomplete or duplicate SafeArgs source mapping" }
        return sources.sortedBy { it.path }
    }

    private fun get(name: String): Any = type.getMethod(name).invoke(task)
    private fun property(name: String): Any? = (get(name) as Provider<*>).orNull
    private fun artifact(clazz: Class<*>, role: String, paths: MutableMap<String, Path>): InputFingerprint {
        val url = requireNotNull(clazz.protectionDomain.codeSource?.location) { "generator artifact is unavailable" }
        require(url.protocol == "file") { "unsupported generator artifact source" }
        val path = Path.of(url.toURI())
        val data = bytes(path, 32 * MIB)
        val hash = data.sha()
        return InputFingerprint(role, "external/$role-$hash.jar", hash).also { paths[it.path] = path }
    }

    companion object {
        const val TASK_CLASS = "androidx.navigation.safeargs.gradle.ArgumentsGenerationTask"
        const val DEFAULT_ACTION = "org.gradle.api.internal.project.taskfactory.IncrementalTaskAction"
        const val PLUGIN_SHA = "5e585dfe55474ce5361f2759faf9afd4a12a7e6a8259c7371bab1b7e8a846c56"
        const val GENERATOR_SHA = "d4eb204430ce5e350799981cbac0941cea76cf6feeffd959878363bfc13b5c3d"
        const val ACTION_SHA = "470ae06ceef38cf2c69f1a20838bc80ece735bc92c2615f5bad77a50ab379d25"
        // 실제 Gradle 8.13 SDK-less task에서 CodeSource JAR 전체 바이트를 대조한 값이다.
        const val ACTION_813_SHA = "0a69dc9e20abce45bbe11135351dca180dd6b98b77274fd2b347655d0cd6fa41"
        internal fun requireSupportedActionArtifact(sha: String) {
            require(sha == ACTION_SHA || sha == ACTION_813_SHA) { "unsupported Gradle generator action artifact" }
        }
        private const val MIB = 1024 * 1024
        private val QUALIFIED = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
        private fun ByteArray.sha(): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(this))
        private fun bytes(path: Path, maximum: Int): ByteArray {
            require(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) <= maximum) { "generation input missing or exceeds limit" }
            return Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(maximum + 1).also { data ->
                require(data.size <= maximum) { "generation input grew beyond limit" }
            } }
        }
        private fun portable(root: Path, path: Path, allowMissing: Boolean = false): String {
            val normalized = path.toAbsolutePath().normalize()
            require(normalized.startsWith(root) && normalized != root) { "generation file is outside project" }
            var current = root
            root.relativize(normalized).forEach { part ->
                current = current.resolve(part)
                require(!Files.isSymbolicLink(current)) { "generation input must not traverse links" }
            }
            require(allowMissing || Files.exists(normalized, NOFOLLOW_LINKS)) { "generation input is unavailable" }
            if (Files.exists(normalized, NOFOLLOW_LINKS)) require(normalized == normalized.toRealPath()) { "generation input identity changed" }
            return root.relativize(normalized).toString().replace('\\', '/').also {
                InputFingerprint("path", it, "0".repeat(64)); require(it.split('/').none { part -> part == "." })
            }
        }
    }
}
