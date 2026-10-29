package dev.kartograph.index

import dev.kartograph.core.ProcessorDeclaration
import dev.kartograph.core.ProcessorOutputs
import dev.kartograph.core.EdgeKind
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile
import org.objectweb.asm.ClassReader

/** API class 출력과 선택된 class root의 실제 bytes가 같을 때만 JVM 선언 귀속을 보강한다. */
public object ProcessorOutputIndexer {
    /** 첫 class root 우선권을 유지하며 관찰만 추가한다. 그래프/보존/생성물 분류는 변경하지 않는다. */
    public fun attribute(project: Path, indexed: IndexedClasses, classRoots: List<Path>, observations: List<ProcessorOutputs>): List<ProcessorOutputs> =
        try { verifiedAttribute(project, indexed, classRoots, observations) }
        catch (_: java.io.IOException) { throw IllegalArgumentException("processor class inputs are unavailable") }

    private fun verifiedAttribute(project: Path, indexed: IndexedClasses, classRoots: List<Path>, observations: List<ProcessorOutputs>): List<ProcessorOutputs> {
        if (observations.none { it.compilerInputs != null }) return observations
        val members = indexed.graph.edges.filter { it.kind == EdgeKind.MEMBER }.groupBy({ it.source }, { it.target })
        return observations.map { observed ->
            require(observed.declarations.isEmpty()) { "processor declarations must be derived from indexed bytes" }
            if (observed.compilerInputs == null) observed else observed.copy(declarations = observed.outputs.filter { it.kind == "class" && it.observation == "api" }.mapNotNull { output ->
                val file = project.resolve(output.path)
                require(Files.size(file) <= MAXIMUM_CLASS_BYTES) { "processor class output exceeds limit" }
                val bytes = Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAXIMUM_CLASS_BYTES + 1) }
                require(bytes.size <= MAXIMUM_CLASS_BYTES && sha(bytes) == output.sha256) { "processor class output changed" }
                val name = try { ClassReader(bytes).className } catch (_: RuntimeException) { throw IllegalArgumentException("invalid processor class output") }
                require(name.isNotEmpty() && name.split('/').none { it in setOf("", ".", "..") } && '\\' !in name) { "invalid processor JVM identity" }
                val owner = JvmNodeId.classId(name)
                val selected = indexed.selectedRootByNode[owner] ?: return@mapNotNull null
                require(selected in classRoots.indices) { "processor attribution requires original class root ownership" }
                val root = classRoots[selected]
                val matching = if (Files.isDirectory(root)) {
                    val selectedFile = root.resolve("$name.class")
                    Files.isRegularFile(selectedFile) && Files.size(selectedFile) <= MAXIMUM_CLASS_BYTES && CompilerEvidenceIndexer.sourceHash(selectedFile) == output.sha256
                } else ZipFile(root.toFile()).use { archive ->
                    val entry = archive.getEntry("$name.class") ?: return@use false
                    require(entry.size <= MAXIMUM_CLASS_BYTES) { "indexed class entry exceeds limit" }
                    val selectedBytes = archive.getInputStream(entry).use { it.readNBytes(MAXIMUM_CLASS_BYTES + 1) }
                    selectedBytes.size <= MAXIMUM_CLASS_BYTES && sha(selectedBytes) == output.sha256
                }
                if (!matching) return@mapNotNull null
                val symbols = (listOf(owner) + members[owner].orEmpty()).filter { indexed.selectedRootByNode[it] == selected }.distinct().sortedBy { it.value }
                ProcessorDeclaration(output.path, owner, symbols)
            })
        }
    }

    /** 미매핑 class와 관찰하지 않은 source/resource 관계를 명시한다. */
    public fun limitations(observations: List<ProcessorOutputs>): List<String> = if (observations.isEmpty()) emptyList() else buildList {
        add("processor-output-observations: declared-input successful-command metadata; hidden compiler IO and producer authentication are not covered")
        if (observations.any { it.compilerInputs != null }) {
            add("processor-output-attribution: byte-identical API class outputs only; source-to-class and resource relationships are not inferred")
            val missing = observations.filter { it.compilerInputs != null }.sumOf { it.outputs.count { output -> output.kind == "class" } - it.declarations.size }
            if (missing > 0) add("processor-output-unmapped-classes: $missing outputs are absent or shadowed by different indexed bytes")
        }
    }

    private const val MAXIMUM_CLASS_BYTES = 16 * 1024 * 1024
    private fun sha(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
