package dev.kartograph.cli

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.KartographVersion
import dev.kartograph.export.ExternalInputBindingsCodec
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.export.QuerySnapshotSizeException
import dev.kartograph.index.ClassFileIndexer
import dev.kartograph.index.IndexedClasses
import dev.kartograph.index.ProvenanceVerifier
import dev.kartograph.index.RuntimeLimitationScanner
import dev.kartograph.index.SourcePathIndex
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * `kartograph snapshot merge` — 모듈별 snapshot(예: Gradle plugin의 `kartographSnapshot<Variant>`)을 한 snapshot으로 합친다.
 *
 * `routes`·`impact`·`query`는 `--graph-file` 하나만 받는다. 모듈 snapshot 그래프에는 다른 모듈로 가는 참조·상속
 * 간선이 없으므로 문서끼리 합치지 않고, 각 구성원의 검증된 class root·classpath를 함께 다시 인덱싱한다(수동
 * `snapshot --classes`를 여러 번 준 것과 같은 그래프). provenance는 경로만 옮겨 합치므로 합친 snapshot도
 * `verify-snapshot`·`routes --input-bindings`로 신선도를 다시 확인할 수 있다.
 */
internal object SnapshotMergeCommand {
    /** 구성원 하나의 명령행 입력이다. */
    private data class MemberArguments(val directory: String, var graphFile: String? = null, var bindings: String? = null)

    /** 합친 결과와 로컬 연결이다. */
    private data class Merged(val snapshot: QuerySnapshot, val bindings: Map<String, Path>)

    fun run(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        if (arguments == listOf("--help") || arguments == listOf("-h")) { output.print(HELP); return ExitStatus.SUCCESS.code }
        val options = parse(arguments) ?: return usage(error, "invalid snapshot merge arguments; see `kartograph snapshot merge --help`")
        val (values, memberArguments) = options
        if (values["--project"]?.size != 1) return usage(error, "provide exactly one --project")
        if (memberArguments.size < 2) return usage(error, "provide at least two --module groups to merge")
        if (listOf("--scope", "--input-bindings-output", "--snapshot-max-mib").any { (values[it]?.size ?: 0) > 1 }) return usage(error, "duplicate snapshot merge option")
        val scope = values["--scope"]?.single()
        if (scope != null && (scope.length > 200 || !Regex("[A-Za-z0-9_.:-]+").matches(scope))) return usage(error, "scope must be a portable label")
        val limit = SnapshotFiles.limit(values["--snapshot-max-mib"].orEmpty())
            ?: return usage(error, "--snapshot-max-mib must be one integer from 1 to 128")
        return try {
            val project = Path.of(values.getValue("--project").single()).toAbsolutePath().normalize()
            if (!Files.isDirectory(project)) return failure(error, "project root does not exist")
            val members = memberArguments.mapIndexed { index, member -> member(project, index, member, limit.maximumBytes) }
            if (members.map { it.directory.toRealPath() }.distinct().size != members.size) {
                return usage(error, "each --module directory may appear only once")
            }
            // 외부 입력 여부는 구성원 provenance로 미리 알 수 있다. 재인덱싱 뒤에 사용 오류로 버리지 않는다.
            if (values["--input-bindings-output"] == null && members.any { member ->
                    member.snapshot.provenance?.let { provenance -> (provenance.inputs + provenance.witnesses.flatMap { it.inputs + it.outputs })
                        .any { it.path.startsWith("external/") } } == true }) {
                return usage(error, "member snapshots have inputs outside their modules; pass --input-bindings-output <file> to keep the merge verifiable")
            }
            val merged = merge(project, members, scope, "--include-paths" in values, error) ?: return ExitStatus.FAILURE.code
            write(merged, values["--input-bindings-output"]?.single(), values.containsKey("--compact"), limit, output, error)
        } catch (_: InvalidPathException) {
            usage(error, "invalid path")
        } catch (invalid: MergeException) {
            failure(error, invalid.message.orEmpty())
        } catch (_: Exception) {
            failure(error, "unable to merge snapshots; supply readable member snapshots, their module directories and input bindings")
        }
    }

