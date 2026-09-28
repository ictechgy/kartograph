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
        assertEquals(emptySet(), rows - expected, "every static fact must be a runtime mapping")
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
}
