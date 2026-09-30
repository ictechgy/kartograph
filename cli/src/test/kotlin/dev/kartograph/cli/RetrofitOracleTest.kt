package dev.kartograph.cli

import dev.kartograph.core.RouteLimitationScope
import dev.kartograph.export.McpJsonCodec
import dev.kartograph.index.RouteLimitationScopes
import dev.kartograph.index.RouteUrlRules
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `routes --role client`의 Retrofit route-call 템플릿을 실제 Retrofit 2.12.0 + OkHttp MockWebServer가 기록한 요청
 * (`fixtures/retrofit-corpus/oracle/retrofit-requests.json`, `experiments/phase4-retrofit/run.py`)과 대조한다.
 *
 * 비교는 isthmus 소비자 쪽 결합과 같다 — `pathAnchor: base`면 케이스의 base URL 경로 뒤에, `root`면 host 루트에 템플릿을
 * 붙이고, `{}` 자리에 Retrofit 규칙으로 인코딩한 `@Path` 값을 넣은 경로가 기록 경로와 같아야 한다. `{}`는 한 세그먼트라는
 * 주장이므로 넣은 값이 `/`를 담으면 불일치다. dynamic 사실은 이유를 적은 케이스만 허용하고, 증명한 접두사가 기록 경로의
 * 접두사인지 확인한다.
 *
 * `basePath`가 없는 케이스는 코퍼스 팩토리가 리터럴 base로 만든 서비스다. 생산자가 `create` 호출의 `baseUrl`을 따라가
 * authority와 base 경로를 결합한 root 템플릿을 내야 하며, base가 여럿이면 기록한 authority의 사실 하나와 대조한다.
 *
 * `interceptorCases`는 인터셉터 결합 코퍼스를 따로 스캔해 대조한다 — URL을 바꾸는 client를 쓰는 인스턴스만 base를 버리고(버린
 * base로 결합하면 기록과 어긋나야 한다), 그 밖의 인스턴스는 base를 적용해 기록과 일치해야 한다. 인스턴스별 한계에 스코프가
 * 있으면 실제 요청이 그 스코프 안이어야 한다.
 */
class RetrofitOracleTest {
    @TempDir
    lateinit var project: Path

    /** 생산자가 계약상 템플릿을 확정하지 않아야 하는 케이스와 그 이유다. 목록 밖 dynamic과 목록 안 템플릿은 모두 실패다. */
    private val expectedDynamic = mapOf(
        "users.raw" to "@Url: the whole URL is a runtime argument",
        "files.doc.encoded" to "@Path(encoded = true) keeps '/' so the value may span segments",
        "files.metadata.partial-segment" to "call-side partial-segment parameter (url-compose compose.interpolation)",
        "files.status-v2.dot-dot" to "'..' climbs above the unknown base path",
        "orgs.members.external-constant" to "constant declared in another file (same-file constants only)",
        "orgs.member.external-constant" to "constant declared in another file (same-file constants only)",
    )

    /** 인터셉터 결합 케이스 중 base를 버려야 하는(client가 요청 URL을 바꾸는) 케이스와 이유다. 목록 밖은 base를 적용해야 한다. */
    private val expectedWithheld = mapOf(
        "interceptor.class-instance.item" to "HostRewriteInterceptor class instance on the client",
        "interceptor.class-instance.order" to "HostRewriteInterceptor class instance on the client",
        "interceptor.network-path.report" to "network interceptor lambda prefixes the path",
        "interceptor.lambda.ping" to "Interceptor { } lambda held in a property",
        "interceptor.object.ping" to "object interceptor",
        "interceptor.derived.ping" to "interceptor added to a newBuilder() copy of the shared client",
        "interceptor.provides.edge" to "@Provides client with an injected HostRewriteInterceptor",
        "interceptor.koin.edge" to "Koin client definition with HostRewriteInterceptor",
        "interceptor.java.edge" to "Java anonymous interceptor on a local builder",
        "interceptor.authenticator.reroute" to "Authenticator retries on another host",
        "interceptor.call-factory.ping" to "custom Call.Factory rewrites the host",
    )

