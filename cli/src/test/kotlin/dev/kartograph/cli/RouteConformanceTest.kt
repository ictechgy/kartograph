package dev.kartograph.cli

import dev.kartograph.core.HttpWrapperArgument
import dev.kartograph.core.HttpWrapperDeclaration
import dev.kartograph.export.McpJsonCodec
import dev.kartograph.index.RouteCallScanner
import dev.kartograph.index.RouteUrlRules
import dev.kartograph.index.RouteUrlRules.ArgumentValue
import dev.kartograph.index.RouteUrlRules.CallArgument
import dev.kartograph.index.RouteUrlRules.ComposedRoute
import dev.kartograph.index.RouteUrlRules.JoinMode
import dev.kartograph.index.RouteUrlRules.UrlPart
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.io.TempDir

/**
 * isthmus 공유 적합성 벡터(`http-template`·`url-compose`)의 생산자 케이스를 kartograph 규칙으로 실행한다.
 *
 * 벤더링한 파일의 sha256을 `conformance.lock`과 먼저 대조한다. 생산자 케이스의 규칙 식별자를 모르면
 * 건너뛰지 않고 실패한다 — 새 규칙이 조용히 미검증으로 남지 않게 하기 위해서다.
 */
class RouteConformanceTest {
    @TempDir
    lateinit var project: Path

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/$name")) { "missing vendored conformance file $name" }.use { it.readAllBytes() }

    private fun document(name: String): Map<*, *> = McpJsonCodec.parse(resource(name).toString(Charsets.UTF_8)) as Map<*, *>

    private fun producerCases(name: String): List<Map<*, *>> {
        val suite = document(name)
        assertEquals<Any?>("isthmus-conformance", suite["format"])
        assertEquals<Any?>(1L, suite["version"])
        return (suite["cases"] as List<*>).map { it as Map<*, *> }
            .filter { case -> (case["appliesTo"] as List<*>).any { it == "producer" } }
    }

    @Test
    fun `vendored vectors match the lock file`() {
        val lock = document("conformance.lock")
        assertEquals<Any?>("isthmus-conformance-lock", lock["format"])
        val files = lock["files"] as Map<*, *>
        assertEquals<Any?>(setOf("http-template.json", "url-compose.json"), files.keys)
        files.forEach { (name, expected) ->
            val digest = MessageDigest.getInstance("SHA-256").digest(resource(name as String)).joinToString("") { "%02x".format(it) }
            assertEquals<Any?>(expected, digest, "vendored $name differs from conformance.lock; re-vendor from isthmus")
        }
        assertTrue((lock["isthmus"] as Map<*, *>)["commit"].toString().matches(Regex("[0-9a-f]{40}")))
    }

    @Test
    fun `http-template producer cases pass`() {
        val cases = producerCases("http-template.json")
        cases.forEach { case ->
            val input = case["input"] as Map<*, *>
            val expect = case["expect"] as Map<*, *>
            when (case["ruleId"]) {
                "template.grammar" -> {
                    val reason = RouteUrlRules.validateTemplate(input["template"] as String)
                    assertEquals<Any?>(expect["valid"], reason == null, case["id"].toString())
                    expect["reason"]?.let { assertEquals<Any?>(it, reason, case["id"].toString()) }
                }
                "template.normalize" ->
                    assertEquals<Any?>(expect["template"], RouteUrlRules.normalizePath(input["path"] as String), case["id"].toString())
                else -> fail("unknown producer rule ${case["ruleId"]} in ${case["id"]}; implement it before vendoring")
            }
        }
        assertTrue(cases.size >= 30, "expected the grammar and normalize producer cases, got ${cases.size}")
    }

