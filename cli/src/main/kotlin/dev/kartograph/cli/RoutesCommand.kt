package dev.kartograph.cli

import dev.kartograph.core.HttpWrapperDeclaration
import dev.kartograph.export.AgentDocumentRenderer
import dev.kartograph.export.HttpWrappersCodec
import dev.kartograph.index.RouteCallScanner
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * `kartograph routes` — 클라이언트 HTTP 호출을 isthmus http 도메인의 `route-call` 문서로 낸다.
 *
 * 이번 버전은 `--role client`만 지원한다. 서버 라우트 선언(`route-decl`)은 스캔하지 않으므로
 * `--role server`를 조용히 빈 문서로 받지 않고 사용 오류로 거부한다.
 */
internal object RoutesCommand {
    /** 값을 받는 옵션이다. */
    private val VALUE_OPTIONS = setOf("--role", "--project", "--format", "--wrappers", "--service", "--graph-file")

    /** 값 없이 켜는 옵션이다. */
    private val FLAG_OPTIONS = setOf("--include-tests")

    /** 선언 파일 크기 상한이다. 코덱과 같은 1 MiB다. */
    private const val MAX_WRAPPERS_BYTES = 1024L * 1024L

    fun run(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        if (arguments == listOf("--help") || arguments == listOf("-h")) {
            output.print(HELP)
            return ExitStatus.SUCCESS.code
        }
        val options = parse(arguments, error) ?: return ExitStatus.USAGE.code
        val role = options.single("--role") ?: return usage(error, "missing required --role client")
        if (role == "server") return usage(error, "--role server is not supported yet; route declarations are not scanned")
        if (role != "client") return usage(error, "invalid routes role: use --role client")
        if (options.single("--format")?.let { it != "json" } == true) return usage(error, "invalid routes format")
        val service = options.single("--service")
        if (service != null && (service.isBlank() || service.any(Char::isISOControl))) return usage(error, "invalid --service value")
        val project = try {
            options.single("--project")?.let(Path::of)?.toAbsolutePath()?.normalize()
                ?: return usage(error, "missing required --project path")
        } catch (_: InvalidPathException) {
            return usage(error, "invalid path")
        }
        if (!Files.isDirectory(project)) return failure(error, "project root does not exist")
        val roots = when (val checked = sourceRoots(project, options.positional, error)) {
            is Checked.Ok -> checked.value
            is Checked.Failed -> return checked.status
        }
        val wrappers = when (val checked = readWrappers(project, options.single("--wrappers"), error)) {
            is Checked.Ok -> checked.value
            is Checked.Failed -> return checked.status
        }
        val conflicting = wrappers.any { it.language == "kotlin" && service != null && it.service != null && it.service != service }
        if (conflicting) return usage(error, "a kotlin wrapper service differs from --service; drop one of them")
        return scan(project, roots, wrappers, options, service, output, error)
    }

    /** 입력 검증 결과다. 실패는 원인에 맞는 종료 코드를 싣고 오류 문구는 이미 출력됐다. */
    private sealed interface Checked<out T> {
        data class Ok<T>(val value: T) : Checked<T>
        data class Failed(val status: Int) : Checked<Nothing>
    }

    private fun scan(
        project: Path,
        roots: List<Path>,
        wrappers: List<HttpWrapperDeclaration>,
        options: RoutesOptions,
        service: String?,
        output: PrintStream,
        error: PrintStream,
    ): Int = try {
        val snapshot = options.single("--graph-file")?.let { SnapshotFiles.read(it) }
        val freshness = snapshot?.let { SavedSnapshotOperations.freshness(it, project, null, emptyMap()) }
        val graph = snapshot?.takeUnless { freshness?.status == "stale" }?.graph
        val scanned = RouteCallScanner(project, roots, wrappers, options.flag("--include-tests"), service).scan(graph = graph)
        val missing = RouteSymbolDiagnostics.missingUsrs(scanned.facts, snapshot?.graph, stale = snapshot != null && graph == null)
        val document = snapshot?.let {
            val evidence = "graph-file-freshness-${freshness!!.status}: " + freshness.reasons.joinToString(";")
            scanned.copy(limitations = (scanned.limitations + it.limitations + evidence + listOfNotNull(missing)).distinct().sorted())
        } ?: scanned.copy(limitations = (scanned.limitations + listOfNotNull(missing)).distinct().sorted())
        output.print(AgentDocumentRenderer.bridges(document))
        ExitStatus.SUCCESS.code
    } catch (_: Exception) {
        failure(error, "unable to scan route calls; check the project inputs and source roots")
    }