    /** base를 버린 케이스 중 재작성이 host만 바꿈을 증명해 한계에 스코프가 있어야 하는 케이스다. */
    private val expectedScoped = setOf(
        "interceptor.class-instance.item", "interceptor.class-instance.order", "interceptor.lambda.ping", "interceptor.object.ping",
        "interceptor.derived.ping", "interceptor.provides.edge", "interceptor.koin.edge", "interceptor.java.edge",
    )

    /** 동사를 계약 동사로 내지 않아야 하는 케이스다. */
    private val expectedMethodDynamic = mapOf("users.purge-cache" to "PURGE is outside the contract verb set")

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/$name")) { "missing Retrofit oracle resource $name" }.use { it.readAllBytes() }

    /** 케이스 하나의 판정이다. */
    private data class Verdict(val id: String, val outcome: String, val detail: String)

    @Test
    fun `retrofit route-call templates agree with MockWebServer recorded requests`() {
        val oracle = McpJsonCodec.parse(resource("retrofit-requests.json").toString(Charsets.UTF_8)) as Map<*, *>
        assertEquals<Any?>("kartograph-retrofit-requests", oracle["format"])
        assertEquals<Any?>("execution", oracle["provenance"])
        (oracle["sources"] as List<*>).map { it as String }.forEach { source ->
            val target = project.resolve(source.removePrefix("retrofit-client/"))
            Files.createDirectories(target.parent)
            Files.write(target, resource(source))
        }
        val (facts, limitations) = scanFacts()
        val cases = (oracle["cases"] as List<*>).map { it as Map<*, *> }
        val verdicts = cases.map { case -> judge(case, facts) }
        val table = verdicts.joinToString("\n") { "${it.outcome.padEnd(8)} ${it.id.padEnd(36)} ${it.detail}" }
        println("Retrofit oracle agreement (${verdicts.count { it.outcome == "match" }} match, " +
            "${verdicts.count { it.outcome == "dynamic" }} dynamic, ${verdicts.count { it.outcome == "mismatch" }} mismatch)\n$table")
        assertTrue(verdicts.none { it.outcome == "mismatch" }, "Retrofit templates disagree with recorded requests:\n$table")
        assertTrue(cases.size >= 50, "expected the full oracle case list, got ${cases.size}")
        assertTrue(cases.count { it["basePath"] == null } >= 10, "expected the factory (baseUrl) cases")
        assertEquals(expectedDynamic.keys, verdicts.filter { it.outcome == "dynamic" }.map { it.id }.toSet(), "dynamic cases")
        // 서비스 파일은 OkHttp 값 타입(RequestBody·ResponseBody)만 import한다 — 모델링하지 않은 호출이 아니다. 팩토리 두 파일은
        // Retrofit에 넘길 OkHttpClient를 import하므로 기존 정책대로 모델링하지 않은 클라이언트로 센다(보수적).
        assertEquals(
            listOf("route-call-coverage: 2 source file(s)"),
            limitations.filter { it.startsWith("route-call-coverage:") }.map { it.substringBefore(" use HTTP") },
            "coverage limitations: $limitations",
        )
        // harness가 base를 주는 서비스는 코퍼스에 create 호출이 없어 base를 풀지 못한 것으로 센다.
        assertTrue(limitations.any { it.startsWith("unresolved-base-url: 7 Retrofit service interface(s)") }, "unresolved: $limitations")
    }

    /** 임시 프로젝트에 `routes --role client`를 실행해 route-call 사실(symbol 한정 이름별)과 limitation을 모은다. */
    private fun scanFacts(root: Path = project): Pair<Map<String, List<Map<*, *>>>, List<String>> {
        val document = scanDocument(root)
        val facts = (document["facts"] as List<*>).map { it as Map<*, *> }.groupBy { ((it["symbol"] as Map<*, *>)["qualifiedName"]).toString() }
        return facts to (document["limitations"] as List<*>).map { it.toString() }
    }

