package dev.kartograph.index

import dev.kartograph.core.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

/**
 * compiler-authored 호출을 명시적 SDK/순서 있는 classpath와 함께 import하는 opt-in 입력 경계다.
 * 임의로 조립한 hierarchy를 받아 완전성을 인증하지 않고 같은 입력에서 selected-root 관측을 직접 만든다.
 */
public object CompilerAuthoredCallIndexer {
    /** 현재 source/input/output/sidecar/SDK를 모두 다시 확인한 뒤 전용 비간선 side table을 반환한다. */
    public fun index(classRoots: List<Path>, classpath: List<Path>, targetJdkHome: Path, explicit: List<Path>,
        context: CompilerEvidenceContext): CompilerEvidenceEnrichment {
        require(explicit.isNotEmpty()) { "authored evidence requires explicit completed documents" }
        require(ProvenanceVerifier.verify(context.provenance, context.project, context.scope, context.externalInputs).status == "matched") {
            "authored evidence requires matched build inputs"
        }
        val project = context.project.toRealPath()
        fun locate(input: InputFingerprint): Path = requireNotNull(if (input.path.startsWith("external/"))
            context.externalInputs[input.path] else project.resolve(input.path)) { "authored input binding is missing" }.toRealPath()
        val roots = classRoots.map { it.toRealPath() }
        val dependencies = classpath.map { it.toRealPath() }
        require(context.provenance.inputs.filter { it.role == "classes" }.map(::locate) == roots) {
            "authored selected root order differs from snapshot provenance"
        }
        val sdk = targetJdkHome.toRealPath()
        // jmods가 분석 header 원천이고 compiler modules가 frontend 원천이다. 전체 SDK를 분석 동안 검증한다.
        val target = TargetJdkHeaders.open(sdk)
        val compilerSdk = sdk.resolve("lib/modules").toRealPath()
        val analysisSdkInputs = listOf(sdk.resolve("jmods").toRealPath(), sdk.resolve("release").toRealPath())
        val indexed = ClassFileIndexer(sdk).indexWithObservations(classRoots, classpath)
        val documents = explicit.map { it.toRealPath() }
        require(documents.distinct().size == documents.size) { "duplicate authored evidence document" }
        val authored = mutableListOf<CompilerAuthoredCallEnvelope>()
        val legacy = mutableListOf<Path>()
        val accepted = mutableListOf<CompilerAuthoredCaller>()
        val selected = mutableSetOf<NodeId>()
        val limitations = mutableListOf<String>()
        for (file in documents) {
            val bytes = BoundedCompilerEvidenceReader.read(file)
            if (!bytes.text.startsWith(CompilerAuthoredCallReader.HEADER + "\n")) { legacy.add(file); continue }
            val envelope = CompilerAuthoredCallReader.parse(bytes.text, bytes.fingerprint)
            authored += envelope
            val evidence = envelope.evidence
            val claims = context.provenance.witnesses.filter { witness -> witness.compilerEvidence.any {
                it.role == "compilerEvidence" && it.sha256 == bytes.fingerprint && locate(it) == file
            } }
            require(claims.size == 1) { "authored evidence requires one unambiguous completed receipt" }
            val claim = claims.single()
            require(CompilerEvidenceToken.matches(claim) && claim.evidenceToken == evidence.inputToken &&
                CompilerAuthoredCallOptions.isEnabled(claim.inputs)) { "authored evidence option/token does not match build" }
            require(claim.compiler == if (evidence.collector == "kotlin-constants") "kotlin" else "javac") { "authored compiler kind mismatch" }
            require(claim.inputs.any { it.role in setOf("compiler", "processor") && it.sha256 == evidence.artifactSha256 }) {
                "authored collector is not a recorded compiler input"
            }
            if (claim.compiler == "kotlin") require(claim.inputs.filter { it.role == "processor" }.all { it.sha256 == evidence.artifactSha256 }) {
                "authored Kotlin profile does not support other compiler plugins"
            }
            require(claim.inputs.filter { it.role == "classpath" }.map(::locate) == dependencies) {
                "authored evidence ordered classpath differs from analysis inputs"
            }
            require(claim.inputs.any { it.role == "compiler" && locate(it) == compilerSdk }) { "authored analysis SDK differs from compiler SDK" }
            require(analysisSdkInputs.all { sdkInput -> claim.inputs.any { it.role == "compilerInput" && locate(it) == sdkInput } }) {
                "authored evidence does not bind the analysis SDK headers"
            }
            val outputRoots = claim.outputs.map(::locate).toSet()
            val ownedRoots = roots.indices.filter { roots[it] in outputRoots }.toSet()
            require(ownedRoots.isNotEmpty()) { "authored output is outside selected class roots" }
            val sourceRoots = claim.inputs.filter { it.role == "sources" }.map(::locate)
            val inventory = evidence.sources.associateBy { it.path }
            val lengths = inventory.mapValues { (_, source) ->
                val path = project.resolve(source.path).toRealPath()
                require(path.startsWith(project) && sourceRoots.any { path == it || Files.isDirectory(it) && path.startsWith(it) }) {
                    "authored source is outside recorded inputs"
                }
                require(CompilerEvidenceIndexer.sourceHash(path) == source.sha256) { "authored source bytes have changed" }
                val raw = Files.newInputStream(path).use { it.readNBytes(CompilerEvidenceReader.MAX_BYTES + 1) }
                require(raw.size <= CompilerEvidenceReader.MAX_BYTES) { "authored source exceeds limit" }
                val chars = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString() } catch (_: java.nio.charset.CharacterCodingException) {
                    throw IllegalArgumentException("authored source requires valid UTF-8")
                }
                if (evidence.collector == "kotlin-constants") chars.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').length else chars.length
            }
            val declarations = envelope.callers.associateBy { it.caller }
            for (row in envelope.callers) {
                require(row.endOffsetUtf16 <= lengths.getValue(row.file.path)) { "authored caller range exceeds compiler source" }
                require(ownedRoots.any { row.caller in indexed.declarationsByRoot[it] }) { "authored caller is absent from compiler output" }
                if (indexed.selectedRootByNode[row.caller] !in ownedRoots) {
                    limitations += "compiler-authored-calls-shadowed"
                    continue
                }
                require(selected.add(row.caller)) { "conflicting authored caller evidence" }
                val reasons = row.reasons.toMutableSet()
                val calls = row.calls.filter { call ->
                    when (call.origin) {
                        AuthoredCallOrigin.PROJECT -> {
                            val targetRow = declarations[call.target]
                            val targetOwned = indexed.selectedRootByNode[call.target] in ownedRoots &&
                                targetRow?.file?.path == call.targetSourcePath
                            if (!targetOwned) reasons += AuthoredCallReason.UNVERIFIED_TARGET_ORIGIN
                            targetOwned
                        }
                        AuthoredCallOrigin.BINARY -> {
                            val value = call.target.value.removePrefix("method:")
                            val owner = value.substringBefore('#')
                            val signature = value.substringAfter('#')
                            val name = signature.substringBefore('(')
                            val descriptor = "(" + signature.substringAfter('(')
                            val header = indexed.hierarchy.declaredMethodsOf(owner)?.filter { it.name == name && it.descriptor == descriptor }
                            val verified = header?.size == 1 && JvmNodeId.classId(owner) !in indexed.graph.nodes
                            if (!verified) reasons += AuthoredCallReason.UNVERIFIED_TARGET_ORIGIN
                            verified
                        }
                    }
                }
                accepted += row.copy(calls = calls, reasons = reasons.sortedBy { it.name },
                    coverage = if (reasons.isEmpty()) AuthoredCallCoverage.COMPLETE else AuthoredCallCoverage.INCOMPLETE)
            }
            if (evidence.unmapped > 0) limitations += "compiler-authored-callers-unmapped: ${evidence.unmapped}"
        }
        require(authored.isNotEmpty()) { "authored evidence document is missing" }
        target.verifyUnchanged()
        val previous = CompilerEvidenceIndexer.enrichWithCallPositions(indexed, classRoots, legacy, context)
        val graph = previous.result.graph.withCompilerAuthoredCalls(accepted)
        require(ProvenanceVerifier.verify(context.provenance, context.project, context.scope, context.externalInputs).status == "matched") {
            "authored evidence inputs changed during import"
        }
        return CompilerEvidenceEnrichment(previous.result.copy(graph = graph), (previous.limitations + limitations).distinct().sorted())
    }
}