    @Test
    fun `url-compose producer cases pass`() {
        val cases = producerCases("url-compose.json")
        cases.forEach { case ->
            val id = case["id"].toString()
            val input = case["input"] as Map<*, *>
            val expect = case["expect"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val expectDynamic = case["expectDynamic"] == true
            when (case["ruleId"]) {
                "compose.interpolation", "compose.query-tail", "compose.suffix", "compose.normalize" ->
                    checkComposed(id, RouteUrlRules.compose(parts(input["parts"] as List<*>), JoinMode.PathOnly), expect, expectDynamic, case)
                "compose.base-join" -> {
                    val composed = RouteUrlRules.compose(listOf(UrlPart.Literal(input["path"] as String)), joinMode(input))
                    checkComposed(id, composed, expect, expectDynamic, case)
                }
                "compose.strip" ->
                    checkComposed(id, RouteUrlRules.compose(listOf(UrlPart.Literal(input["url"] as String)), JoinMode.PathOnly), expect, expectDynamic, case)
                "compose.mask" -> {
                    val (template, count) = RouteUrlRules.mask(input["template"] as String, input["authority"] as String?)
                    assertEquals<Any?>(expect["template"], template, id)
                    assertEquals<Any?>(expect["maskedSegments"], count.toLong(), id)
                }
                "wrapper.method" -> checkMethod(id, input, expect, expectDynamic)
                "wrapper.location" -> checkLocation(id, input, expect)
                else -> fail("unknown producer rule ${case["ruleId"]} in $id; implement it before vendoring")
            }
        }
        assertTrue(cases.size >= 40, "expected every url-compose producer case, got ${cases.size}")
    }

    /** 적힌 키만 비교한다(계약의 벡터 비교 규칙). */
    private fun checkComposed(id: String, composed: ComposedRoute, expect: Map<*, *>, expectDynamic: Boolean, case: Map<*, *>) {
        assertEquals<Any?>(expectDynamic, composed.dynamic, "$id dynamic")
        expect["template"]?.let { assertEquals<Any?>(it, composed.template, "$id template") }
        expect["channelPrefix"]?.let { assertEquals<Any?>(it, composed.channelPrefix, "$id channelPrefix") }
        expect["queryTailStripped"]?.let { assertEquals<Any?>(it, composed.queryTailStripped, "$id queryTailStripped") }
        expect["pathAnchor"]?.let { assertEquals<Any?>(it, composed.pathAnchor, "$id pathAnchor") }
        expect["authority"]?.let { assertEquals<Any?>(it, composed.authority, "$id authority") }
        (case["expectLimitation"] as String?)?.let { assertEquals<Any?>(it, composed.limitation, "$id limitation") }
    }

    private fun parts(values: List<*>): List<UrlPart> = values.map { raw ->
        val part = raw as Map<*, *>
        when {
            "literal" in part -> UrlPart.Literal(part["literal"] as String)
            "value" in part -> UrlPart.Value(part["value"] as String)
            "queryTail" in part -> UrlPart.QueryTail(part["queryTail"] as String)
            else -> fail("unknown part shape $part")
        }
    }

    private fun joinMode(input: Map<*, *>): JoinMode = when (input["join"]) {
        "rfc3986" -> JoinMode.Rfc3986
        "slash-join" -> JoinMode.SlashJoin
        "dio-concat" -> JoinMode.Concat(input["base"] as String?)
        else -> fail("unknown join ${input["join"]}")
    }

    private fun checkMethod(id: String, input: Map<*, *>, expect: Map<*, *>, expectDynamic: Boolean) {
        val declaration = input["declaration"] as Map<*, *>
        val arguments = ((input["call"] as Map<*, *>)["args"] as List<*>).map { raw ->
            val argument = raw as Map<*, *>
            val value = argument["value"] as Map<*, *>
            CallArgument(
                argument["label"] as String?,
                when {
                    "literal" in value -> ArgumentValue.Literal(value["literal"] as String)
                    "enumCase" in value -> ArgumentValue.EnumCase(value["enumCase"] as String)
                    else -> ArgumentValue.Opaque
                },
            )
        }
        val method = RouteUrlRules.bindMethod(
            argumentSpec(declaration["methodArg"] as Map<*, *>?),
            declaration["defaultMethod"] as String?,
            (declaration["methodEnum"] as Map<*, *>?).orEmpty().entries.associate { (key, verb) -> key as String to verb as String },
            arguments,
        )
        if (expectDynamic) assertEquals<Any?>(null, method, id) else assertEquals<Any?>(expect["method"], method, id)
    }

    private fun argumentSpec(value: Map<*, *>?): HttpWrapperArgument? =
        value?.let { HttpWrapperArgument((it["index"] as Long?)?.toInt(), it["label"] as String?) }

    /** 실제 스캐너로 여러 줄 호출을 읽어 호출식 시작 줄을 보고하는지 확인한다. */
    private fun checkLocation(id: String, input: Map<*, *>, expect: Map<*, *>) {
        val callLine = (input["callStartLine"] as Long).toInt()
        val methodLine = (input["methodArgLine"] as Long).toInt()
        val pathLine = (input["pathArgLine"] as Long).toInt()
        val lines = MutableList(pathLine + 3) { "" }
        lines[0] = "package conformance"
        lines[callLine - 1] = "fun caller() = request("
        lines[methodLine - 1] = "    method = \"GET\","
        lines[pathLine - 1] = "    path = \"/x\","
        lines[pathLine] = ")"
        lines[pathLine + 1] = "fun request(method: String, path: String) = path"
        project.resolve("Conformance.kt").writeText(lines.joinToString("\n") + "\n")
        val wrapper = HttpWrapperDeclaration(
            language = "kotlin", kind = "function", owner = "conformance", name = "request",
            methodArg = HttpWrapperArgument(null, "method"), pathArg = HttpWrapperArgument(null, "path"),
            defaultMethod = null, methodEnum = emptyMap(), pathAnchor = "root", service = null,
        )
        val fact = RouteCallScanner(project, wrappers = listOf(wrapper)).scan(generatedAt = "2026-01-01T00:00:00Z").facts.single()
        assertEquals<Any?>(expect["line"], fact.location.line.toLong(), id)
        assertEquals<Any?>("GET", fact.method, id)
    }
}