    private fun scanDocument(root: Path): Map<*, *> {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = KartographCli.run(
            arrayOf("routes", "--role", "client", "--project", root.toString(), "--format", "json"), PrintStream(output), PrintStream(error),
        )
        assertEquals(ExitStatus.SUCCESS.code, status, error.toString())
        return McpJsonCodec.parse(output.toString()) as Map<*, *>
    }

    @Test
    fun `interceptor binding rules agree with MockWebServer recorded requests`() {
        val oracle = McpJsonCodec.parse(resource("retrofit-requests.json").toString(Charsets.UTF_8)) as Map<*, *>
        val root = project.resolve("interceptor-client")
        (oracle["interceptorSources"] as List<*>).map { it as String }.forEach { source ->
            val target = root.resolve(source.removePrefix("interceptor-client/"))
            Files.createDirectories(target.parent)
            Files.write(target, resource(source))
        }
        val document = scanDocument(root)
        val facts = (document["facts"] as List<*>).map { it as Map<*, *> }.groupBy { ((it["symbol"] as Map<*, *>)["qualifiedName"]).toString() }
        val limitations = (document["limitations"] as List<*>).map { it.toString() }
        val scopes = (document["limitationScopes"] as List<*>? ?: emptyList<Any>()).map { it as Map<*, *> }.associate { entry ->
            val limitation = limitations[(entry["limitationIndex"] as Number).toInt()]
            fun strings(key: String) = (entry[key] as List<*>? ?: emptyList<Any>()).map { it.toString() }
            limitation to RouteLimitationScope(limitation, strings("templates"), strings("templatePrefixes"), strings("templateSuffixes"), strings("methods"))
        }
        // 모든 재작성이 원천을 아는 client에 붙었다 — 프로젝트 전체 대체 규칙이 아니라 인스턴스별로 판단해야 한다.
        assertTrue(limitations.none { "are not bound to the OkHttp clients they affect" in it }, "$limitations")
        val cases = (oracle["interceptorCases"] as List<*>).map { it as Map<*, *> }
        val verdicts = cases.map { case -> judgeInterceptor(case, facts, limitations, scopes) }
        val table = verdicts.joinToString("\n") { "${it.outcome.padEnd(8)} ${it.id.padEnd(36)} ${it.detail}" }
        println("Retrofit interceptor oracle (${verdicts.count { it.outcome == "match" }} match, " +
            "${verdicts.count { it.outcome == "withheld" }} withheld, ${verdicts.count { it.outcome == "mismatch" }} mismatch)\n$table")
        assertTrue(verdicts.none { it.outcome == "mismatch" }, "interceptor bindings disagree with recorded requests:\n$table")
        assertTrue(cases.size >= 18, "expected the full interceptor case list, got ${cases.size}")
        assertEquals(expectedWithheld.keys, verdicts.filter { it.outcome == "withheld" }.map { it.id }.toSet(), "withheld cases")
    }

