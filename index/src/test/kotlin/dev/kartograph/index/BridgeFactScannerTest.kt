package dev.kartograph.index

import java.nio.file.Path
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class BridgeFactScannerTest {
    @Test
    fun `messages follows aliases mutation shadowing and removes null handlers`(@TempDir project: Path) {
        project.resolve("src/main/kotlin/app/Plugin.kt").also { source ->
            source.parent.createDirectories()
            source.writeText(
                """
                package app
                class Plugin {
                  fun register(messenger: Any, codec: Any) {
                    var channel = BasicMessageChannel<Any?>(messenger, "first", codec)
                    val alias = channel
                    alias.setMessageHandler { _, _ -> Unit }
                    channel = BasicMessageChannel<Any?>(messenger, "second", codec)
                    channel.send("outgoing")
                    fun nested() {
                      val channel = BasicMessageChannel<Any?>(messenger, "inner", codec)
                      channel.setMessageHandler { _, _ -> Unit }
                      channel.send("nested")
                    }
                    channel.setMessageHandler(null)
                  }
                }
                """.trimIndent(),
            )
        }

        val document = BridgeFactScanner(project).scanMessages(generatedAt = "2026-09-14T00:00:00Z")

        assertEquals(2, document.facts.size)
        assertEquals(
            listOf("message-handle" to "first", "message-handle" to "inner"),
            document.facts.map { it.kind to it.channel },
        )
        assertTrue(document.facts.all { it.dynamic.not() })
        assertTrue(document.facts.all { it.symbol == null })
        assertTrue(document.limitations.any { it.startsWith("missing-handler-usrs:") })
        assertTrue(document.limitations.any { it.startsWith("unscanned-message-sends:") })
    }

    @Test
    fun `messages preserves dynamic expression and proven nonempty prefix`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            fun register(messenger: Any, codec: Any, suffix: String) {
              val channel = BasicMessageChannel<Any?>(messenger, "dev.flutter.pigeon.Camera.${'$'}suffix", codec)
              channel.setMessageHandler { _, _ -> Unit }
            }
            """.trimIndent(),
        )

        val fact = BridgeFactScanner(project).scanMessages(generatedAt = "2026-09-14T00:00:00Z").facts.single()

        assertEquals("message-handle", fact.kind)
        assertEquals("\"dev.flutter.pigeon.Camera.${'$'}suffix\"", fact.channel)
        assertEquals("dev.flutter.pigeon.Camera.", fact.channelPrefix)
        assertTrue(fact.dynamic)
    }

    @Test
    fun `messages keeps a named nonliteral channel expression without prefix`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            fun register(messenger: Any, codec: Any, name: String) {
              BasicMessageChannel<Any?>(binaryMessenger = messenger, name = name, codec = codec)
                .setMessageHandler { _, _ -> Unit }
            }
            """.trimIndent(),
        )

        val fact = BridgeFactScanner(project).scanMessages().facts.single()

        assertEquals("name = name", fact.channel)
        assertTrue(fact.dynamic)
        assertEquals(null, fact.channelPrefix)
    }

    @Test
    fun `messages attaches an enclosing compiler snapshot JVM symbol without guessing names`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            class Plugin {
              fun register(messenger: Any, codec: Any) {
                val channel = BasicMessageChannel<Any?>(messenger, "camera", codec)
                channel.setMessageHandler { _, _ -> Unit }
              }
            }
            """.trimIndent(),
        )
        val graph = CodeGraph(listOf(
            GraphNode(NodeId("method:app/Plugin#register(Ljava/lang/Object;Ljava/lang/Object;)V"), "register", NodeKind.METHOD,
                location = SourceLocation("Plugin.kt", 4, 3)),
        ), emptyList())

        val fact = BridgeFactScanner(project).scanMessages(graph = graph).facts.single()

        assertEquals("method:app/Plugin#register(Ljava/lang/Object;Ljava/lang/Object;)V", fact.symbol?.usr)
        assertEquals("app.Plugin.register", fact.symbol?.qualifiedName)
    }

    @Test
    fun `messages scans raw Java syntax and reports source limitation`(@TempDir project: Path) {
        project.resolve("Plugin.java").writeText(
            """
            class Plugin {
              void register(Object messenger, Object codec) {
                BasicMessageChannel<Object> channel = new BasicMessageChannel<>(messenger, "java", codec);
                channel.setMessageHandler((message, reply) -> { });
                channel.send("outgoing");
              }
            }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scanMessages()

        assertEquals(listOf("message-handle" to "java"), document.facts.map { it.kind to it.channel })
        assertTrue(document.limitations.any { it.startsWith("java-source-basic-message-analysis:") })
    }

    @Test
    fun `messages does not attach an opaque handler to the previous channel`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            fun register(messenger: Any, codec: Any, handler: Any) {
              val known = BasicMessageChannel<Any?>(messenger, "known", codec)
              handler.setMessageHandler { _, _ -> Unit }
            }
            """.trimIndent(),
        )

        val fact = BridgeFactScanner(project).scanMessages().facts.single()

        assertEquals("message-handle", fact.kind)
        assertEquals(null, fact.channel)
        assertTrue(fact.dynamic)
        assertTrue(fact.location.line > 0)
    }

    @Test
    fun `messages resolves immutable channel aliases and ignores code-like strings`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            private val basicName = "dev.flutter.pigeon.runtime.Api.echo"
            fun register(messenger: Any, codec: Any) {
              val source = "channel.send(\"fake\")"
              BasicMessageChannel<Any?>(messenger, basicName, codec).setMessageHandler { _, _ -> Unit }
            }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scanMessages()

        assertEquals(listOf("message-handle" to "dev.flutter.pigeon.runtime.Api.echo"), document.facts.map { it.kind to it.channel })
        assertTrue(document.limitations.none { it.startsWith("unscanned-message-sends:") })
    }

    @Test
    fun `messages does not leak bindings between sibling function scopes`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            fun first(messenger: Any, codec: Any) {
              val channel = BasicMessageChannel<Any?>(messenger, "first", codec)
              channel.setMessageHandler { _, _ -> Unit }
            }
            fun second(handler: Any) {
              handler.setMessageHandler { _, _ -> Unit }
            }
            """.trimIndent(),
        )

        val facts = BridgeFactScanner(project).scanMessages().facts

        assertEquals(listOf("first", null), facts.map { it.channel })
        assertTrue(facts[1].dynamic)
    }

    @Test
    fun `extracts MethodChannel registrations and React Native exports`(@TempDir project: Path) {
        project.resolve("src/main/kotlin/app/Plugin.kt").also { source ->
            source.parent.createDirectories()
            source.writeText(
                """
                package app
                class Plugin {
                  fun unrelated(value: String) = when (value) { "notABridgeMethod" -> Unit }
                  fun register(messenger: BinaryMessenger) {
                    val channel = MethodChannel(messenger, "com.example/camera")
                    channel.setMethodCallHandler { call, _ ->
                      when (call.method) { "takePhoto" -> Unit }
                    }
                  }
                }
                @ReactModule(name = "Calendar") class CalendarModule {
                  @ReactMethod fun addEvent() = Unit
                }
                """.trimIndent(),
            )
        }

        val document = BridgeFactScanner(project).scan(generatedAt = "2026-09-04T00:00:00Z")

        assertEquals("bridge-facts", document.format)
        assertEquals(1, document.version)
        assertEquals("kotlin", document.platform)
        assertEquals("flutter", document.target)
        assertEquals(project.toRealPath().toString().replace('\\', '/'), document.project)
        assertEquals(
            listOf("channel-register", "method-handle", "module-export", "method-handle"),
            document.facts.map { it.kind },
        )
        assertEquals("com.example/camera", document.facts[0].channel)
        assertEquals("takePhoto", document.facts[1].method)
        assertEquals("Calendar", document.facts[2].channel)
        assertEquals("addEvent", document.facts[3].method)
        assertTrue(document.limitations.any { it.startsWith("missing-handler-usrs:") })
        assertTrue(document.limitations.any { it.startsWith("mixed-targets:") })
        assertTrue(document.facts.all { !it.location.path.startsWith('/') && it.location.line > 0 })
    }

    @Test
    fun `target filter keeps Flutter facts and records omitted React Native facts`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            val channel = MethodChannel(messenger, "camera")
            channel.setMethodCallHandler(handler)
            @ReactModule(name = "Calendar") class CalendarModule {
              @ReactMethod fun addEvent() = Unit
            }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scan(targetFilter = "flutter")

        assertTrue(document.facts.isNotEmpty())
        assertTrue(document.facts.all { it.target == "flutter" })
        assertTrue(document.limitations.any { it.startsWith("target-filter:") })
        assertEquals("flutter", document.target)
    }

    @Test
    fun `v1 resolves immutable MethodChannel name and attaches unique snapshot symbol`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            private val methodName = "camera"
            fun register(messenger: Any) {
              MethodChannel(messenger, methodName).setMethodCallHandler(handler)
            }
            """.trimIndent(),
        )
        val graph = CodeGraph(listOf(
            GraphNode(NodeId("method:app/Plugin#register(Ljava/lang/Object;)V"), "register", NodeKind.METHOD,
                location = SourceLocation("Plugin.kt", 3, 3)),
        ), emptyList())

        val fact = BridgeFactScanner(project).scan(graph = graph).facts.single()

        assertEquals("camera", fact.channel)
        assertEquals("method:app/Plugin#register(Ljava/lang/Object;)V", fact.symbol?.usr)
        assertTrue(!BridgeFactScanner(project).scan(graph = graph).limitations.any { it.startsWith("missing-handler-usrs:") })
    }

    @Test
    fun `snapshot symbol remains absent for stale or ambiguous source mappings`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            fun register(messenger: Any) {
              MethodChannel(messenger, "camera").setMethodCallHandler(handler)
            }
            """.trimIndent(),
        )
        val stale = CodeGraph(listOf(
            GraphNode(NodeId("method:app/Plugin#register(Ljava/lang/Object;)V"), "register", NodeKind.METHOD,
                location = SourceLocation("Other.kt", 2, 1)),
        ), emptyList())
        val ambiguous = CodeGraph(listOf(
            GraphNode(NodeId("method:app/Plugin#register(Ljava/lang/Object;)V"), "register", NodeKind.METHOD,
                location = SourceLocation("Plugin.kt", 2, 1)),
            GraphNode(NodeId("method:app/Other#register(Ljava/lang/Object;)V"), "register", NodeKind.METHOD,
                location = SourceLocation("Plugin.kt", 2, 2)),
        ), emptyList())

        val staleFact = BridgeFactScanner(project).scan(graph = stale).facts.single()
        val ambiguousFact = BridgeFactScanner(project).scan(graph = ambiguous).facts.single()

        assertEquals(null, staleFact.symbol)
        assertEquals(null, ambiguousFact.symbol)
        assertTrue(staleFact.symbol == null && ambiguousFact.symbol == null)
    }

    @Test
    fun `keeps dynamic MethodChannel names as counted limitations`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            "val channel = MethodChannel(messenger, channelName)\nchannel.setMethodCallHandler(handler)\n",
        )

        val document = BridgeFactScanner(project).scan(generatedAt = "2026-09-04T00:00:00Z")

        assertTrue(document.facts.single().dynamic)
        assertEquals("channelName", document.facts.single().channel)
        assertEquals(
            listOf(
                "dynamic-channel-names: 1 channel registration(s) use a non-literal name",
                "unscanned-method-handlers: 1 handler callback(s) are not inline lambdas",
            ),
            document.limitations,
        )
    }

    @Test
    fun `attributes multiline MethodChannels to their own handlers`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
                val camera = MethodChannel(
                    messenger,
                    "camera",
                )
                val location = MethodChannel(messenger, "location")
                camera.setMethodCallHandler { call, _ ->
                    when (call.method) { "takePhoto" -> Unit }
                }
                location.setMethodCallHandler { call, _ ->
                    when (call.method) { "currentLocation" -> Unit }
                }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scan(generatedAt = "2026-09-04T00:00:00Z")

        assertEquals(
            listOf(
                "camera" to null,
                "camera" to "takePhoto",
                "location" to null,
                "location" to "currentLocation",
            ),
            document.facts.map { it.channel to it.method },
        )
    }

    @Test
    fun `records chained and opaque handlers without inventing unrelated when methods`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
                MethodChannel(messenger, "camera").setMethodCallHandler { call, _ ->
                    when (call.arguments) { "notAMethod" -> Unit }
                    when (call.method) { "takePhoto" -> Unit }
                }
                val location = MethodChannel(messenger, "location")
                location.setMethodCallHandler(locationHandler)
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scan(generatedAt = "2026-09-04T00:00:00Z")

        assertTrue(document.facts.any { it.kind == "channel-register" && it.channel == "camera" })
        assertTrue(document.facts.any { it.kind == "method-handle" && it.method == "takePhoto" })
        assertTrue(document.facts.none { it.method == "notAMethod" })
        assertTrue(document.facts.any { it.kind == "channel-register" && it.channel == "location" })
        assertTrue(document.limitations.any { it.startsWith("unscanned-method-handlers: 1") })
    }

    @Test
    fun `records a multiline chained MethodChannel handler`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
                MethodChannel(
                    messenger,
                    "camera",
                ).setMethodCallHandler { call, _ ->
                    when (call.method) { "takePhoto" -> Unit }
                }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scan(generatedAt = "2026-09-04T00:00:00Z")

        assertEquals(
            listOf("camera" to null, "camera" to "takePhoto"),
            document.facts.map { it.channel to it.method },
        )
    }

    @Test
    fun `scopes adjacent React methods and skips vendored and test sources`(@TempDir project: Path) {
        project.resolve("src/main/Modules.kt").also { source ->
            source.parent.createDirectories()
            source.writeText(
                """
                    @ReactModule(name = "Calendar")
                    class CalendarModule {
                      @ReactMethod
                      fun addEvent() = Unit
                    }
                    @ReactModule(name = "Camera")
                    class CameraModule {
                      @ReactMethod fun takePhoto() = Unit
                    }
                """.trimIndent(),
            )
        }
        listOf("node_modules/vendor/Vendor.kt", "src/test/TestModule.kt").forEach { relative ->
            project.resolve(relative).also { source ->
                source.parent.createDirectories()
                source.writeText("@ReactModule(name = \"Ignored\") class Ignored { @ReactMethod fun ignored() = Unit }")
            }
        }

        val document = BridgeFactScanner(project).scan(generatedAt = "2026-09-04T00:00:00Z")

        assertEquals(
            listOf("Calendar" to null, "Calendar" to "addEvent", "Camera" to null, "Camera" to "takePhoto"),
            document.facts.map { it.channel to it.method },
        )
    }

    @Test
    fun `ignores assistant and cache directories outside project sources`(@TempDir project: Path) {
        project.resolve("src/main/kotlin/app/Plugin.kt").also { source ->
            source.parent.createDirectories()
            source.writeText(
                """
                val channel = MethodChannel(messenger, "camera")
                channel.setMethodCallHandler(handler)
                """.trimIndent(),
            )
        }
        // 어시스턴트·캐시 디렉터리의 예제 코드는 project source가 아니므로 교환 문서에 수확하지 않는다.
        listOf(
            ".claude/skills/Fake.kt",
            ".omx/notes/Fake.kt",
            ".gradle/checks/Fake.kt",
            ".worktrees/copy/Fake.kt",
        ).forEach { relative ->
            project.resolve(relative).also { source ->
                source.parent.createDirectories()
                source.writeText(
                    """
                    val channel = MethodChannel(messenger, "harvested")
                    channel.setMethodCallHandler(handler)
                    """.trimIndent(),
                )
            }
        }

        val document = BridgeFactScanner(project).scan(generatedAt = "2026-09-04T00:00:00Z")

        assertEquals(listOf("camera"), document.facts.map { it.channel })
    }
}
