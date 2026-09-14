package dev.kartograph.cli

import dev.kartograph.core.KartographVersion
import dev.kartograph.export.McpJsonCodec
import java.io.InputStream
import java.io.PrintStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** reader는 취소와 EOF를 처리하고, 최대 한 개의 도구 작업만 별도 daemon에서 실행한다. */
internal class McpServer(private val call: (String, Map<String, Any?>) -> Map<String, Any?>) {
    private enum class State { NEW, INITIALIZING, READY }
    private class Pending(val id: Any) { var canceled = false }
    private var state = State.NEW
    private val lock = Any()
    private var closed = false
    private var active: Pending? = null

    fun run(input: InputStream, output: PrintStream, error: PrintStream): Int {
        val readerThread = Thread.currentThread()
        val transportFailed = AtomicBoolean()
        fun failTransport() {
            transportFailed.set(true)
            synchronized(lock) { closed = true; active?.canceled = true; active = null }
            // buffered reader의 잠금 뒤에서 기다리지 않도록 원본 입력을 닫는다.
            try {
                input.close()
            } catch (_: Exception) {
                // 이미 실패한 세션은 아래 interrupt와 reader의 종료 경로로 정리한다.
            } finally {
                readerThread.interrupt()
            }
        }
        val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
            { task -> Thread(task, "kartograph-mcp-tool").apply {
                isDaemon = true
                // Error로 worker가 끝나도 원시 stack trace나 영구 busy 상태를 남기지 않는다.
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> failTransport() }
            } }, ThreadPoolExecutor.AbortPolicy())
        fun send(value: Any?) {
            val text = McpJsonCodec.render(value, MAX_RESPONSE)
            synchronized(lock) {
                if (!closed) { output.println(text); output.flush(); check(!output.checkError()) }
            }
        }
        fun result(id: Any, value: Any?) = mapOf("jsonrpc" to "2.0", "id" to id, "result" to value)
        fun failure(id: Any?, code: Int, message: String) = send(mapOf("jsonrpc" to "2.0", "id" to id,
            "error" to mapOf("code" to code, "message" to message)))
        try {
            val bytes = ByteArray(MAX_REQUEST)
            val stream = input.buffered()
            while (true) {
                var size = 0
                var oversized = false
                var eof = false
                while (true) {
                    val next = stream.read()
                    if (next == -1) { eof = true; break }
                    if (next == 10) break
                    if (size < bytes.size) bytes[size++] = next.toByte() else oversized = true
                }
                if (eof && size == 0 && !oversized) break
                if (oversized) { failure(null, -32700, "Request frame exceeds 256 KiB; send a smaller request") }
                else {
                    val parsed = try {
                        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, size)).toString()
                        McpJsonCodec.parse(text)
                    } catch (_: IllegalArgumentException) { failure(null, -32700, "Invalid JSON frame"); if (eof) break else continue }
                    catch (_: java.nio.charset.CharacterCodingException) { failure(null, -32700, "Invalid UTF-8 frame"); if (eof) break else continue }
                    val request = parsed as? Map<*, *>
                    val id = request?.get("id")
                    val hasId = request?.containsKey("id") == true
                    val method = request?.get("method") as? String
                    if (request == null || request["jsonrpc"] != "2.0" || method.isNullOrEmpty() ||
                        request.keys.any { it !in setOf("jsonrpc", "id", "method", "params") } ||
                        hasId && !validId(id) || request.containsKey("params") && request["params"] !is Map<*, *>) {
                        failure(null, -32600, "Invalid JSON-RPC request")
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        val params = (request["params"] as? Map<String, Any?>).orEmpty()
                        if (!hasId) {
                            if (method == "notifications/initialized" && state == State.INITIALIZING &&
                                (params.isEmpty() || params.keys == setOf("_meta") && params["_meta"] is Map<*, *>)) state = State.READY
                            if (method == "notifications/cancelled" && validId(params["requestId"])) synchronized(lock) {
                                active?.takeIf { it.id == params["requestId"] }?.canceled = true
                            }
                            // 요청 ID 없는 메시지는 실행하거나 응답하지 않는다.
                        } else {
                            requireNotNull(id)
                            if (synchronized(lock) { active?.id == id }) {
                                failure(null, -32600, "Request ID is already in flight")
                            } else when (method) {
                                "initialize" -> {
                                    if (state != State.NEW) failure(id, -32600, "Already initialized")
                                    else if (params["protocolVersion"] !is String || params["capabilities"] !is Map<*, *> ||
                                        (params["clientInfo"] as? Map<*, *>)?.let { it["name"] is String && it["version"] is String } != true ||
                                        params.keys.any { it !in setOf("protocolVersion", "capabilities", "clientInfo", "_meta") })
                                        failure(id, -32602, "Invalid initialize parameters")
                                    else {
                                        send(result(id, mapOf("protocolVersion" to PROTOCOL,
                                            "capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
                                            "serverInfo" to mapOf("name" to "kartograph", "version" to KartographVersion.current),
                                            "instructions" to "Captured bytecode and metadata only. Call freshness before trusting live inputs. Restart to load new snapshots. Unknowns do not authorize safe deletion or skipped tests. One tool call at a time; cancellation suppresses responses but may not stop computation.")))
                                        state = State.INITIALIZING
                                    }
                                }
                                "ping" -> if (params.keys.all { it == "_meta" }) send(result(id, emptyMap<String, Any?>())) else failure(id, -32602, "Invalid ping parameters")
                                "tools/list" -> if (state != State.READY) failure(id, -32600, "Initialize and send notifications/initialized first")
                                    else if (params.keys.any { it != "_meta" }) failure(id, -32602, "No tool-list cursor or options are supported")
                                    else send(result(id, mapOf("tools" to McpTools.definitions)))
                                "tools/call" -> {
                                    if (state != State.READY) failure(id, -32600, "Initialize and send notifications/initialized first")
                                    else if (params["name"] !is String || params.keys.any { it !in setOf("name", "arguments", "_meta") } ||
                                        params.containsKey("arguments") && params["arguments"] !is Map<*, *> ||
                                        McpTools.definitions.none { it["name"] == params["name"] }) failure(id, -32602, "Invalid tool call; use tools/list")
                                    else {
                                        val pending = synchronized(lock) {
                                            if (active != null) null else Pending(id).also { active = it }
                                        }
                                        if (pending == null) failure(id, -32000, "A tool call is still running; wait before retrying")
                                        else worker.execute {
                                            val response = try {
                                                @Suppress("UNCHECKED_CAST")
                                                val arguments = (params["arguments"] as? Map<String, Any?>).orEmpty()
                                                val value = call(params["name"] as String, arguments)
                                                try { McpJsonCodec.render(result(id, value), MAX_RESPONSE) }
                                                catch (_: IllegalArgumentException) {
                                                    McpJsonCodec.render(result(id, McpTools.error("Result exceeds 1 MiB; narrow symbols/files, reduce depth/limit/pathLimit, or capture a narrower snapshot.")), MAX_RESPONSE)
                                                }
                                            } catch (_: IllegalArgumentException) {
                                                McpJsonCodec.render(result(id, McpTools.error("Invalid arguments or oversized result; use tools/list schema, portable selectors and smaller depth/limit/pathLimit.")), MAX_RESPONSE)
                                            } catch (_: Exception) {
                                                McpJsonCodec.render(result(id, McpTools.error("Saved-snapshot operation failed; check the fixed inputs and restart the server.")), MAX_RESPONSE)
                                            }
                                            var outputFailed = false
                                            synchronized(lock) {
                                                if (!closed && !pending.canceled) {
                                                    output.println(response)
                                                    output.flush()
                                                    if (output.checkError()) {
                                                        closed = true
                                                        transportFailed.set(true)
                                                        outputFailed = true
                                                    }
                                                }
                                                active = null
                                            }
                                            if (outputFailed) {
                                                failTransport()
                                            }
                                        }
                                    }
                                }
                                else -> failure(id, -32601, "Method not found; supported protocol is 2025-11-25 stdio")
                            }
                        }
                    }
                }
                if (eof) break
            }
            if (transportFailed.get()) {
                Thread.interrupted() // worker가 blocked read를 깨우기 위해 설정한 내부 interrupt를 소비한다.
                error.println("error: MCP transport failed; check UTF-8 stdio and restart")
                return 2
            }
            return 0
        } catch (_: Exception) {
            if (transportFailed.get()) Thread.interrupted()
            error.println("error: MCP transport failed; check UTF-8 stdio and restart")
            return 2
        } finally {
            synchronized(lock) { closed = true; active?.canceled = true }
            worker.shutdownNow()
        }
    }

    companion object {
        const val PROTOCOL = "2025-11-25"
        const val MAX_REQUEST = 256 * 1024
        const val MAX_RESPONSE = 1024 * 1024
        private fun validId(id: Any?) = id is String || id is Long || id is BigInteger
    }
}
