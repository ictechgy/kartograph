package dev.kartograph.export

import dev.kartograph.core.HttpWrapperArgument
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** `http-wrappers` v1 선언 파일의 엄격한 검증 계약이다 — 낡거나 잘못된 선언을 조용히 무시하지 않는다. */
class HttpWrappersCodecTest {
    private val valid = """
        {"format": "http-wrappers", "version": 1, "wrappers": [
          {"language": "kotlin", "kind": "constructor", "owner": "dev.example.Endpoint", "name": "<init>",
           "methodArg": {"index": 0, "label": "method"}, "pathArg": {"label": "path"},
           "methodEnum": {"get": "GET", "POST": "POST"}, "pathAnchor": "root", "service": "api"},
          {"language": "swift", "kind": "function", "owner": "Network.Client", "name": "send",
           "pathArg": {"index": 0}, "defaultMethod": "POST", "pathAnchor": "base"}
        ]}
    """.trimIndent()

    @Test
    fun `valid declarations keep every field`() {
        val (kotlin, swift) = HttpWrappersCodec.parse(valid)
        assertEquals(HttpWrapperArgument(0, "method"), kotlin.methodArg)
        assertEquals(HttpWrapperArgument(null, "path"), kotlin.pathArg)
        assertEquals(mapOf("POST" to "POST", "get" to "GET"), kotlin.methodEnum)
        assertEquals("api", kotlin.service)
        assertNull(swift.methodArg)
        assertEquals("POST", swift.defaultMethod)
        assertEquals("base", swift.pathAnchor)
        assertEquals(emptyMap(), swift.methodEnum)
    }

    @Test
    fun `invalid declarations fail with a located reason and without raw values`() {
        val entry = """{"language": "kotlin", "kind": "function", "owner": "a.B", "name": "send", "pathArg": {"index": 0}, "defaultMethod": "GET", "pathAnchor": "root"}"""
        val cases = mapOf(
            "not json" to "not valid JSON",
            "[]" to "top level must be an object",
            """{"format": "other", "version": 1, "wrappers": []}""" to "format must be",
            """{"format": "http-wrappers", "version": 2, "wrappers": []}""" to "version must be 1",
            """{"format": "http-wrappers", "version": 1}""" to "wrappers must be an array",
            """{"format": "http-wrappers", "version": 1, "wrappers": [], "extra": 1}""" to "the document has unknown field",
            """{"format": "http-wrappers", "version": 1, "wrappers": [1]}""" to "wrappers[0] must be an object",
            wrap(entry.replace("\"owner\"", "\"secretOwner\"")) to "wrappers[0] has unknown field",
            wrap(entry.replace("\"function\"", "\"method\"")) to "wrappers[0].kind",
            wrap(entry.replace("\"kotlin\"", "\"rust\"")) to "wrappers[0].language",
            wrap(entry.replace("\"a.B\"", "\"\"")) to "wrappers[0].owner",
            wrap(entry.replace("\"a.B\"", "\"com.\"")) to "wrappers[0].owner must be a dotted kotlin name",
            wrap(entry.replace("\"a.B\"", "\".\"")) to "wrappers[0].owner must be a dotted kotlin name",
            wrap(entry.replace("\"a.B\"", "\"a..B\"")) to "wrappers[0].owner must be a dotted kotlin name",
            wrap(entry.replace("\"send\"", "\"send(\"")) to "wrappers[0].name must be a kotlin identifier",
            wrap(entry.replace(", \"defaultMethod\": \"GET\"", "")) to "needs methodArg or defaultMethod",
            wrap(entry.replace("\"GET\"", "\"get\"")) to "wrappers[0].defaultMethod",
            wrap(entry.replace("\"root\"", "\"host\"")) to "wrappers[0].pathAnchor",
            wrap(entry.replace("{\"index\": 0}", "{}")) to "pathArg requires index or label",
            wrap(entry.replace("{\"index\": 0}", "{\"index\": 300}")) to "pathArg.index",
            wrap(entry.replace("{\"index\": 0}", "{\"index\": 0, \"name\": 1}")) to "pathArg has unknown field",
            wrap(entry.replace("{\"index\": 0}", "[0]")) to "pathArg must be an object",
            wrap(entry.replace(", \"pathArg\": {\"index\": 0}", "")) to "pathArg is required",
            wrap(entry.replace("\"function\"", "\"constructor\"")) to "must be \"<init>\"",
            wrap(entry.replace("\"pathAnchor\"", "\"methodEnum\": {\"get\": \"FETCH\"}, \"pathAnchor\"")) to "methodEnum value",
            wrap(entry.replace("\"pathAnchor\"", "\"methodEnum\": [], \"pathAnchor\"")) to "methodEnum must be an object",
            wrap(entry.replace("\"pathAnchor\"", "\"service\": \"a\\u0001b\", \"pathAnchor\"")) to "wrappers[0].service",
        )
        cases.forEach { (content, reason) ->
            val failure = assertFailsWith<IllegalArgumentException>(content) { HttpWrappersCodec.parse(content) }
            assertContains(failure.message.orEmpty(), reason)
            assertContains(failure.message.orEmpty(), "http-wrappers v1")
            assertFalse(failure.message.orEmpty().contains("secretOwner"))
        }
        assertFailsWith<IllegalArgumentException> { HttpWrappersCodec.parse(" ".repeat(1024 * 1024 + 1)) }
        assertFailsWith<IllegalArgumentException> {
            HttpWrappersCodec.parse(wrap(List(1001) { entry }.joinToString(",")))
        }
    }

    private fun wrap(entries: String): String = """{"format": "http-wrappers", "version": 1, "wrappers": [$entries]}"""

    @Test
    fun `non kotlin owners keep their own symbol rules`() {
        val js = """{"format": "http-wrappers", "version": 1, "wrappers": [
            {"language": "js", "kind": "function", "owner": "@scope/api-client", "name": "request",
             "pathArg": {"label": "url"}, "defaultMethod": "GET", "pathAnchor": "base"}]}"""
        assertEquals("@scope/api-client", HttpWrappersCodec.parse(js).single().owner)
    }
}
