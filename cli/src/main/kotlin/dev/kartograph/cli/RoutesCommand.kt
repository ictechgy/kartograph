package dev.kartograph.cli

import dev.kartograph.core.HttpWrapperDeclaration
import dev.kartograph.export.AgentDocumentRenderer
import dev.kartograph.export.ExternalInputBindingsCodec
import dev.kartograph.export.HttpWrappersCodec
import dev.kartograph.index.RouteCallScanner
import dev.kartograph.index.RouteDeclScanner
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * `kartograph routes` — isthmus http 도메인 문서를 낸다.
 *
 * `--role client`는 클라이언트 HTTP 호출(래퍼·`java.net.URL`·Retrofit·Spring 클라이언트)을 `route-call`로, `--role server`는 Spring MVC·WebFlux 어노테이션
 * controller를 `route-decl`로 낸다. 서버 역할은 snapshot이 신선하면 그 class root의 바이트코드 어노테이션을 값
 * 원천으로 쓴다(상수가 접힌 값). 역할마다 다른 스캐너를 쓰지만 입력 검증·신선도·신원 진단은 공유한다.
 */
internal object RoutesCommand {
    /** 값을 받는 옵션이다. */
    private val VALUE_OPTIONS = setOf("--role", "--project", "--format", "--wrappers", "--service", "--graph-file", "--input-bindings")

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
        val role = options.single("--role") ?: return usage(error, "missing required --role client|server")
        if (role != "client" && role != "server") return usage(error, "invalid routes role: use --role client or --role server")
        if (role == "server" && options.flag("--wrappers")) return usage(error, "--wrappers applies to --role client only")
        if (options.single("--format")?.let { it != "json" } == true) return usage(error, "invalid routes format")
        if (options.flag("--input-bindings") && !options.flag("--graph-file")) return usage(error, "--input-bindings requires --graph-file")
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
        return scan(project, roots, wrappers, options, service, role, output, error)
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
        role: String,
        output: PrintStream,
        error: PrintStream,
    ): Int = try {
        val snapshot = options.single("--graph-file")?.let { SnapshotFiles.read(it) }
        // plugin·merge가 만든 로컬 연결이 있어야 project 밖 입력(의존성 JAR, 옮긴 build 디렉터리)의 신선도를 확인한다.
        val bindings = options.single("--input-bindings")?.let { path ->
            ExternalInputBindingsCodec.parse(SnapshotFiles.readText(path, 1024 * 1024)).mapValues { Path.of(it.value) }
        }.orEmpty()
        val freshness = snapshot?.let { SavedSnapshotOperations.freshness(it, project, null, bindings) }
        val graph = snapshot?.takeUnless { freshness?.status == "stale" }?.graph
        val includeTests = options.flag("--include-tests")
        val scanned = if (role == "server") {
            val classRoots = if (graph == null) emptyList() else snapshotClassRoots(snapshot, project, bindings)
            RouteDeclScanner(project, roots, includeTests, service, classRoots).scan(graph = graph)
        } else RouteCallScanner(project, roots, wrappers, includeTests, service).scan(graph = graph)
        val missing = RouteSymbolDiagnostics.missingUsrs(scanned.facts, snapshot?.graph, stale = snapshot != null && graph == null)
        val document = snapshot?.let {
            val evidence = SavedSnapshotOperations.freshnessLimitation(freshness!!)
            scanned.copy(limitations = (scanned.limitations + it.limitations + listOfNotNull(evidence, missing)).distinct().sorted())
        } ?: scanned.copy(limitations = (scanned.limitations + listOfNotNull(missing)).distinct().sorted())
        output.print(AgentDocumentRenderer.bridges(document))
        ExitStatus.SUCCESS.code
    } catch (_: Exception) {
        val subject = if (role == "server") "route declarations" else "route calls"
        failure(error, "unable to scan $subject; check the project inputs and source roots")
    }

    /**
     * snapshot provenance의 class root 중 지금 있는 것이다. `external/` 슬롯은 `--input-bindings`로 연결한 것만 쓴다.
     * 신선도가 stale이면 호출하지 않는다 — 바뀐 class로 값을 읽지 않기 위해서다.
     */
    private fun snapshotClassRoots(snapshot: dev.kartograph.export.QuerySnapshot, project: Path, bindings: Map<String, Path>): List<Path> =
        snapshot.provenance?.inputs.orEmpty().filter { it.role == "classes" }.mapNotNull { input ->
            val path = if (input.path.startsWith("external/")) bindings[input.path] else project.resolve(input.path)
            path?.toAbsolutePath()?.normalize()?.takeIf { Files.exists(it) }
        }.distinct()

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
        Scan project sources for HTTP routes and emit a bridge-facts http document for isthmus.

        Usage:
          kartograph routes --role client --project <directory> [--format json] [--wrappers <http-wrappers.json>] \
            [--include-tests] [--service <name>] [--graph-file <snapshot>] [--input-bindings <file>] [<source-root>...]
          kartograph routes --role server --project <directory> [--format json] [--include-tests] [--service <name>] \
            [--graph-file <snapshot>] [--input-bindings <file>] [<source-root>...]

        --role server emits "roles": ["server"], "dispatch": "specificity" and one route-decl fact per Spring MVC or
        WebFlux handler mapping (@RequestMapping, @GetMapping..., @HttpExchange... and custom annotations meta-annotated
        with them, class x method, with context-path or base-path from in-repo Spring Boot configuration). With a fresh
        --graph-file the snapshot's class roots supply the annotation values (constants already folded by the compiler)
        and the snapshot supplies symbol.usr; sources supply the annotation locations. See docs/SPRING-ROUTES.md.

        Emits "target": "http", "roles": ["client"] and one route-call fact per call site: the HTTP method
        (or methodDynamic), the canonical path template (or a dynamic fact with a proven channelPrefix),
        pathAnchor, and the project-relative location of the call expression.

        Recognized calls:
          - calls of wrappers declared in an http-wrappers v1 file (--wrappers; only "kotlin" entries apply)
          - java.net.URL requests opened with openConnection/openStream/readText when the path is provable
          - Retrofit @GET/@POST/@PUT/@PATCH/@DELETE/@HEAD/@OPTIONS/@HTTP paths (@Url is dynamic)
          - Spring RestTemplate (getForObject, exchange, ...), RestClient and WebClient (get()...uri(...)) calls on a
            receiver proven to be such a client, and @HttpExchange interface methods (one fact per method); base URLs
            come from builder chains (baseUrl, rootUri), @Bean methods and @Value properties of the in-repo default
            profile, joined the way Spring's UriBuilderFactory does (see docs/SPRING-CLIENTS.md)
        Other clients (OkHttp, Ktor, Feign, ...) are not claimed and are reported as route-call-coverage.

        Source roots resolve from --project and must stay inside it; without them the whole project is
        scanned. Test source sets (src/test, src/androidTest, src/*Test, ...) are excluded unless
        --include-tests is given, which marks those facts testSource.
        Literal URLs lose userinfo, query and fragment, and high-entropy or webhook segments are masked.
        --graph-file attaches JVM symbol identities unless the snapshot is stale. --input-bindings passes the local bindings
        written with the snapshot (Gradle plugin or `snapshot merge`) so inputs outside --project can be verified; without it
        such inputs report graph-file-freshness-unverified (missing-external-input). Facts left without an identity
        are counted by missing-route-usrs, which names a snapshot/routes --project root mismatch when the source paths
        show one; capture the snapshot and run routes with the same --project.
    """.trimIndent() + "\n"
}
