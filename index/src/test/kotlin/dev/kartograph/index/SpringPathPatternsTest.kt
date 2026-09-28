package dev.kartograph.index

import dev.kartograph.core.RouteParamConstraint
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Spring `PathPattern` → 정규 템플릿 변환 규칙을 하나씩 고정한다. 근거는 spring-web 7.0.8 소스다. */
class SpringPathPatternsTest {
    private fun template(pattern: String): String? = SpringPathPatterns.convert(pattern).template

    @Test
    fun `leading slash is added only to non-empty patterns`() {
        assertEquals("/users", SpringPathPatterns.initFullPathPattern("users"))
        assertEquals("/users", SpringPathPatterns.initFullPathPattern("/users"))
        assertEquals("", SpringPathPatterns.initFullPathPattern(""))
    }

    @Test
    fun `class and method patterns combine like PathPattern combine`() {
        assertEquals("/m", SpringPathPatterns.combine("", "/m"))
        assertEquals("/t", SpringPathPatterns.combine("/t", ""))
        assertEquals("/t/m", SpringPathPatterns.combine("/t", "/m"))
        assertEquals("/t/m", SpringPathPatterns.combine("/t/", "/m"))
        assertEquals("/t/m", SpringPathPatterns.combine("/t/", "m"))
        assertEquals("/t/m", SpringPathPatterns.combine("/t", "m"))
        assertEquals("/api/", SpringPathPatterns.combine("/api", "/"))
        assertEquals("/{id}/x", SpringPathPatterns.combine("/{id}", "/x"))
        // 클래스 패턴의 글롭은 combine이 경로 매칭으로 결과를 고르므로 옮기지 않는다.
        assertNull(SpringPathPatterns.combine("/hotels/*", "/booking"))
        assertNull(SpringPathPatterns.combine("/files/**", "/x"))
        assertNull(SpringPathPatterns.combine("/a{", "/x"))
    }

    @Test
    fun `literals keep slashes and case and are percent-normalized`() {
        assertEquals("/", template(""))
        assertEquals("/", template("/"))
        assertEquals("/Owners/new/", template("/Owners/new/"))
        assertEquals("/a//b", template("/a//b"))
        assertEquals("/caf%C3%A9/a%20b", template("/café/a b"))
        assertEquals("/vets.html", template("/vets.html"))
        assertNull(template("relative"))
    }

    @Test
    fun `variables become whole or partial parameter segments`() {
        assertEquals("/owners/{}/pets/{}", template("/owners/{ownerId}/pets/{petId}"))
        assertEquals("/files/{}.json", template("/files/{name}.json"))
        assertEquals("/x/{}/y", template("/x/*/y"))
        assertEquals("/x/{}", template("/x/*"))
    }

    @Test
    fun `shapes without a template form are dynamic`() {
        listOf("/v{major}.{minor}", "/t?st", "/files/*.png", "/a/**/b", "/a/{*rest}/b", "/a/{", "/a/}", "/a/{:x}", "/a/{*}x").forEach { pattern ->
            val converted = SpringPathPatterns.convert(pattern)
            assertNull(converted.template, pattern)
            assertEquals(true, converted.dynamicReason != null, pattern)
        }
    }

    @Test
    fun `final catch-all expands to the prefix declaration`() {
        val capture = SpringPathPatterns.convert("/files/{*path}")
        assertEquals("/files/{**}", capture.template)
        assertEquals("/files", capture.catchAllPrefix)
        val wildcard = SpringPathPatterns.convert("/static/**")
        assertEquals("/static/{**}", wildcard.template)
        assertEquals("/static", wildcard.catchAllPrefix)
        val root = SpringPathPatterns.convert("/**")
        assertEquals("/{**}", root.template)
        assertEquals("/", root.catchAllPrefix)
        assertNull(SpringPathPatterns.convert("/files").catchAllPrefix)
    }