    /**
     * 인터셉터 케이스 하나의 판정이다. base를 적용한 사실은 기록과 일치해야 하고(`match`), base를 버린 사실은 이유가 적힌 케이스여야
     * 하며 버린 base로 결합한 요청이 기록과 달라야 한다(`withheld` — 재작성이 실제로 요청을 바꿨다). 인스턴스별 한계의 스코프는
     * 기록한 요청을 덮어야 한다.
     */
    private fun judgeInterceptor(case: Map<*, *>, facts: Map<String, List<Map<*, *>>>, limitations: List<String>, scopes: Map<String, RouteLimitationScope>): Verdict {
        val id = case["id"] as String
        val recorded = case["recorded"] as Map<*, *>
        val authority = recorded["authority"] as String
        val fact = facts[case["symbol"] as String].orEmpty().singleOrNull() ?: return Verdict(id, "mismatch", "expected one fact for ${case["symbol"]}")
        if (fact["method"] != recorded["method"]) return Verdict(id, "mismatch", "method ${fact["method"]}, recorded ${recorded["method"]}")
        val arguments = (case["pathArguments"] as List<*>).map { it as Map<*, *> }.map { encodePathValue(it["value"] as String, it["encoded"] == true) }
        val recordedPath = RouteUrlRules.normalizePath(recorded["path"] as String)
        val template = fact["channel"] as String? ?: return Verdict(id, "mismatch", "unexpected dynamic fact")
        val filled = substitute(template, arguments) ?: return Verdict(id, "mismatch", "template $template does not take $arguments")
        val anchor = fact["pathAnchor"] as String
        if (fact["authority"] != null) {
            if (id in expectedWithheld) return Verdict(id, "mismatch", "expected a withheld base (${expectedWithheld[id]}), got ${fact["authority"]}")
            val composed = RouteUrlRules.normalizePath(join(anchor, null, filled))
            if (composed != recordedPath || fact["authority"] != authority) {
                return Verdict(id, "mismatch", "${fact["authority"]} $composed, recorded $authority $recordedPath")
            }
            return Verdict(id, "match", "${fact["method"]} root ${fact["authority"]} $template -> $composed")
        }
        val reason = expectedWithheld[id] ?: return Verdict(id, "mismatch", "base withheld although the client does not rewrite (recorded $authority $recordedPath)")
        val base = case["declaredBase"] as String
        val baseAuthority = base.substringAfter("://").substringBefore('/')
        val basePath = "/" + base.substringAfter("://").substringAfter('/', "")
        val wouldBe = RouteUrlRules.normalizePath(if (anchor == "base") basePath.removeSuffix("/") + filled else filled)
        if (wouldBe == recordedPath && baseAuthority == authority) return Verdict(id, "mismatch", "withheld base $base would have matched the recorded request")
        val line = limitations.singleOrNull { it.startsWith("url-rewrite-interceptors: Retrofit instance ${fact["baseRef"]} ") }
            ?: return Verdict(id, "mismatch", "no per-instance url-rewrite-interceptors limitation for ${fact["baseRef"]}")
        val scope = scopes[line]
        if ((scope != null) != (id in expectedScoped)) return Verdict(id, "mismatch", "scope ${if (scope == null) "missing" else "unexpected"}: $scope")
        if (scope != null && !RouteLimitationScopes.applies(scope, recordedPath, recorded["method"] as String, "root", declaration = true)) {
            return Verdict(id, "mismatch", "scope $scope does not cover the recorded ${recorded["method"]} $recordedPath")
        }
        val scoped = if (scope == null) "unscoped" else "scope covers ${recorded["method"]} $recordedPath"
        return Verdict(id, "withheld", "$reason; $baseAuthority$wouldBe -> recorded $authority$recordedPath; $scoped")
    }

    private fun judge(case: Map<*, *>, facts: Map<String, List<Map<*, *>>>): Verdict {
        val id = case["id"] as String
        val recorded = case["recorded"] as Map<*, *>
        val basePath = case["basePath"] as String?
        val authority = (recorded["authority"] as String).ifEmpty { null }
        // 팩토리 케이스는 base마다 사실이 하나다 — 기록한 host의 사실과 대조한다.
        val candidates = facts[case["symbol"] as String].orEmpty().filter { basePath != null || it["authority"] == authority }
        val fact = candidates.singleOrNull() ?: return Verdict(id, "mismatch", "expected one fact for ${case["symbol"]} (authority $authority), found ${candidates.size}")
        if (basePath == null && fact["pathAnchor"] != "root") return Verdict(id, "mismatch", "a literal base must compose a root template, got ${fact["pathAnchor"]}")
        methodProblem(id, fact, recorded["method"] as String)?.let { return Verdict(id, "mismatch", it) }
        val arguments = (case["pathArguments"] as List<*>).map { it as Map<*, *> }.map { encodePathValue(it["value"] as String, it["encoded"] == true) }
        val recordedPath = RouteUrlRules.normalizePath(recorded["path"] as String)
        if (fact["dynamic"] == true) return dynamicVerdict(id, fact, arguments, basePath, recordedPath)
        if (id in expectedDynamic) return Verdict(id, "mismatch", "expected dynamic (${expectedDynamic[id]}), got ${fact["channel"]}")
        val template = fact["channel"] as String
        val filled = substitute(template, arguments) ?: return Verdict(id, "mismatch", "template $template does not take ${arguments.size} single-segment value(s) $arguments")
        val composed = RouteUrlRules.normalizePath(join(fact["pathAnchor"] as String, basePath, filled))
        if (composed != recordedPath) return Verdict(id, "mismatch", "${fact["pathAnchor"]} $template -> $composed, recorded $recordedPath")
        if (fact["authority"] != authority) return Verdict(id, "mismatch", "authority ${fact["authority"]}, recorded $authority")
        return Verdict(id, "match", "${fact["method"] ?: "methodDynamic"} ${fact["pathAnchor"]} $template -> $composed")
    }

