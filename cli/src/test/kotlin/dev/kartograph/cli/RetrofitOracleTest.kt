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