    @Test
    fun `regex constraints narrow to closed kinds only when contained`() {
        val constraints = SpringPathPatterns.convert("/u/{id:\\d+}/s/{s:[a-z0-9-]+}/c/{c:[A-Z]{3}}").constraints
        assertEquals(
            listOf(
                RouteParamConstraint(1, "int"),
                RouteParamConstraint(3, "slug"),
                RouteParamConstraint(5, "regex", "[A-Z]{3}"),
            ),
            constraints,
        )
        val uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        assertEquals("uuid", SpringPathPatterns.constraint(0, uuid).kind)
        assertEquals("uuid", SpringPathPatterns.constraint(0, uuid.replace("0-9a-f", "0-9a-fA-F")).kind)
        assertEquals("regex", SpringPathPatterns.constraint(0, "[0-9a-z]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").kind)
        assertEquals("regex", SpringPathPatterns.constraint(0, "[0-9a-f]{8}-[0-9a-f]{4}").kind)
        listOf("[0-9]+", "-?\\d+", "[+-]?[0-9]+", "\\d*").forEach { assertEquals("int", SpringPathPatterns.constraint(0, it).kind, it) }
        listOf("\\w+", "[a-zA-Z0-9_-]+", "[a-z]*", "[\\w\\-]+").forEach { assertEquals("slug", SpringPathPatterns.constraint(0, it).kind, it) }
        listOf("[a-f]+", "[.a-z]+", "\\d{2}", "[a-z]", ".+").forEach { assertEquals("regex", SpringPathPatterns.constraint(0, it).kind, it) }
        assertNull(SpringPathPatterns.constraint(0, "").pattern)
    }

    @Test
    fun `partial segment constraint points at the partial segment`() {
        val converted = SpringPathPatterns.convert("/files/{name:[a-z]+}.json")
        assertEquals("/files/{}.json", converted.template)
        assertEquals(listOf(RouteParamConstraint(1, "slug")), converted.constraints)
    }

    /**
     * isthmus `http-template` 생산자 정규화 케이스를 서버 변환기로도 돌린다. `normalize/literal-braces`만 뺀다 — Spring
     * 패턴의 `{x}`는 리터럴 중괄호가 아니라 경로 변수라서 서버 생산자는 `{}`를 낸다.
     */
    @Test
    fun `shared normalize vectors hold for literal pattern segments`() {
        val vectors = Path.of("../fixtures/isthmus-conformance/http-template.json")
        val document = McpLikeJson.cases(Files.readString(vectors)).filter { it.first == "template.normalize" && it.second != "normalize/literal-braces" }
        assertEquals(6, document.size)
        document.forEach { (_, id, input, expected) -> assertEquals(expected, template(input), id) }
        assertEquals("/tpl/{}", template("/tpl/{x}"))
    }

    /** 벡터 파일에서 (ruleId, id, input.path, expect.template)만 뽑는 작은 reader다. index 모듈은 JSON 코덱에 의존하지 않는다. */
    private object McpLikeJson {
        private val CASE = Regex("\\{\\s*\"id\"\\s*:\\s*\"([^\"]+)\".*?\"ruleId\"\\s*:\\s*\"([^\"]+)\".*?\"input\"\\s*:\\s*\\{([^}]*)}.*?\"expect\"\\s*:\\s*\\{([^}]*)}", RegexOption.DOT_MATCHES_ALL)
        private val PATH = Regex("\"path\"\\s*:\\s*\"([^\"]*)\"")
        private val TEMPLATE = Regex("\"template\"\\s*:\\s*\"([^\"]*)\"")

        fun cases(text: String): List<Quad> = CASE.findAll(text).mapNotNull { match ->
            val path = PATH.find(match.groupValues[3])?.groupValues?.get(1) ?: return@mapNotNull null
            val template = TEMPLATE.find(match.groupValues[4])?.groupValues?.get(1) ?: return@mapNotNull null
            Quad(match.groupValues[2], match.groupValues[1], path, template)
        }.toList()
    }

    private data class Quad(val first: String, val second: String, val third: String, val fourth: String)
}
