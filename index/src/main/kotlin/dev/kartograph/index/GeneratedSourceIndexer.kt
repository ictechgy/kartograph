package dev.kartograph.index

import dev.kartograph.core.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.zip.ZipFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

/** 성공 generator의 별도 receipt를 실제 compiler source/class 바이트에 연결한다. 런타임 간선은 만들지 않는다. */
public object GeneratedSourceIndexer {
    public data class Result(val graph: CodeGraph, val receipts: List<GenerationReceipt>,
        val declarations: List<GeneratedDeclaration>, val limitations: List<String>)

    /**
     * receipts의 현재 task lifecycle/options/전체 inventory 재검증은 호출 adapter의 책임이다.
     * 이 경계는 exact javac file inventory만 받으며 directory sourcepath와 표시용 SourcePathIndex는 증명으로 쓰지 않는다.
     */
    public fun attribute(project: Path, indexed: IndexedClasses, graph: CodeGraph, classRoots: List<Path>,
        provenance: SnapshotProvenance, scope: String, receipts: List<GenerationReceipt>,
        external: Map<String, Path> = emptyMap()): Result {
        require(receipts.size in 1..256) { "generation receipt count exceeds limit" }
        receipts.forEach { it.requireValid() }
        for (count in listOf(receipts.sumOf { it.inputs.size.toLong() }, receipts.sumOf { it.outputs.size.toLong() },
            receipts.sumOf { it.sources.size.toLong() })) require(count <= GenerationReceipt.MAX_ROWS) { "generation aggregate rows exceed limit" }
        require(receipts.sumOf { r -> r.inputs.count { it.role == "generatorArtifact" || it.role == "generatorActionArtifact" } } <=
            GenerationReceipt.MAX_ARTIFACTS) { "generation artifacts exceed limit" }
        require(provenance.memberScopes.isEmpty() && receipts.all { it.scope == scope }) { "generation scope mismatch" }
        require(graph.nodeIds == indexed.graph.nodeIds) { "generation attribution requires the original indexed nodes" }
        val root = project.toRealPath()
        fun locate(input: InputFingerprint): Path = (if (input.path.startsWith("external/"))
            requireNotNull(external[input.path]) { "generation compiler input binding is missing" } else root.resolve(input.path)).toRealPath()
        val selected = provenance.inputs.filter { it.role == "classes" }.associateBy { locate(it) }
        require(selected.size == provenance.inputs.count { it.role == "classes" }) { "ambiguous selected class root" }
        val roots = classRoots.map { it.toRealPath() }
        val members = graph.edges.filter { it.kind == EdgeKind.MEMBER }.groupBy({ it.source }, { it.target })
        val mappings = receipts.flatMap { receipt -> receipt.sources.map { receipt to it } }
        require(mappings.map { it.second.path }.distinct().size == mappings.size) { "ambiguous generator source ownership" }
        val basenameSet = mappings.mapTo(mutableSetOf()) { it.second.path.substringAfterLast('/') }
        val units = provenance.witnesses.filter { it.scope == scope && it.compiler == "javac" }.map { witness ->
            val files = witness.inputs.filter { it.role == "sources" }
            require(files.size <= GenerationReceipt.MAX_ROWS) { "compiler source inventory exceeds generation budget" }
            witness to files.filter { it.path.substringAfterLast('/') in basenameSet }
        }
        var sourceBytes = 0L
        val sourceCache = mutableMapOf<Path, Source>()
        val verifiedRoots = mutableSetOf<InputFingerprint>()
        fun verifyRoot(input: InputFingerprint) {
            if (verifiedRoots.add(input)) require(ContentFingerprint.hashInput(locate(input), "classes") == input.sha256) {
                "generated compiler or selected class root changed"
            }
        }
        fun source(input: InputFingerprint): Source {
            val file = locate(input)
            val captured = sourceCache.getOrPut(file) {
                val bytes = read(file, MAX_SOURCE_BYTES)
                sourceBytes += bytes.size
                require(sourceBytes <= MAX_TOTAL_BYTES) { "compiler source bytes exceed generation budget" }
                Source(file, sha(bytes), declaredPackage(bytes))
            }
            require(ContentFingerprint.values(listOf("file", captured.hash)) == input.sha256) { "generated compiler source changed" }
            return captured
        }
        val headers = mutableMapOf<Pair<Path, String>, Header?>()
        var classBytes = 0L
        fun header(directory: Path, path: String): Header? = headers.getOrPut(directory to path) {
            val bytes = classBytes(directory, path) ?: return@getOrPut null
            classBytes += bytes.size
            require(classBytes <= MAX_TOTAL_BYTES) { "generated class bytes exceed generation budget" }
            Header(bytes).also { require(it.name + ".class" == path) { "generated class path/header mismatch" } }
        }
        val declarations = ArrayList<GeneratedDeclaration>()
        var declaredSymbols = 0L
        for ((receipt, mapped) in mappings) {
            val file = root.resolve(mapped.path).normalize()
            require(file.startsWith(root) && file == file.toRealPath()) { "generated source is outside project or traverses links" }
            require(sha(read(file, MAX_SOURCE_BYTES)) == mapped.sha256) { "generated source changed after capture" }
            val name = mapped.qualifiedName.replace('.', '/')
            val expectedPackage = mapped.qualifiedName.substringBeforeLast('.', "")
            val possible = units.filter { (witness, inputs) ->
                // sourcepath directory는 실제 compiler unit 목록이 아니므로 포함된 witness를 인증하지 않는다.
                witness.inputs.none { it.role == "sources" && Files.isDirectory(locate(it)) } &&
                    inputs.any { locate(it) == file }
            }
            if (possible.size != 1) continue
            val (witness, inputs) = possible.single()
            val candidates = inputs.filter { it.path.substringAfterLast('/') == file.fileName.toString() }.map(::source)
            if (candidates.any { it.pkg == null } || candidates.filter { it.pkg == expectedPackage }.singleOrNull()?.let {
                    it.path == file && it.hash == mapped.sha256
                } != true) continue
            val owners = indexed.selectedRootByNode.keys.filter { it.value == "class:$name" || it.value.startsWith("class:$name$") }
            for (owner in owners.sorted()) {
                val position = indexed.selectedRootByNode.getValue(owner)
                require(position in roots.indices) { "missing selected class root ownership" }
                val selectedRoot = roots[position]
                val selectedInput = selected[selectedRoot] ?: continue
                verifyRoot(selectedInput)
                val path = owner.value.removePrefix("class:") + ".class"
                val actual = header(selectedRoot, path) ?: continue
                if (actual.source != file.fileName.toString()) continue
                val originalCandidates = witness.outputs.filter { it.role == "classes" }.mapNotNull { input ->
                    verifyRoot(input)
                    val old = header(locate(input), path)
                    if (old != null && old.hash == actual.hash) input else null
                }
                val original = originalCandidates.singleOrNull() ?: continue
                if (original != selectedInput && provenance.transformations.none { transform ->
                        transform.scope == scope && original in transform.originalOutputs && selectedInput in transform.selectedOutputs &&
                            transform.changedClasses.none { it.name == actual.name }
                    }) continue
                // 이름의 '$'만으로 중첩 소유권을 추론하지 않는다. 실제 InnerClasses/enclosing chain이 mapped outer로 닿아야 한다.
                var nested = actual
                val visited = mutableSetOf<String>()
                while (nested.name != name && visited.add(nested.name)) {
                    val parent = nested.innerOwner ?: nested.enclosingOwner ?: break
                    val next = header(selectedRoot, "$parent.class") ?: break
                    if (next.source != actual.source) break
                    nested = next
                }
                if (nested.name != name) continue
                val symbols = (listOf(owner) + members[owner].orEmpty()).filter {
                    indexed.selectedRootByNode[it] == position && NodeAttribute.EXTERNAL_STUB !in graph.nodes.getValue(it).attributes
                }.distinct().sorted()
                if (owner !in symbols) continue
                declaredSymbols += symbols.size
                require(declarations.size < GenerationReceipt.MAX_ROWS && declaredSymbols <= GenerationReceipt.MAX_ROWS) {
                    "generation declarations exceed limit"
                }
                declarations += GeneratedDeclaration(scope, receipt.task, receipt.attempt, witness.artifact, mapped.path,
                    mapped.sha256, owner, symbols, original.path, selectedInput.path, path, actual.hash)
            }
        }
        val admitted = declarations.flatMapTo(mutableSetOf()) { it.symbols }
        var classified = CodeGraph(graph.nodes.values.map { node -> if (node.id !in admitted) node else node.copy(
            synthesized = true, sourceDeclaration = null,
            attributes = node.attributes - NodeAttribute.SOURCE_DECLARATION_LOCATION + NodeAttribute.GENERATED_SOURCE_EVIDENCE,
        ) }, graph.edges, graph.externalCalls, graph.serviceProviders, graph.enclosures, graph.callbackArguments,
            graph.parameterUses, graph.lambdaEscapes)
        if (graph.compilerCallPositionsCaptured) classified = classified.withCompilerCallPositions(graph.locatedCompilerReferences)
        classified = classified.withCompilerAuthoredCalls(graph.compilerAuthoredCallers)
            .withCompilerCallerInventories(graph.compilerCallerInventories).preserveCompilerSourceUsesFrom(graph)
        return Result(classified, receipts, declarations, listOf(
            "generated-source-attribution: verified Java SafeArgs source bytes and byte-identical javac classes; classification only, not runtime reachability",
            "generated-source-unmapped: ${mappings.size - declarations.map { it.source }.distinct().size} source(s) lack an unambiguous compiler/class join",
        ))
    }

