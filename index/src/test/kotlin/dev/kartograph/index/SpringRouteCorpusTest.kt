package dev.kartograph.index

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 실제 Spring Boot 4.1.0으로 띄워 기록한 `/actuator/mappings`(fixtures/spring-routes-corpus)와 소스 원천 추출을 대조한다.
 *
 * 정밀도는 100%여야 한다 — 낸 정적 사실은 모두 오라클에 있어야 한다. 재현율의 빈자리는 이유를 적은 목록과 정확히
 * 같아야 한다. 새 규칙이 빈자리를 메우거나 새 빈자리를 만들면 이 테스트가 드러낸다.
 */
class SpringRouteCorpusTest {
    private val corpus = Path.of("../fixtures/spring-routes-corpus")

    private fun expected(app: String): Set<String> = Files.readAllLines(corpus.resolve("$app/expected-routes.tsv"))
        .filter { it.isNotBlank() && !it.startsWith('#') }.toSet()

    private fun extracted(app: String): Pair<Set<String>, List<String>> {
        val document = RouteDeclScanner(corpus.resolve(app)).scan(generatedAt = "2026-01-01T00:00:00Z")
        val rows = document.facts.filter { !it.dynamic }.mapTo(sortedSetOf()) { "${it.method}\t${it.channel}\t${it.symbol!!.qualifiedName}" }
        return rows to document.limitations
    }

    @Test
    fun `mvc corpus matches the recorded actuator mappings`() {
        val expected = expected("edge-mvc")
        val (rows, limitations) = extracted("edge-mvc")
        // 기록은 패턴 단위라 빈 값 변형 템플릿이 따로 없다. `/doc/{name}.json`의 부분 세그먼트 변수는 빈 캡처(`/doc/.json`)도 받는다
        // (RegexPathElement 기본 `(.*)`, isthmus `spring/partial-variable-allows-empty`).
        assertEquals(
            setOf("GET\t/api/j/doc/.json\tedge.mvc.EdgeJavaController.partial", "GET\t/api/java/doc/.json\tedge.mvc.EdgeJavaController.partial"),
            rows - expected,
            "every static fact must be a runtime mapping or its empty-value variant",
        )
        // 계약상 dynamic으로 남기는 빈자리다: 다른 프로필만 정한 플레이스홀더(저장소 안 재정의가 있으면 기본값을 쓰지 않는다)와
        // 한 세그먼트의 변수 두 개(정규 템플릿 문법 밖).
        assertEquals(
            setOf(
                "GET\t/api/j/fallback\tedge.mvc.EdgeJavaController.placeholderOtherProfile",
                "GET\t/api/java/fallback\tedge.mvc.EdgeJavaController.placeholderOtherProfile",
                "GET\t/api/j/v{}.{}\tedge.mvc.EdgeJavaController.twoVariables",
                "GET\t/api/java/v{}.{}\tedge.mvc.EdgeJavaController.twoVariables",
            ),
            expected - rows,
        )
        assertTrue(limitations.any { it.startsWith("route-coverage: 1 source file(s) declare functional routes") }, limitations.toString())
        assertTrue(limitations.any { it.startsWith("framework-provided-routes: error endpoint (/error)") }, limitations.toString())
    }

    @Test
    fun `webflux corpus matches the recorded actuator mappings`() {
        val expected = expected("edge-webflux")
        val (rows, limitations) = extracted("edge-webflux")
        assertEquals(expected, rows)
        assertTrue(limitations.none { it.startsWith("framework-provided-routes: error endpoint") }, limitations.toString())
    }

    /**
     * 기록한 프레임워크 매핑(`frameworkPredicates`)이 모두 스코프 있는 `framework-provided-routes:` 한계 안에 드는지 본다 — 스코프가
     * 실제로 서비스되는 경로를 빠뜨리면 그 경로의 호출이 거짓 error가 된다. 함수형 라우터(`/fn`)는 스코프 없는 `route-coverage:`다.
     * 반대로 선언도 프레임워크 경로도 아닌 POST 호출은 어느 스코프에도 들지 않아 error를 판정할 수 있어야 한다.
     */
    @Test
    fun `framework scopes cover every recorded framework mapping`() {
        listOf("edge-mvc", "edge-webflux").forEach { app ->
            val document = RouteDeclScanner(corpus.resolve(app)).scan(generatedAt = "2026-01-01T00:00:00Z")
            val oracle = Files.readString(corpus.resolve("$app/expected-mappings.json"))
            val prefix = PREFIX.find(oracle)!!.groupValues[1]
            // 기록 파일은 원소마다 한 줄인 들여쓴 JSON이다. 술어 문자열에는 따옴표·역슬래시가 없다.
            val predicates = oracle.substringAfter("\"frameworkPredicates\": [").substringBefore("\n  ]").lines()
                .map { it.trim().removeSuffix(",").removeSurrounding("\"") }.filter { it.isNotEmpty() }
            assertTrue(predicates.size >= 5, "$app: recorded framework predicates")
            val framework = document.limitationScopes.filter { it.limitation.startsWith("framework-provided-routes:") }
            assertEquals(document.limitations.count { it.startsWith("framework-provided-routes:") }, framework.size, "$app: every provider is scoped")
            predicates.filterNot { it.startsWith("(") }.forEach { predicate ->
                val (verb, pattern) = probe(predicate)
                val template = prefix + pattern
                val methods = verb?.let(::listOf) ?: listOf("GET", "POST", "PUT", "DELETE")
                methods.forEach { method ->
                    assertTrue(framework.any { RouteLimitationScopes.applies(it, template, method, "root") },
                        "$app: $method $template ($predicate) must be inside a framework scope")
                }
            }
            assertTrue(framework.none { RouteLimitationScopes.applies(it, "$prefix/undeclared/{}", "POST", "root") })
        }
    }

    /** 술어에서 (동사, 경로 표본)을 뽑는다. 리소스 핸들러 패턴(슬래시로 시작)은 GET이다. 변수·catch-all 자리는 리터럴 표본으로 채운다. */
    private fun probe(predicate: String): Pair<String?, String> {
        val verb = VERB.find(predicate)?.groupValues?.get(1) ?: if (predicate.startsWith("/")) "GET" else null
        val path = PATH.find(predicate)!!.value.replace("**", "sample/leaf").replace(Regex("\\{[^}]*}"), "sample")
        return verb to path
    }

    private companion object {
        val PREFIX = Regex("\"prefix\"\\s*:\\s*\"([^\"]*)\"")
        val VERB = Regex("^\\{\\s*([A-Z]+)\\b")
        val PATH = Regex("/[^\\s\\],]*")
    }
}