    /** 사용자가 고칠 수 있는 병합 거부 사유다. 절대 경로를 담지 않는다. */
    private class MergeException(message: String) : Exception(message)

    private fun member(project: Path, index: Int, arguments: MemberArguments, maximumBytes: Int): AggregateMember {
        val directory = Path.of(arguments.directory).let { if (it.isAbsolute) it else project.resolve(it) }.toAbsolutePath().normalize()
        if (!Files.isDirectory(directory) || !directory.toRealPath().startsWith(project.toRealPath())) {
            throw MergeException("member ${index + 1}: --module must be an existing directory inside --project")
        }
        val snapshot = SnapshotFiles.read(requireNotNull(arguments.graphFile), maximumBytes)
        val bindings = arguments.bindings?.let { path ->
            ExternalInputBindingsCodec.parse(SnapshotFiles.readText(path, 1024 * 1024)).mapValues { Path.of(it.value) }
        }.orEmpty()
        val prefix = project.toRealPath().relativize(directory.toRealPath()).joinToString("/")
        return AggregateMember(directory, prefix, "member-${index + 1}", snapshot, bindings)
    }

    private fun merge(project: Path, members: List<AggregateMember>, scope: String?, includePaths: Boolean, error: PrintStream): Merged? {
        requireCompatible(members)
        val unverified = members.map { member -> member to verifyMember(member) }
            .filter { (_, result) -> result.status != "matched" }
            .map { (member, result) -> "aggregate-member-unverified: ${member.snapshot.scope}: ${result.reasons.joinToString(";")}" }
        val provenance = SnapshotAggregation.provenance(members)
        val bindings = SnapshotAggregation.bindings(members)
        val label = scope ?: defaultScope(members)
        val before = ProvenanceVerifier.verify(provenance, project, label, bindings)
        if (before.status == "stale") throw MergeException("member inputs changed while merging; rebuild and recapture the member snapshots")
        val indexed = index(members)
        val sources = sources(members)
        val paths = if (includePaths) SourcePathIndex.resolve(indexed.graph, project, sources) else null
        val graph = paths?.let { resolution -> relocate(indexed.graph, resolution.byNodeId) } ?: indexed.graph
        val limitations = SnapshotAggregation.limitations(members,
            RuntimeLimitationScanner.scan(indexed, sources) + paths?.limitations.orEmpty()) + unverified
        // class와 source를 모두 읽은 뒤 다시 비교해, 결과가 provenance가 증명하는 바이트에서만 나왔음을 확인한다.
        val after = ProvenanceVerifier.verify(provenance, project, label, bindings)
        if (after != before) throw MergeException("member inputs changed while merging; rebuild and recapture the member snapshots")
        val first = members.first().snapshot
        val suppressed = members.flatMap { it.snapshot.suppressed }.filterTo(mutableSetOf()) { it in graph.nodes }
        val snapshot = QuerySnapshot(graph, SnapshotAggregation.retention(members), limitations, suppressed,
            first.includePrivateMembers, revision = first.revision, scope = label, provenance = provenance)
        if (unverified.isNotEmpty()) error.println("warning: merged snapshot includes unverified members; see its aggregate-member-unverified limitations")
        return Merged(snapshot, bindings)
    }

