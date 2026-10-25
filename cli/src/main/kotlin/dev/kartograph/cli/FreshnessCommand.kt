package dev.kartograph.cli

import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.SnapshotProvenance
import dev.kartograph.export.BuildWitnessCodec
import dev.kartograph.export.ExternalInputBindingsCodec
import dev.kartograph.index.CaptureInput
import dev.kartograph.index.ContentFingerprint
import java.io.PrintStream
import java.nio.file.Path

/** 내용 검증을 저장 그래프 질의와 분리해 오프라인 질의의 의미를 보존한다. */
internal object FreshnessCommand {
    fun run(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        if (arguments == listOf("--help")) {
            output.println("Usage: kartograph verify-snapshot --graph-file <file> --project <directory> [--snapshot-max-mib <1..1024>] [--scope <project:variant>] [--input <external/slot=path>]...")
            output.println("Optional ordered comparisons: --classes <root>, --classpath <root>, --artifact <Gradle-task-path>, --compiler <javac|kotlin> (repeatable).")
            output.println("--input-bindings <file> reads a generated local binding document; do not publish this file with snapshots.")
            output.println("--snapshot-max-mib bounds the saved snapshot read to 1..1024 MiB (default 64).")
            output.println("Exit 0: matching compiler evidence; 1: stale or unverified; 2: invalid input; 64: usage error. Saved queries remain offline.")
            return 0
        }
        argumentProblem(arguments)?.let { return usage(error, it) }
        val options = arguments.chunked(2).groupBy({ it[0] }, { it[1] })
        if (listOf("--graph-file", "--project").any { options[it]?.size != 1 }) {
            return usage(error, "--graph-file and --project are required exactly once")
        }
        listOf("--scope", "--input-bindings", "--snapshot-max-mib").firstOrNull { (options[it]?.size ?: 0) > 1 }
            ?.let { return usage(error, "duplicate $it; give it at most once") }
        val snapshotLimit = SnapshotFiles.limit(options["--snapshot-max-mib"].orEmpty())
            ?: return usage(error, "--snapshot-max-mib must be one integer from 1 to 1024")
        return try {
            val explicit = try { inputBindings(options["--input"].orEmpty()) } catch (_: IllegalArgumentException) {
                return usage(error, "--input must be external/<slot>=<path>, with each slot given once")
            }
            val local = options["--input-bindings"]?.single()?.let { path ->
                ExternalInputBindingsCodec.parse(SnapshotFiles.readText(path, 1024 * 1024)).mapValues { Path.of(it.value) }
            }.orEmpty()
            if (explicit.keys.any(local::containsKey)) return usage(error, "--input duplicates a slot from --input-bindings")
            val external = local + explicit
            val snapshot = SnapshotFiles.read(options.getValue("--graph-file").single(), snapshotLimit.maximumBytes)
            val started = System.nanoTime()
            val project = Path.of(options.getValue("--project").single()).toAbsolutePath().normalize()
            val result = SavedSnapshotOperations.freshness(snapshot, project, options["--scope"]?.single(), external)
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
            val status = if (changedRoots.isNotEmpty() || identityMismatch.isNotEmpty()) "stale" else result.status
            val reasons = (result.reasons +
                changedRoots.map { "changed-$it-order" } + identityMismatch.map { "build-$it-mismatch" }).distinct().sorted()
            output.println("{\"format\":\"kartograph-freshness\",\"version\":1,\"status\":\"$status\",\"hashNanos\":${System.nanoTime() - started},\"reasons\":[${reasons.joinToString(",") { "\"$it\"" }}]}")
            if (status == "matched") 0 else 1
        } catch (_: Exception) {
            error.println("error: unable to verify snapshot inputs; supply a valid snapshot (maximum ${snapshotLimit.maximumMiB} MiB), project and external input bindings")
            2
        }
    }

    private val OPTIONS = setOf(
        "--graph-file", "--project", "--scope", "--input", "--input-bindings", "--classes", "--classpath", "--artifact",
        "--compiler", "--snapshot-max-mib",
    )

    /** 옵션 이름 모양의 낱말이다. 경로·바인딩 값(`/`, `=`가 든 낱말)은 이 모양이 아니라 오류 문구에 싣지 않는다. */
    private val OPTION_NAME = Regex("--?[A-Za-z][A-Za-z0-9-]*")

    /**
     * 모든 옵션이 값 하나를 받는 `--option value` 쌍으로 읽히지 않는 이유다. 읽히면 null이다.
     *
     * 값 자리에는 경로가 올 수 있으므로 알려진 옵션 이름과 옵션 모양의 낱말만 문구에 싣는다.
     */
    private fun argumentProblem(arguments: List<String>): String? {
        val unknown = arguments.chunked(2).map { it[0] }.firstOrNull { it !in OPTIONS }
        return when {
            unknown != null && OPTION_NAME.matches(unknown) -> "unknown verify-snapshot option: $unknown (see kartograph verify-snapshot --help)"
            unknown != null -> "unexpected argument; verify-snapshot takes --option value pairs (see kartograph verify-snapshot --help)"
            arguments.size % 2 != 0 -> "missing value for ${arguments.last()}"
            else -> null
        }
    }

    /** 다른 명령처럼 원인을 stderr에 알리고 사용 오류(64)로 끝낸다. 경로나 원시 값은 싣지 않는다. */
    private fun usage(error: PrintStream, message: String): Int {
        error.println("error: $message")
        return ExitStatus.USAGE.code
    }

    internal fun inputBindings(values: List<String>): Map<String, Path> {
        val bindings = linkedMapOf<String, Path>()
        for (value in values) {
            val key = value.substringBefore('=')
            require('=' in value && key.startsWith("external/") && key !in bindings && value.substringAfter('=').isNotBlank()) {
                "invalid external input binding"
            }
            bindings[key] = Path.of(value.substringAfter('='))
        }
        return bindings
    }

    fun capture(project: Path, files: List<Pair<String, Path>>, context: List<String>, witnessPaths: List<Path>,
        scope: dev.kartograph.index.VerifiedCaptureScope): SnapshotProvenance {
        // 파일과 witness를 한 묶음으로 넘겨 digest를 병렬화한다. provenance 순서(파일, 옵션, witness)는 그대로다.
        val requested = files.mapIndexed { index, (role, path) -> CaptureInput(path, role, "$role-$index") } +
            witnessPaths.mapIndexed { index, path -> CaptureInput(path, "witness", "witness-$index") }
        val captured = scope.captureAll(project, requested)
        val inputs = captured.take(files.size) +
            InputFingerprint("options", "snapshot-options", ContentFingerprint.values(context)) +
            captured.drop(files.size)
        val witnesses = witnessPaths.map { BuildWitnessCodec.parse(SnapshotFiles.readText(it.toString(), 64 * 1024 * 1024)) }
        return SnapshotProvenance(inputs, witnesses)
    }
}
