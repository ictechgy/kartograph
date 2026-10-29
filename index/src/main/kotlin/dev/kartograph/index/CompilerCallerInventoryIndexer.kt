package dev.kartograph.index

import dev.kartograph.core.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/** C1 완료 증거와 같은 task에 속한 전체 source/output accounting을 재검증하는 opt-in importer다. */
public object CompilerCallerInventoryIndexer {
    public fun index(classRoots: List<Path>, classpath: List<Path>, targetJdkHome: Path, documents: List<Path>,
        context: CompilerEvidenceContext): CompilerEvidenceEnrichment {
        require(documents.isNotEmpty() && documents.size <= 256) { "caller inventory requires bounded explicit documents" }
        val inputs = documents.map { it.toRealPath() to BoundedCompilerEvidenceReader.read(it) }
        require(inputs.map { it.first }.distinct().size == inputs.size && inputs.sumOf { it.second.text.length.toLong() } <= 64L * 1024 * 1024) { "duplicate or oversized inventory documents" }
        val inventoryInputs = inputs.filter { it.second.text.startsWith(CompilerCallerInventoryReader.HEADER + "\n") }
        require(inventoryInputs.isNotEmpty()) { "caller inventory document is missing" }
        val others = inputs.filterNot { it in inventoryInputs }
        val previous = CompilerAuthoredCallIndexer.index(classRoots, classpath, targetJdkHome, others.map { it.first }, context)
        val project = context.project.toRealPath()
        fun locate(input: InputFingerprint): Path = requireNotNull(if (input.path.startsWith("external/")) context.externalInputs[input.path]
            else project.resolve(input.path)) { "inventory input binding is missing" }.toRealPath()
        val roots = classRoots.map { it.toRealPath() }
        val observed = ClassFileIndexer(targetJdkHome).indexWithObservations(classRoots, classpath)
        val inventories = inventoryInputs.map { (file, bytes) ->
            val envelope = CompilerCallerInventoryReader.parse(bytes.text, bytes.fingerprint)
            val evidence = envelope.evidence
            val claim = context.provenance.witnesses.filter { witness -> witness.compilerEvidence.any {
                it.role == "compilerEvidence" && locate(it) == file && it.sha256 == bytes.fingerprint
            } }.singleOrNull() ?: throw IllegalArgumentException("inventory requires one unambiguous completed receipt")
            require(CompilerCallerInventoryOptions.isEnabled(claim.inputs) && CompilerAuthoredCallOptions.isEnabled(claim.inputs) &&
                CompilerEvidenceToken.matches(claim) && claim.evidenceToken == evidence.inputToken) { "inventory profile/token differs from completed task" }
            val paired = others.mapNotNull { (path, content) ->
                if (content.text.startsWith(CompilerAuthoredCallReader.HEADER + "\n") && claim.compilerEvidence.any { locate(it) == path && it.sha256 == content.fingerprint })
                    CompilerAuthoredCallReader.parse(content.text, content.fingerprint) to content.fingerprint else null
            }.singleOrNull() ?: throw IllegalArgumentException("inventory requires one paired authored document")
            require(paired.first.evidence.let { it.inputToken == evidence.inputToken && it.collector == evidence.collector &&
                it.compilerVersion == evidence.compilerVersion && it.artifactSha256 == evidence.artifactSha256 }) { "inventory compiler identity differs from authored evidence" }
            val output = project.resolve(envelope.outputRoot).toRealPath()
            require(output.startsWith(project) && claim.outputs.map(::locate) == listOf(output)) { "inventory output differs from task ownership" }
            val root = roots.indexOf(output)
            require(root >= 0 && roots.count { it == output } == 1) { "inventory output is outside selected roots" }
            val sources = evidence.sources.associateBy { it.path }
            require(paired.first.evidence.sources.all { sources[it.path] == it }) { "authored source is absent from caller inventory" }
            val expected = linkedSetOf<Path>()
            var sourceEntries = 0
            val extension = if (claim.compiler == "kotlin") ".kt" else ".java"
            claim.inputs.filter { it.role == "sources" }.map(::locate).forEach { sourceRoot ->
                if (Files.isDirectory(sourceRoot)) Files.walk(sourceRoot).use { paths ->
                    val iterator = paths.iterator()
                    while (iterator.hasNext()) {
                        val path = iterator.next(); require(++sourceEntries <= 200_000 && !Files.isSymbolicLink(path)) { "inventory source tree is unsupported or oversized" }
                        if (Files.isRegularFile(path) && path.fileName.toString().endsWith(extension)) expected.add(path.toRealPath())
                    }
                } else if (sourceRoot.fileName.toString().endsWith(extension)) {
                    require(++sourceEntries <= 200_000) { "inventory source inputs exceed limit" }
                    expected.add(sourceRoot)
                }
            }
            val actualSources = evidence.sources.map { project.resolve(it.path).toRealPath() }.toSet()
            require(actualSources == expected) { "caller inventory is not the exact selected declaration source set" }
            val lengths = sources.mapValues { (_, source) ->
                val path = project.resolve(source.path).toRealPath()
                require(path.startsWith(project) && CompilerEvidenceIndexer.sourceHash(path) == source.sha256) { "inventory source bytes changed" }
                val raw = Files.newInputStream(path).use { it.readNBytes(CompilerEvidenceReader.MAX_BYTES + 1) }
                require(raw.size <= CompilerEvidenceReader.MAX_BYTES) { "inventory source exceeds limit" }
                val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString() } catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("inventory source is not UTF-8") }
                if (claim.compiler == "kotlin") text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').length else text.length
            }
            require(envelope.bodies.all { it.endOffsetUtf16?.let { end -> end <= lengths.getValue(it.file.path) } ?: true }) { "inventory source range exceeds file" }
            verifyOutputs(output, envelope)
            require(envelope.outputs.mapNotNull { it.owner }.all { observed.selectedRootByNode[JvmNodeId.classId(it)] == root }) {
                "caller inventory class is missing or shadowed in selected output"
            }
            require(envelope.methods.all { observed.selectedRootByNode[it.id] == root && it.id in observed.declarationsByRoot[root] }) {
                "caller inventory method is missing or shadowed in selected output"
            }
            val ownedDeclarations = observed.declarationsByRoot[root].filter { it.value.startsWith("method:") }.toSet()
            require(ownedDeclarations == envelope.methods.map { it.id }.toSet()) { "caller inventory omits emitted methods" }
            envelope.bound(claim.scope, claim.artifact, paired.second)
        }
        val graph = previous.result.graph.withCompilerCallerInventories(inventories)
        require(ProvenanceVerifier.verify(context.provenance, context.project, context.scope, context.externalInputs).status == "matched") { "caller inventory inputs changed during import" }
        return previous.copy(result = previous.result.copy(graph = graph))
    }

    internal fun verifyOutputs(root: Path, envelope: CompilerCallerInventoryEnvelope) {
        val actualFiles = linkedMapOf<String, String>()
        val methods = linkedMapOf<NodeId, Pair<Int, Boolean>>()
        val owners = linkedMapOf<String, String?>()
        var total = 0L
        Files.walk(root).use { paths ->
            val iterator = paths.iterator(); var count = 0
            while (iterator.hasNext()) {
                val path = iterator.next()
                require(++count <= 200_000 && !Files.isSymbolicLink(path)) { "inventory output tree is unsupported or oversized" }
                if (Files.isDirectory(path)) continue
                require(Files.isRegularFile(path)) { "inventory output is not a regular file" }
                val bytes = Files.newInputStream(path).use { it.readNBytes(16 * 1024 * 1024 + 1) }
                total += bytes.size
                require(bytes.size <= 16 * 1024 * 1024 && total <= 256L * 1024 * 1024) { "inventory output exceeds limit" }
                val relative = root.relativize(path).toString().replace('\\', '/')
                actualFiles[relative] = CompilerEvidenceIndexer.sourceHash(path)
                owners[relative] = null
                if (relative.endsWith(".class")) {
                    val reader = ClassReader(bytes)
                    require(relative == "${reader.className}.class") { "inventory class owner/path mismatch" }
                    owners[relative] = reader.className
                    reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                            val id = JvmNodeId.methodId(reader.className, name, descriptor)
                            require(methods.size < 200_000 && methods.putIfAbsent(id, access to false) == null) { "duplicate inventory output method" }
                            return object : MethodVisitor(Opcodes.ASM9) {
                                override fun visitCode() { methods[id] = access to true }
                            }
                        }
                    }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                }
            }
        }
        require(actualFiles == envelope.outputs.associate { it.path to it.sha256 } && owners == envelope.outputs.associate { it.path to it.owner }) { "inventory output files differ from completed compiler output" }
        require(methods == envelope.methods.associate { it.id to (it.access to it.hasCode) }) { "inventory method headers differ from emitted bytes" }
    }
}
