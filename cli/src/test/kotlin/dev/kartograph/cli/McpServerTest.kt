package dev.kartograph.cli

import dev.kartograph.export.McpJsonCodec
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

internal class McpTestClient(run: (InputStream, PrintStream, PrintStream) -> Int) : AutoCloseable {
    private val pipe = PipedInputStream(1024 * 1024)
    private val writer = PipedOutputStream(pipe)
    private val lines = LinkedBlockingQueue<String>()
    val errors = ByteArrayOutputStream()
    var status: Int? = null
    private val output = object : OutputStream() {
        val buffer = ByteArrayOutputStream()
        @Volatile var failed = false
        override fun write(value: Int) {
            if (failed) throw IOException("simulated broken MCP stdout")
            if (value == 10) { lines.add(buffer.toString(Charsets.UTF_8)); buffer.reset() } else buffer.write(value)
        }
    }
    private val thread = Thread { status = run(pipe, PrintStream(output, true, Charsets.UTF_8), PrintStream(errors)) }.apply { isDaemon = true; start() }
    fun raw(bytes: ByteArray) { writer.write(bytes); writer.flush() }
    fun raw(text: String) = raw((text + "\n").toByteArray())
    fun send(method: String, id: Any? = null, params: Map<String, Any?> = emptyMap()) = raw(McpJsonCodec.render(buildMap {
        put("jsonrpc", "2.0"); put("method", method); if (id != null) put("id", id); put("params", params)
    }))
    fun receive(): Map<*, *> = McpJsonCodec.parse(requireNotNull(lines.poll(5, TimeUnit.SECONDS)) { "No MCP response; ${errors}" }) as Map<*, *>
    fun request(method: String, id: Any = 1L, params: Map<String, Any?> = emptyMap()): Map<*, *> { send(method, id, params); return receive() }
    fun initialize(version: String = "2025-11-25") {
        val response = request("initialize", params = mapOf("protocolVersion" to version, "capabilities" to emptyMap<String, Any?>(),
            "clientInfo" to mapOf("name" to "test", "version" to "1")))
        assertEquals("2025-11-25", (response["result"] as Map<*, *>)["protocolVersion"])
        send("notifications/initialized")
    }
    fun call(name: String, arguments: Map<String, Any?> = emptyMap()): Map<*, *> =
        request("tools/call", params = mapOf("name" to name, "arguments" to arguments))["result"] as Map<*, *>
    override fun close() { writer.close(); thread.join(2000); assertFalse(thread.isAlive, "EOF must exit promptly") }
    fun failOutput() { output.failed = true }
    fun awaitExit(): Int? { thread.join(2_000); assertFalse(thread.isAlive, "transport failure must end the session"); return status }
    fun assertNoResponse() { assertNull(lines.poll(100, TimeUnit.MILLISECONDS)) }
}

class McpServerTest {
    private fun client(call: (String, Map<String, Any?>) -> Map<String, Any?> = { _, _ -> mapOf("content" to emptyList<Any?>()) }) =
        McpTestClient { input, output, error -> McpServer(call).run(input, output, error) }
    private fun code(response: Map<*, *>) = (response["error"] as Map<*, *>)["code"]

    @Test fun `legacy handshake list ping exact ids notifications and EOF`() {
        client().use { c ->
            assertEquals(-32601L, code(c.request("server/discover")))
            assertEquals(-32600L, code(c.request("tools/list")))
            c.initialize("2026-07-28")
            val tools = ((c.request("tools/list")["result"] as Map<*, *>)["tools"] as List<*>).map { (it as Map<*, *>)["name"] }
            assertEquals(listOf("query_symbol", "impact", "freshness"), tools)
            c.raw("""{"jsonrpc":"2.0","id":123456789012345678901234567890,"method":"ping"}""")
            assertEquals(java.math.BigInteger("123456789012345678901234567890"), c.receive()["id"])
            assertEquals("ID\ntext", c.request("ping", "ID\ntext")["id"])
            c.send("ping"); c.send("unknown"); c.send("tools/call", params = mapOf("name" to "freshness"))
            c.assertNoResponse()
            assertEquals(-32600L, code(c.request("initialize")))
            assertEquals(-32601L, code(c.request("server/discover")))
            assertEquals(-32602L, code(c.request("tools/list", params = mapOf("cursor" to "bad"))))
            assertEquals(false, c.call("freshness").containsKey("isError"))
            assertEquals("", c.errors.toString())
        }
    }