    private fun methodProblem(id: String, fact: Map<*, *>, recorded: String): String? = when {
        fact["methodDynamic"] == true -> if (id in expectedMethodDynamic) null else "unexpected methodDynamic, recorded $recorded"
        id in expectedMethodDynamic -> "expected methodDynamic (${expectedMethodDynamic[id]}), got ${fact["method"]}"
        fact["method"] != recorded -> "method ${fact["method"]}, recorded $recorded"
        else -> null
    }

    /** dynamic 사실은 이유가 적힌 케이스만 받는다. 증명한 접두사가 있으면 채운 접두사가 기록 경로의 접두사여야 한다. */
    private fun dynamicVerdict(id: String, fact: Map<*, *>, arguments: List<String>, basePath: String?, recordedPath: String): Verdict {
        val reason = expectedDynamic[id] ?: return Verdict(id, "mismatch", "unexpected dynamic fact")
        val prefix = fact["channelPrefix"] as String? ?: return Verdict(id, "dynamic", reason)
        val filled = substitute(prefix, arguments.take(prefix.split("{}").size - 1), partial = true)
            ?: return Verdict(id, "mismatch", "channelPrefix $prefix cannot take the path values")
        val composed = RouteUrlRules.normalizePath(join(fact["pathAnchor"] as String, basePath, filled))
        if (!recordedPath.startsWith(composed)) return Verdict(id, "mismatch", "channelPrefix $composed is not a prefix of $recordedPath")
        return Verdict(id, "dynamic", "$reason; channelPrefix $prefix -> $composed")
    }

    /** base 앵커는 base URL 경로(`/`로 끝남) 뒤에, root 앵커는 host 루트에 붙인다. */
    private fun join(anchor: String, basePath: String?, template: String): String =
        if (anchor == "base") requireNotNull(basePath) { "a base-anchored fact needs the case base path" }.removeSuffix("/") + template else template

    /**
     * 템플릿의 `{}`를 순서대로 값으로 채운다. 값이 `/`를 담으면 한 세그먼트라는 주장과 어긋나므로 null이다.
     *
     * @param partial 참이면 남는 값이 있어도 된다(dynamic 접두사)
     */
    private fun substitute(template: String, values: List<String>, partial: Boolean = false): String? {
        val pieces = template.split("{}")
        if (pieces.size - 1 != values.size && !(partial && pieces.size - 1 <= values.size)) return null
        if (values.any { '/' in it }) return null
        return pieces.withIndex().joinToString("") { (index, piece) -> piece + (values.getOrNull(index).takeIf { index < pieces.lastIndex } ?: "") }
    }

    /**
     * Retrofit `RequestBuilder.canonicalizeForPath`와 같은 `@Path` 값 인코딩이다(retrofit 2.12.0 소스). 제어·비ASCII 문자와
     * ` "<>^`{}|\?#`는 늘, `/`·`%`는 `encoded = false`일 때 UTF-8 퍼센트 인코딩한다.
     */
    private fun encodePathValue(value: String, encoded: Boolean): String = buildString {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val escape = codePoint < 0x20 || codePoint >= 0x7f || codePoint.toChar() in " \"<>^`{}|\\?#" ||
                (!encoded && (codePoint == '/'.code || codePoint == '%'.code))
            val text = String(Character.toChars(codePoint))
            if (escape) text.toByteArray(Charsets.UTF_8).forEach { append('%').append("%02X".format(it.toInt() and 0xFF)) } else append(text)
            index += Character.charCount(codePoint)
        }
    }
}