    /** 위치 인자 source 루트를 `--project` 기준으로 풀고 프로젝트 안인지 확인한다. */
    private fun sourceRoots(project: Path, values: List<String>, error: PrintStream): Checked<List<Path>> {
        val projectReal = project.toRealPath()
        return Checked.Ok(values.map { value ->
            val path = try {
                Path.of(value).let { if (it.isAbsolute) it.normalize() else project.resolve(it).normalize() }
            } catch (_: InvalidPathException) {
                return Checked.Failed(usage(error, "invalid source root path"))
            }
            if (!Files.isDirectory(path)) {
                return Checked.Failed(failure(error, "source root does not exist; pass directories inside --project"))
            }
            if (!path.toRealPath().startsWith(projectReal)) {
                return Checked.Failed(usage(error, "source root must be inside --project"))
            }
            path
        })
    }

    /** 선언 파일을 읽는다. 경로는 `--project` 기준이며, 없으면 빈 목록이다. */
    private fun readWrappers(project: Path, value: String?, error: PrintStream): Checked<List<HttpWrapperDeclaration>> {
        if (value == null) return Checked.Ok(emptyList())
        return try {
            val path = Path.of(value).let { if (it.isAbsolute) it.normalize() else project.resolve(it).normalize() }
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_WRAPPERS_BYTES) {
                return Checked.Failed(failure(error, "http-wrappers file does not exist or exceeds 1 MiB"))
            }
            Checked.Ok(HttpWrappersCodec.parse(Files.readString(path)))
        } catch (invalid: IllegalArgumentException) {
            // 코덱 문구는 위치와 고칠 방향만 담고 선언 원문 값은 담지 않는다.
            Checked.Failed(failure(error, invalid.message ?: "invalid http-wrappers file"))
        } catch (_: InvalidPathException) {
            Checked.Failed(usage(error, "invalid path"))
        } catch (_: IOException) {
            Checked.Failed(failure(error, "unable to read the http-wrappers file"))
        }
    }

    private data class RoutesOptions(val values: Map<String, List<String>>, val positional: List<String>) {
        fun single(name: String): String? = values[name]?.lastOrNull()
        fun flag(name: String): Boolean = values.containsKey(name)
    }

    private fun parse(arguments: List<String>, error: PrintStream): RoutesOptions? {
        val values = mutableMapOf<String, MutableList<String>>()
        val positional = mutableListOf<String>()
        var index = 0
        while (index < arguments.size) {
            val argument = arguments[index]
            when {
                argument in FLAG_OPTIONS -> { values.getOrPut(argument) { mutableListOf() } += "true"; index++ }
                argument in VALUE_OPTIONS -> {
                    val value = arguments.getOrNull(index + 1)?.takeUnless { it.startsWith('-') }
                        ?: return null.also { error.println("error: missing value for $argument") }
                    values.getOrPut(argument) { mutableListOf() } += value
                    index += 2
                }
                argument.startsWith('-') -> return null.also { error.println("error: unknown option: $argument") }
                else -> { positional += argument; index++ }
            }
        }
        return RoutesOptions(values, positional)
    }

    private fun usage(error: PrintStream, message: String): Int {
        error.println("error: $message")
        return ExitStatus.USAGE.code
    }

    private fun failure(error: PrintStream, message: String): Int {
        error.println("error: $message")
        return ExitStatus.FAILURE.code
    }

    val HELP = """
        Scan project sources for client HTTP calls and emit a bridge-facts http document for isthmus.

        Usage:
          kartograph routes --role client --project <directory> [--format json] [--wrappers <http-wrappers.json>] \
            [--include-tests] [--service <name>] [--graph-file <snapshot>] [<source-root>...]

        Emits "target": "http", "roles": ["client"] and one route-call fact per call site: the HTTP method
        (or methodDynamic), the canonical path template (or a dynamic fact with a proven channelPrefix),
        pathAnchor, and the project-relative location of the call expression.

        Recognized calls:
          - calls of wrappers declared in an http-wrappers v1 file (--wrappers; only "kotlin" entries apply)
          - java.net.URL requests opened with openConnection/openStream/readText when the path is provable
          - Retrofit @GET/@POST/@PUT/@PATCH/@DELETE/@HEAD/@OPTIONS/@HTTP paths (@Url is dynamic)
        Other clients (OkHttp, Ktor, ...) are not claimed and are reported as route-call-coverage.

        Source roots resolve from --project and must stay inside it; without them the whole project is
        scanned. Test source sets (src/test, src/androidTest, src/*Test, ...) are excluded unless
        --include-tests is given, which marks those facts testSource. --role server is not supported yet.
        Literal URLs lose userinfo, query and fragment, and high-entropy or webhook segments are masked.
        --graph-file attaches JVM symbol identities only when the snapshot is fresh. Facts left without an identity
        are counted by missing-route-usrs, which names a snapshot/routes --project root mismatch when the source paths
        show one; capture the snapshot and run routes with the same --project.
    """.trimIndent() + "\n"
}
