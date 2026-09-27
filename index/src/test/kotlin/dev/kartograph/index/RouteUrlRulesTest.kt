package dev.kartograph.index

import dev.kartograph.core.HttpWrapperArgument
import dev.kartograph.index.RouteUrlRules.ArgumentValue
import dev.kartograph.index.RouteUrlRules.CallArgument
import dev.kartograph.index.RouteUrlRules.JoinMode
import dev.kartograph.index.RouteUrlRules.UrlPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 공유 벡터가 덮지 않는 경계(동적 host, 선언 앵커, 깨진 퍼센트 등)를 규칙 단위로 고정한다. */
class RouteUrlRulesTest {
    private fun literal(text: String) = UrlPart.Literal(text)
    private fun value(text: String) = UrlPart.Value(text)

    @Test
    fun `declared anchor keeps the user declaration and roots relative paths`() {
        val composed = RouteUrlRules.compose(listOf(literal("items/"), value("id")), JoinMode.Declared("base"))
        assertEquals("/items/{}", composed.template)
        assertEquals("base", composed.pathAnchor)
    }

    @Test
    fun `declared wrapper with an unresolved leading base joins like string concatenation`() {
        val rooted = RouteUrlRules.compose(listOf(value("base"), literal("/v1/items")), JoinMode.Declared("root"))
        assertEquals("/v1/items", rooted.template)
        assertEquals("base", rooted.pathAnchor)

        val relative = RouteUrlRules.compose(listOf(value("base"), literal("items")), JoinMode.Declared("root"))
        assertTrue(relative.dynamic)
        assertEquals("ambiguous-base-join:", relative.limitation)

        val opaque = RouteUrlRules.compose(listOf(value("base"), value("path")), JoinMode.Declared("root"))
        assertTrue(opaque.dynamic)
        assertNull(opaque.limitation)
    }

    @Test
    fun `absolute url with an interpolated host is base anchored without authority`() {
        val composed = RouteUrlRules.compose(
            listOf(literal("https://api."), value("env"), literal(".example.com/v1/items?x=1")),
            JoinMode.Concat(null),
        )
        assertEquals("/v1/items", composed.template)
        assertEquals("base", composed.pathAnchor)
        assertNull(composed.authority)
        assertTrue(composed.queryTailStripped)

        val hostOnly = RouteUrlRules.compose(listOf(literal("https://"), value("host")), JoinMode.Concat(null))
        assertEquals("/", hostOnly.template)
        assertEquals("base", hostOnly.pathAnchor)
    }

    @Test
    fun `invalid authority is dropped instead of emitting a rejected field`() {
        val composed = RouteUrlRules.compose(listOf(literal("https://bad_host!/x")), JoinMode.PathOnly)
        assertEquals("/x", composed.template)
        assertNull(composed.authority)
    }

    @Test
    fun `slash join is always base and empty input is dynamic`() {
        assertEquals("base", RouteUrlRules.compose(listOf(value("b"), literal("x")), JoinMode.SlashJoin).pathAnchor)
        assertTrue(RouteUrlRules.compose(listOf(value("b"), value("c")), JoinMode.SlashJoin).dynamic)
        assertTrue(RouteUrlRules.compose(emptyList(), JoinMode.PathOnly).dynamic)
        assertTrue(RouteUrlRules.compose(listOf(literal("")), JoinMode.PathOnly).dynamic)
    }

    @Test
    fun `a value right after another value is a partial segment`() {
        val composed = RouteUrlRules.compose(listOf(literal("/a/"), value("x"), value("y")), JoinMode.PathOnly)
        assertTrue(composed.dynamic)
        assertEquals("/a/", composed.channelPrefix)
    }

    @Test
    fun `channel prefix is masked like the template`() {
        val composed = RouteUrlRules.compose(
            listOf(literal("/v1/a1b2c3d4e5f6a7b8c9/"), value("name"), literal(".json")),
            JoinMode.PathOnly,
        )
        assertTrue(composed.dynamic)
        assertEquals("/v1/{}/", composed.channelPrefix)
    }

    @Test
    fun `normalize encodes malformed percent and keeps astral characters valid`() {
        assertEquals("/a%25zz/%252", RouteUrlRules.normalizePath("/a%zz/%2"))
        assertEquals("/%F0%9F%98%80", RouteUrlRules.normalizePath("/😀"))
    }

    @Test
    fun `validate reports too long templates and control characters`() {
        assertEquals("too-long", RouteUrlRules.validateTemplate("/" + "a".repeat(RouteUrlRules.MAX_TEMPLATE_LENGTH)))
        assertEquals("invalid-character", RouteUrlRules.validateTemplate("/a\u0001"))
        assertNull(RouteUrlRules.validateTemplate("/files/{**}"))
    }

    @Test
    fun `mask counts webhook segments only for known hosts and skips empty segments`() {
        assertEquals("/{}//{}" to 2, RouteUrlRules.mask("/a//b", "hooks.slack.com"))
        assertEquals("/api/webhooks" to 0, RouteUrlRules.mask("/api/webhooks", "discordapp.com:443"))
        assertEquals("/x/%41" to 0, RouteUrlRules.mask("/x/%41", null))
        assertEquals("/{}" to 1, RouteUrlRules.mask("/abcdefghijklmn%31%32", null))
    }

    @Test
    fun `argument binding prefers labels and ignores positions taken by other labels`() {
        val spec = HttpWrapperArgument(index = 0, label = "method")
        val labelled = listOf(CallArgument("path", ArgumentValue.Literal("/x")), CallArgument("method", ArgumentValue.Literal("PUT")))
        assertEquals(1, RouteUrlRules.findArgumentIndex(spec, labelled))
        val positionTaken = listOf(CallArgument("path", ArgumentValue.Literal("/x")))
        assertNull(RouteUrlRules.findArgumentIndex(spec, positionTaken))
        assertNull(RouteUrlRules.findArgumentIndex(null, positionTaken))
        assertNull(RouteUrlRules.findArgumentIndex(HttpWrapperArgument(5, null), positionTaken))
    }

    @Test
    fun `method binding maps enum cases, rejects opaque values and uses defaults only when omitted`() {
        val spec = HttpWrapperArgument(index = 0, label = null)
        val enum = mapOf("Post" to "POST")
        assertEquals("POST", RouteUrlRules.bindMethod(spec, null, enum, listOf(CallArgument(null, ArgumentValue.EnumCase("Post")))))
        assertNull(RouteUrlRules.bindMethod(spec, "GET", enum, listOf(CallArgument(null, ArgumentValue.Opaque))))
        assertEquals("GET", RouteUrlRules.bindMethod(spec, "GET", enum, emptyList()))
        assertEquals("DELETE", RouteUrlRules.bindMethod(null, "DELETE", enum, emptyList()))
    }

    @Test
    fun `network path references split authority and drop userinfo`() {
        val literal = RouteUrlRules.compose(listOf(literal("//user:pw@CDN.example.com/x?y=1")), JoinMode.PathOnly)
        assertEquals("/x", literal.template)
        assertEquals("cdn.example.com", literal.authority)
        assertEquals("root", literal.pathAnchor)

        val scheme = RouteUrlRules.compose(listOf(value("scheme"), literal("//user:pw@example.com")), JoinMode.Concat(null))
        assertEquals("/", scheme.template)
        assertEquals("example.com", scheme.authority)

        val prefix = RouteUrlRules.compose(listOf(value("scheme"), literal("//user:pw@example.com/a"), value("b")), JoinMode.Declared("root"))
        assertTrue(prefix.dynamic)
        assertEquals("/a", prefix.channelPrefix)
    }
}
