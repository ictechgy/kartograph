package dev.kartograph.cli

import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.SnapshotProvenance
import dev.kartograph.export.BuildWitnessCodec
import dev.kartograph.index.ContentFingerprint
import dev.kartograph.index.ProvenanceVerifier
import java.io.PrintStream
import java.nio.file.Path

/** 내용 검증을 저장 그래프 질의와 분리해 오프라인 질의의 의미를 보존한다. */
internal object FreshnessCommand {
    fun run(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        if (arguments == listOf("--help")) {
            output.println("Usage: kartograph verify-snapshot --graph-file <file> --project <directory> [--scope <project:variant>] [--input <external/slot=path>]...")
            output.println("Optional ordered comparisons: --classes <root>, --classpath <root>, --artifact <Gradle-task-path>, --compiler <javac|kotlin> (repeatable).")
            output.println("Exit 0: matching compiler evidence; 1: stale or unverified; 2: invalid input; 64: usage error. Saved queries remain offline.")
            return 0
        }
        if (arguments.size % 2 != 0 || arguments.chunked(2).any { it[0] !in setOf("--graph-file", "--project", "--scope", "--input", "--classes", "--classpath", "--artifact", "--compiler") }) return 64
        val options = arguments.chunked(2).groupBy({ it[0] }, { it[1] })
        if (listOf("--graph-file", "--project").any { options[it]?.size != 1 } || (options["--scope"]?.size ?: 0) > 1) return 64
        return try {
            val external = linkedMapOf<String, Path>()
            for (value in options["--input"].orEmpty()) {
                val key = value.substringBefore('=')
                if ('=' !in value || !key.startsWith("external/") || key in external || value.substringAfter('=').isBlank()) return 64
                external[key] = Path.of(value.substringAfter('='))
            }
            val snapshot = SnapshotFiles.read(options.getValue("--graph-file").single())
            val started = System.nanoTime()
            val project = Path.of(options.getValue("--project").single()).toAbsolutePath().normalize()
            val result = ProvenanceVerifier.verify(snapshot.provenance, project, snapshot.scope, external)
            val scopeMismatch = options["--scope"]?.single()?.let { it != snapshot.scope } == true
            val identityMismatch = listOf("artifact", "compiler").filter { key ->
                options["--$key"]?.let { expected -> expected != snapshot.provenance?.witnesses?.map { if (key == "artifact") it.artifact else it.compiler } } == true
            }
            val changedRoots = listOf("classes", "classpath").filter { role ->
                options["--$role"]?.map { value ->
                    val path = (if (role == "classes") Path.of(value) else project.resolve(value)).toAbsolutePath().normalize()
                    path to ContentFingerprint.hash(path)
                }?.let { supplied -> snapshot.provenance?.inputs?.filter { it.role == role }?.let { recorded ->
                    supplied.size != recorded.size || supplied.zip(recorded).any { (current, previous) ->
                        // 미연결 슬롯의 위치는 바뀌었다고 단정하지 않는다. 공급된 바이트 차이는 독립적으로 확인한다.
                        val path = if (previous.path.startsWith("external/")) external[previous.path] else project.resolve(previous.path)
                        current.second != previous.sha256 || path != null && current.first != path.toAbsolutePath().normalize()
                    }
                } } == true
            }
            val status = if (scopeMismatch || changedRoots.isNotEmpty() || identityMismatch.isNotEmpty()) "stale" else result.status
            val reasons = (result.reasons + (if (scopeMismatch) listOf("snapshot-scope-mismatch") else emptyList()) +
                changedRoots.map { "changed-$it-order" } + identityMismatch.map { "build-$it-mismatch" }).distinct().sorted()
            output.println("{\"format\":\"kartograph-freshness\",\"version\":1,\"status\":\"$status\",\"hashNanos\":${System.nanoTime() - started},\"reasons\":[${reasons.joinToString(",") { "\"$it\"" }}]}")
            if (status == "matched") 0 else 1
        } catch (_: Exception) {
            error.println("error: unable to verify snapshot inputs; supply a valid snapshot, project and external input bindings")
            2
        }
    }

    fun capture(project: Path, files: List<Pair<String, Path>>, context: List<String>, witnessPaths: List<Path>): SnapshotProvenance {
        val inputs = files.mapIndexed { index, (role, path) -> ContentFingerprint.capture(project, path, role, "$role-$index") } +
            InputFingerprint("options", "snapshot-options", ContentFingerprint.values(context)) +
            witnessPaths.mapIndexed { index, path -> ContentFingerprint.capture(project, path, "witness", "witness-$index") }
        val witnesses = witnessPaths.map { BuildWitnessCodec.parse(SnapshotFiles.readText(it.toString(), 64 * 1024 * 1024)) }
        return SnapshotProvenance(inputs, witnesses)
    }
}