    private data class Source(val path: Path, val hash: String, val pkg: String?)
    private class Header(bytes: ByteArray) : ClassVisitor(Opcodes.ASM9) {
        var name = ""; var source: String? = null; var innerOwner: String? = null; var enclosingOwner: String? = null
        val hash = sha(bytes)
        init { ClassReader(bytes).accept(this, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES) }
        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) { this.name = name }
        override fun visitSource(source: String?, debug: String?) { this.source = source }
        override fun visitOuterClass(owner: String?, name: String?, descriptor: String?) { enclosingOwner = owner }
        override fun visitInnerClass(name: String?, outerName: String?, innerName: String?, access: Int) { if (name == this.name) innerOwner = outerName }
    }
    private fun classBytes(root: Path, name: String): ByteArray? = if (Files.isDirectory(root, NOFOLLOW_LINKS)) {
        val file = root.resolve(name).normalize()
        require(file.startsWith(root)) { "generated class escapes selected root" }
        if (!Files.exists(file, NOFOLLOW_LINKS)) null else read(file, MAX_CLASS_BYTES)
    } else ZipFile(root.toFile()).use { archive ->
        val entry = archive.getEntry(name) ?: return@use null
        require(entry.size in 0..MAX_CLASS_BYTES.toLong()) { "generated class entry exceeds budget" }
        archive.getInputStream(entry).use { it.readNBytes(MAX_CLASS_BYTES + 1).also { bytes ->
            require(bytes.size <= MAX_CLASS_BYTES) { "generated class entry expanded beyond budget" }
        } }
    }
    private fun read(path: Path, maximum: Int): ByteArray {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && path.toAbsolutePath().normalize() == path.toRealPath() && Files.size(path) <= maximum) {
            "generation input unavailable or exceeds budget"
        }
        return Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(maximum + 1).also { bytes ->
            require(bytes.size <= maximum) { "generation input grew beyond budget" }
        } }
    }
    private fun declaredPackage(bytes: ByteArray): String? {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        // Unicode escapes alter Java tokenization before comments are removed; this bounded reader does not guess them.
        if (Regex("\\\\u+[0-9a-fA-F]{4}").containsMatchIn(text)) return null
        val masked = maskStringContents(stripComments(text)).removePrefix("\uFEFF").trimStart()
        return if (masked.startsWith("package")) PACKAGE.find(masked)?.groupValues?.get(1)?.replace(Regex("\\s+"), "")
            else if (Regex("^(import|public|final|abstract|class|interface|enum|record|@)\\b").containsMatchIn(masked)) "" else null
    }
    private fun sha(bytes: ByteArray): String = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
    private val PACKAGE = Regex("^package\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_]*)*)\\s*;")
    private const val MAX_SOURCE_BYTES = 4 * 1024 * 1024
    private const val MAX_CLASS_BYTES = 16 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
}
