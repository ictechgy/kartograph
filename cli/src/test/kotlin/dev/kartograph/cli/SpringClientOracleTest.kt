package dev.kartograph.cli

import dev.kartograph.export.McpJsonCodec
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
 * `routes --role client`의 Spring `RestTemplate`·`RestClient`·`WebClient`·`@HttpExchange` route-call 사실을 합성 Spring Boot 앱이 실제로
 * 보낸 요청(`fixtures/spring-clients-corpus/oracle/spring-client-requests.json`, `experiments/phase7b-spring-clients/run.py`)과 대조한다.
 *
 * 비교는 isthmus 소비자 쪽 결합과 같다 — `root`면 템플릿의 `{}`를 케이스 값으로 채운 경로가 기록 경로와 같고 `authority`가 기록한
 * host와 같아야 한다. `pathAnchor: base`는 base를 실행 시점 값으로 둔 케이스(`basePath`)만 받고, 그 경로 뒤에 붙인 결과를 대조한다.
 * 동사도 대조한다.
 */
class SpringClientOracleTest {
    @TempDir
    lateinit var project: Path

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/$name")) { "missing Spring client oracle resource $name" }.use { it.readAllBytes() }

    /** 케이스 하나의 판정이다. */
    private data class Verdict(val id: String, val outcome: String, val detail: String)

    @Test
    fun `spring client route-call templates agree with requests the synthetic app sent`() {
        val oracle = McpJsonCodec.parse(resource("spring-client-requests.json").toString(Charsets.UTF_8)) as Map<*, *>
        assertEquals<Any?>("kartograph-spring-client-requests", oracle["format"])
        assertEquals<Any?>("execution", oracle["provenance"])
        (oracle["sources"] as List<*>).map { it as String }.forEach { source ->
            val target = project.resolve(source.removePrefix("spring-client/"))
            Files.createDirectories(target.parent)
            Files.write(target, resource(source))
        }
        val (facts, limitations) = scanFacts()
        val cases = (oracle["cases"] as List<*>).map { it as Map<*, *> }
        val verdicts = cases.map { case -> judge(case, facts) }
        val table = verdicts.joinToString("\n") { "${it.outcome.padEnd(8)} ${it.id.padEnd(36)} ${it.detail}" }
        println("Spring client oracle agreement (${verdicts.count { it.outcome == "match" }} match, " +
            "${verdicts.count { it.outcome == "mismatch" }} mismatch)\n$table")
        assertTrue(verdicts.all { it.outcome == "match" }, "Spring client templates disagree with recorded requests:\n$table")
        assertTrue(cases.size >= 30, "expected the full oracle case list, got ${cases.size}")
        // 실행 시점 base 하나(env)만 풀지 못하고, 다른 프로필이 바꾸는 base 하나(search)는 기본 프로필 값을 쓴 것으로 알린다.
        assertTrue(limitations.any { it.startsWith("unresolved-base-url: 1 Spring RestTemplate/RestClient/WebClient call(s) and 0 @HttpExchange") }, "$limitations")
        assertTrue(limitations.any { it.startsWith("unresolved-base-url: 1 Spring HTTP client call(s) take their base URL") }, "$limitations")
        assertTrue(limitations.none { it.startsWith("route-call-coverage:") || it.startsWith("ambiguous-base-join:") }, "$limitations")
    }

    /** 임시 프로젝트에 `routes --role client`를 실행해 route-call 사실(symbol 한정 이름별)과 limitation을 모은다. */
    private fun scanFacts(): Pair<Map<String, List<Map<*, *>>>, List<String>> {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = KartographCli.run(
            arrayOf("routes", "--role", "client", "--project", project.toString(), "--format", "json"), PrintStream(output), PrintStream(error),
        )
        assertEquals(ExitStatus.SUCCESS.code, status, error.toString())
        val document = McpJsonCodec.parse(output.toString()) as Map<*, *>
        val facts = (document["facts"] as List<*>).map { it as Map<*, *> }.groupBy { ((it["symbol"] as Map<*, *>)["qualifiedName"]).toString() }
        return facts to (document["limitations"] as List<*>).map { it.toString() }
    }

    private fun judge(case: Map<*, *>, facts: Map<String, List<Map<*, *>>>): Verdict {
        val id = case["id"] as String
        val recorded = case["recorded"] as Map<*, *>
        val basePath = case["basePath"] as String?
        val authority = recorded["authority"] as String
        val candidates = facts[case["symbol"] as String].orEmpty()
        val fact = candidates.singleOrNull() ?: return Verdict(id, "mismatch", "expected one fact for ${case["symbol"]}, found ${candidates.size}")
        if (fact["dynamic"] == true) return Verdict(id, "mismatch", "unexpected dynamic fact ${fact["channel"]} prefix ${fact["channelPrefix"]}")
        if (fact["methodDynamic"] == true || fact["method"] != recorded["method"]) return Verdict(id, "mismatch", "method ${fact["method"]}, recorded ${recorded["method"]}")
        val anchor = fact["pathAnchor"] as String
        if ((basePath == null) != (anchor == "root")) return Verdict(id, "mismatch", "pathAnchor $anchor with basePath $basePath")
        val template = fact["channel"] as String
        val arguments = (case["pathArguments"] as List<*>).map { it as String }
        val filled = substitute(template, arguments) ?: return Verdict(id, "mismatch", "template $template does not take $arguments")
        val composed = RouteUrlRules.normalizePath(if (basePath == null) filled else basePath.removeSuffix("/") + filled)
        val recordedPath = RouteUrlRules.normalizePath(recorded["path"] as String)
        if (composed != recordedPath) return Verdict(id, "mismatch", "$anchor $template -> $composed, recorded $recordedPath")
        if (basePath == null && fact["authority"] != authority) return Verdict(id, "mismatch", "authority ${fact["authority"]}, recorded $authority")
        return Verdict(id, "match", "${fact["method"]} $anchor ${fact["authority"] ?: "-"} $template -> $composed")
    }

    /** 템플릿의 `{}`를 순서대로 값으로 채운다. 값이 `/`를 담으면 한 세그먼트라는 주장과 어긋나므로 null이다. */
    private fun substitute(template: String, values: List<String>): String? {
        val pieces = template.split("{}")
        if (pieces.size - 1 != values.size || values.any { '/' in it }) return null
        return pieces.withIndex().joinToString("") { (index, piece) -> piece + (values.getOrNull(index).takeIf { index < pieces.lastIndex } ?: "") }
    }
}