    /** 합칠 수 있는 구성원인지 확인한다. 다른 버전·옵션·scope의 사실을 섞지 않는다. */
    private fun requireCompatible(members: List<AggregateMember>) {
        val snapshots = members.map { it.snapshot }
        if (snapshots.any { it.provenance == null || it.scope == null }) throw MergeException("member snapshots must carry provenance and a scope; recapture them with a supported capture path")
        if (snapshots.any { it.toolVersion != KartographVersion.current }) throw MergeException("member snapshots must be captured by kartograph ${KartographVersion.current}; recapture them with this version")
        if (snapshots.map { it.scope }.distinct().size != snapshots.size) throw MergeException("member snapshot scopes must be distinct")
        if (snapshots.map { it.includePrivateMembers }.distinct().size != 1 || snapshots.map { it.revision }.distinct().size != 1) {
            throw MergeException("member snapshots must share includePrivateMembers and revision labels")
        }
        if (snapshots.any { it.processorOutputs.isNotEmpty() || it.processorGenerations.isNotEmpty() || it.provenance!!.memberScopes.isNotEmpty() }) {
            throw MergeException("members with processor observations or already merged snapshots are not supported; merge module captures directly")
        }
        if (snapshots.any { snapshot -> snapshot.provenance!!.witnesses.any { it.compilerEvidence.isNotEmpty() } }) {
            throw MergeException("members with compiler-evidence witnesses are not supported by snapshot merge")
        }
    }

    /** 다시 인덱싱할 바이트가 구성원 capture와 같은지 먼저 확인한다. 외부 입력을 찾지 못하면 합치지 않는다. */
    private fun verifyMember(member: AggregateMember): ProvenanceVerifier.Result {
        val result = ProvenanceVerifier.verify(member.snapshot.provenance, member.directory, member.snapshot.scope, member.bindings)
        if (result.status == "stale" || "missing-external-input" in result.reasons) {
            throw MergeException("member ${member.snapshot.scope} is ${result.status} (${result.reasons.joinToString(";")}); " +
                "rebuild and recapture it, and pass its --input-bindings")
        }
        return result
    }

    /** 구성원 순서와 capture 순서를 그대로 지켜 class root를 함께 인덱싱한다. 중복 class는 앞선 root가 이긴다. */
    private fun index(members: List<AggregateMember>): IndexedClasses {
        fun paths(role: String) = members.flatMap { member ->
            requireNotNull(member.snapshot.provenance).inputs.filter { it.role == role }.map { requireNotNull(member.locate(it.path)) }
        }.distinct()
        val roots = paths("classes")
        val generated = paths("generated-classes")
        return ClassFileIndexer().indexWithObservations(roots, paths("classpath") - roots.toSet(), paths("service-resources"),
            generated.filter(roots::contains))
    }

    /** compiler witness가 기록한 Java/Kotlin 소스다. 경로 해석과 runtime 신선도 관측의 읽기 경계다. */
    private fun sources(members: List<AggregateMember>): List<Path> = members.flatMap { member ->
        requireNotNull(member.snapshot.provenance).witnesses.flatMap { it.inputs }.filter { it.role == "sources" }
            .mapNotNull { member.locate(it.path) }
            .filter { Files.isRegularFile(it) && it.fileName.toString().let { name -> name.endsWith(".kt") || name.endsWith(".java") } }
    }.map { it.toRealPath() }.distinct()

    private fun relocate(graph: CodeGraph, paths: Map<dev.kartograph.core.NodeId, String>): CodeGraph = CodeGraph(graph.nodes.values.map { node ->
        paths[node.id]?.let { path -> node.copy(location = node.location?.copy(path = path)) } ?: node
    }, graph.edges, graph.externalCalls, graph.serviceProviders, graph.enclosures, graph.callbackArguments, graph.parameterUses, graph.lambdaEscapes)

    /** 구성원 scope가 같은 variant로 끝나면 `aggregate:<variant>`를 기본 label로 쓴다. */
    private fun defaultScope(members: List<AggregateMember>): String {
        val variants = members.map { requireNotNull(it.snapshot.scope).substringAfterLast(':') }.distinct()
        if (variants.size != 1 || variants.single().isEmpty()) throw MergeException("member variants differ; pass --scope <label> explicitly")
        return "aggregate:${variants.single()}"
    }