    @Test fun `malformed frames strict ids and invalid envelopes are bounded and recoverable`() {
        client().use { c ->
            for (json in listOf("[]", "null", "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"ping\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"ping\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":[]}", "{}")) {
                c.raw(json); assertEquals(-32600L, code(c.receive()))
            }
            for (json in listOf("{private-malformed", "[".repeat(34) + "]".repeat(34), "{\"a\":1,\"a\":2}")) {
                c.raw(json); val response = c.receive(); assertEquals(-32700L, code(response)); assertFalse(response.toString().contains("private-malformed"))
            }
            c.raw(byteArrayOf(0xff.toByte(), 10)); assertEquals(-32700L, code(c.receive()))
            c.raw("x".repeat(McpServer.MAX_REQUEST + 1)); assertEquals(-32700L, code(c.receive()))
            assertEquals(emptyMap<Any, Any>(), c.request("ping")["result"])
        }
    }

    @Test fun `initialized notification is required and notification initialize cannot change state`() {
        client().use { c ->
            c.send("initialize", params = mapOf("protocolVersion" to "2025-11-25", "capabilities" to emptyMap<String, Any?>(),
                "clientInfo" to mapOf("name" to "test", "version" to "1")))
            c.send("notifications/initialized")
            assertEquals(-32600L, code(c.request("tools/list")))
            c.initialize()
            assertNotNull(c.request("tools/list")["result"])
        }
    }

    @Test fun `initialized accepts sole metadata object and ignores invalid notifications without responding`() {
        client().use { c ->
            c.send("initialize", 1L, mapOf("protocolVersion" to "2025-11-25", "capabilities" to emptyMap<String, Any?>(),
                "clientInfo" to mapOf("name" to "test", "version" to "1")))
            assertNotNull(c.receive()["result"])
            c.send("notifications/initialized", params = mapOf("_meta" to "invalid"))
            c.assertNoResponse()
            assertEquals(-32600L, code(c.request("tools/list")))
            c.send("notifications/initialized", params = mapOf("_meta" to mapOf("progressToken" to "ready")))
            assertNotNull(c.request("tools/list")["result"])
        }
    }

    @Test fun `one bounded worker rejects overload duplicate ids and suppresses cancellation`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        client { _, _ -> entered.countDown(); release.await(); finished.countDown(); mapOf("content" to emptyList<Any?>()) }.use { c ->
            c.initialize()
            c.send("tools/call", "work", mapOf("name" to "freshness"))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(-32000L, code(c.request("tools/call", "busy", mapOf("name" to "freshness"))))
            val duplicate = c.request("ping", "work")
            assertNull(duplicate["id"]); assertEquals(-32600L, code(duplicate))
            c.send("notifications/cancelled", params = mapOf("requestId" to "work"))
            assertNotNull(c.request("ping", "barrier")["result"])
            release.countDown(); assertTrue(finished.await(2, TimeUnit.SECONDS)); c.assertNoResponse()
        }
    }

    @Test fun `EOF abandons blocked work without waiting and output errors are sanitized`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val c = client { _, _ -> entered.countDown(); release.await(); emptyMap() }
        c.initialize(); c.send("tools/call", "work", mapOf("name" to "freshness")); assertTrue(entered.await(2, TimeUnit.SECONDS))
        c.close(); release.countDown(); assertEquals(0, c.status); c.assertNoResponse()
        val errors = ByteArrayOutputStream()
        val status = McpServer { _, _ -> emptyMap() }.run(object : InputStream() {
            override fun read(): Int = throw IOException("private path")
        }, PrintStream(ByteArrayOutputStream()), PrintStream(errors))
        assertEquals(2, status); assertFalse(errors.toString().contains("private path"))
    }

    @Test fun `oversized tool output and operation exceptions are tool errors with valid JSON`() {
        for (action in listOf<(String, Map<String, Any?>) -> Map<String, Any?>>(
            { _, _ -> mapOf("content" to listOf(mapOf("type" to "text", "text" to "x".repeat(McpServer.MAX_RESPONSE)))) },
            { _, _ -> throw IOException("private path and details") })) {
            client(action).use { c ->
                c.initialize(); val result = c.call("freshness")
                assertEquals(true, result["isError"]); assertFalse(result.toString().contains("private path"))
                assertTrue(McpJsonCodec.render(result).length < McpServer.MAX_RESPONSE)
            }
        }
    }

    @Test fun `async stdout failure ends the transport while stdin remains open`() {
        val c = client()
        try {
            c.initialize()
            c.failOutput()
            c.send("tools/call", "work", mapOf("name" to "freshness"))

            assertEquals(2, c.awaitExit())
            assertContains(c.errors.toString(), "MCP transport failed")
            assertFalse(c.errors.toString().contains("simulated broken MCP stdout"))
        } finally {
            c.close()
        }
    }

    @Test fun `fatal tool error ends the session without leaking details or staying busy`() {
        val c = client { _, _ -> throw StackOverflowError("private failure details") }
        try {
            c.initialize()
            c.send("tools/call", "work", mapOf("name" to "freshness"))
            assertEquals(2, c.awaitExit())
            assertFalse(c.errors.toString().contains("private failure details"))
            c.assertNoResponse()
        } finally {
            c.close()
        }
    }
}