    private fun write(merged: Merged, bindingsOutput: String?, compact: Boolean, limit: SnapshotFileLimit, output: PrintStream, error: PrintStream): Int {
        val content = try {
            QuerySnapshotCodec.render(merged.snapshot, compact, limit.maximumBytes)
        } catch (_: QuerySnapshotSizeException) {
            return failure(error, "merged snapshot exceeds ${limit.maximumMiB} MiB; use --compact or increase --snapshot-max-mib up to 128")
        }
        bindingsOutput?.let { path ->
            val file = Path.of(path).toAbsolutePath()
            Files.createDirectories(requireNotNull(file.parent))
            Files.writeString(file, ExternalInputBindingsCodec.render(merged.bindings.mapValues { it.value.toString() }))
        }
        output.print(content)
        return ExitStatus.SUCCESS.code
    }

    /** `--module`이 새 구성원을 열고 뒤따르는 `--graph-file`·`--input-bindings`가 그 구성원에 속한다. */
    private fun parse(arguments: List<String>): Pair<Map<String, List<String>>, List<MemberArguments>>? {
        val values = mutableMapOf<String, MutableList<String>>()
        val members = mutableListOf<MemberArguments>()
        var index = 0
        while (index < arguments.size) {
            val option = arguments[index]
            if (option in FLAGS) { values.getOrPut(option) { mutableListOf() } += "true"; index++; continue }
            val value = arguments.getOrNull(index + 1)?.takeUnless { it.startsWith("--") } ?: return null
            index += 2
            when (option) {
                "--module" -> members += MemberArguments(value)
                "--graph-file" -> members.lastOrNull()?.takeIf { it.graphFile == null }?.also { it.graphFile = value } ?: return null
                "--input-bindings" -> members.lastOrNull()?.takeIf { it.bindings == null }?.also { it.bindings = value } ?: return null
                in VALUES -> values.getOrPut(option) { mutableListOf() } += value
                else -> return null
            }
        }
        return if (members.all { it.graphFile != null }) values to members else null
    }

    private fun usage(error: PrintStream, message: String): Int { error.println("error: $message"); return ExitStatus.USAGE.code }
    private fun failure(error: PrintStream, message: String): Int { error.println("error: $message"); return ExitStatus.FAILURE.code }

    private val VALUES = setOf("--project", "--scope", "--input-bindings-output", "--snapshot-max-mib")
    private val FLAGS = setOf("--include-paths", "--compact")

    val HELP = """
        Merge per-module snapshots into one snapshot for routes, impact and query.

        Usage:
          kartograph snapshot merge --project <root> \
            --module <module-directory> --graph-file <snapshot.json> [--input-bindings <bindings.json>] \
            --module <module-directory> --graph-file <snapshot.json> [--input-bindings <bindings.json>] ... \
            [--input-bindings-output <file>] [--scope <label>] [--include-paths] [--compact] [--snapshot-max-mib <1..128>]

        Each --module group names the directory a member snapshot was captured for (inside --project), its snapshot
        and the local input bindings written next to it (the Gradle plugin writes build/kartograph/<variant>-input-bindings.json).
        Members must be fresh: a stale member or one whose external inputs cannot be located is refused. The member class
        roots and classpaths are indexed together, so calls, references and inheritance across modules become graph edges;
        JVM USRs do not depend on the module and stay stable. Provenance is combined with paths rebased onto --project,
        so `verify-snapshot` and `routes --input-bindings` can re-check the merged snapshot against the written bindings.
        --input-bindings-output is required when members have inputs outside --project; the file holds absolute paths
        and must not be published. --scope defaults to aggregate:<variant> when all members share a variant.
        --include-paths resolves node source paths against --project, as `snapshot --include-paths` does.
        Retention evidence is combined per member; keep rules and manifests are not re-evaluated across modules.
    """.trimIndent() + "\n"
}
