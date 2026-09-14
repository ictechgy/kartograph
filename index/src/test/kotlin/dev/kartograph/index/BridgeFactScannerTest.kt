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
    fun `messages does not resolve a mutable receiver from a conditional assignment`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText("""
            fun register(messenger: Any, codec: Any, alternate: Boolean) {
              var channel = BasicMessageChannel<Any?>(messenger, "a", codec)
              if (alternate) { channel = BasicMessageChannel<Any?>(messenger, "b", codec) }
              channel.setMessageHandler { _, _ -> Unit }
            }
        """.trimIndent())
        val fact = BridgeFactScanner(project).scanMessages().facts.single()
        assertTrue(fact.dynamic)
        assertEquals(null, fact.channelPrefix)
    }

    @Test
    fun `messages does not treat mutable channel name fields as constants`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText("""
            var channelName = "a"
            fun change() { channelName = "b" }
            fun register(messenger: Any, codec: Any) {
              val channel = BasicMessageChannel<Any?>(messenger, channelName, codec)
              channel.setMessageHandler { _, _ -> Unit }
            }
        """.trimIndent())
        val fact = BridgeFactScanner(project).scanMessages().facts.single()
        assertTrue(fact.dynamic)
        assertEquals(null, fact.channelPrefix)
    }

    @Test
    fun `messages preserves mutable alias uncertainty and removes null handlers`(@TempDir project: Path) {
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
            listOf("message-handle" to "channel", "message-handle" to "channel"),
            document.facts.map { it.kind to it.channel },
        )
        assertTrue(document.facts.all { it.dynamic })
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

        assertEquals("name", fact.channel)
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
                final BasicMessageChannel<Object> channel = new BasicMessageChannel<>(messenger, "java", codec);
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
    fun `messages does not reuse a prior chained channel for an unknown receiver`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            fun register(messenger: Any, codec: Any, unknown: Any) {
              BasicMessageChannel<Any?>(messenger, "known", codec).setMessageHandler { _, _ -> Unit }
              unknown.setMessageHandler { _, _ -> Unit }
            }
            """.trimIndent(),
        )

        val facts = BridgeFactScanner(project).scanMessages().facts

        assertEquals(listOf("known", null), facts.map { it.channel })
        assertTrue(facts[1].dynamic)
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
    fun `messages keeps a handler when its lambda calls reply with null`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            fun register(messenger: Any, codec: Any) {
              val channel = BasicMessageChannel<Any?>(messenger, "camera", codec)
              channel.setMessageHandler { _, reply -> reply.reply(null) }
            }
            """.trimIndent(),
        )

        val facts = BridgeFactScanner(project).scanMessages().facts

        assertEquals(listOf("message-handle" to "camera"), facts.map { it.kind to it.channel })
    }

    @Test
    fun `messages decodes escaped dollar as a literal channel`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            fun register(messenger: Any, codec: Any) {
              BasicMessageChannel<Any?>(messenger, "dev.flutter.pigeon.Api.\${'$'}literal", codec)
                .setMessageHandler { _, _ -> Unit }
            }
            """.trimIndent(),
        )

        val fact = BridgeFactScanner(project).scanMessages().facts.single()

        assertEquals("dev.flutter.pigeon.Api.${'$'}literal", fact.channel)
        assertTrue(!fact.dynamic)
        assertEquals(null, fact.channelPrefix)
    }

    @Test
    fun `messages ignores braces and bridge text inside a Kotlin raw string`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(listOf(
            "fun first(messenger: Any, codec: Any) {",
            "  val doc = \"\"\"config } channel.send(\\\"fake\\\")\"\"\"",
            "  val channel = BasicMessageChannel<Any?>(messenger, \"first\", codec)",
            "  channel.setMessageHandler { _, _ -> Unit }",
            "}",
            "fun second(messenger: Any, codec: Any) {",
            "  val channel = BasicMessageChannel<Any?>(messenger, \"second\", codec)",
            "  channel.setMessageHandler { _, _ -> Unit }",
            "}",
        ).joinToString("\n"))

        val facts = BridgeFactScanner(project).scanMessages().facts

        assertEquals(listOf("first", "second"), facts.map { it.channel })
    }

    @Test
    fun `messages resolves named channel constructor argument`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            fun register(messenger: Any, codec: Any) {
              BasicMessageChannel<Any?>(codec = codec, name = "named", binaryMessenger = messenger)
                .setMessageHandler { _, _ -> Unit }
            }
            """.trimIndent(),
        )

        val fact = BridgeFactScanner(project).scanMessages().facts.single()

        assertEquals("named", fact.channel)
        assertTrue(!fact.dynamic)
    }

    @Test
    fun `messages decodes unicode escapes in channel literals`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            fun register(messenger: Any, codec: Any) {
              BasicMessageChannel<Any?>(messenger, "dev.flutter.pigeon.Api.\u0041pi", codec)
                .setMessageHandler { _, _ -> Unit }
            }
            """.trimIndent(),
        )

        val fact = BridgeFactScanner(project).scanMessages().facts.single()

        assertEquals("dev.flutter.pigeon.Api.Api", fact.channel)
        assertTrue(!fact.dynamic)
    }

    @Test
    fun `messages records nullable safe-call handlers and ignores unrelated send calls`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            fun register(messenger: Any, codec: Any, other: Any) {
              val channel = BasicMessageChannel<Any?>(messenger, "camera", codec)
              channel?.setMessageHandler { _, _ -> Unit }
              other.send(Unit)
            }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scanMessages()

        assertEquals(listOf("message-handle" to "camera"), document.facts.map { it.kind to it.channel })
        assertTrue(document.limitations.none { it.startsWith("unscanned-message-sends:") })
    }

    @Test
    fun `messages keeps dollar shaped characters that are not at a dollar position`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            fun register(messenger: Any, codec: Any) {
              BasicMessageChannel<Any?>(messenger, "a{'${'$'}'}", codec)
                .setMessageHandler { _, _ -> Unit }
            }
            """.trimIndent(),
        )

        val fact = BridgeFactScanner(project).scanMessages().facts.single()

        assertEquals("a{'${'$'}'}", fact.channel)
        assertTrue(!fact.dynamic)
    }

    @Test
    fun `messages treats backslash dollar in a raw channel as interpolation`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(listOf(
            "fun register(messenger: Any, codec: Any, suffix: String) {",
            "  BasicMessageChannel<Any?>(messenger, \"\"\"prefix.\\${'$'}suffix\"\"\", codec)",
            "    .setMessageHandler { _, _ -> Unit }",
            "}",
        ).joinToString("\n"))

        val fact = BridgeFactScanner(project).scanMessages().facts.single()

        assertTrue(fact.dynamic)
        assertEquals("prefix.\\", fact.channelPrefix)
    }

    @Test
    fun `messages decodes raw dollar literal template into a literal channel`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(listOf(
            "fun register(messenger: Any, codec: Any) {",
            "  BasicMessageChannel<Any?>(messenger, \"\"\"prefix.${'$'}{'${'$'}'}suffix\"\"\", codec)",
            "    .setMessageHandler { _, _ -> Unit }",
            "}",
        ).joinToString("\n"))

        val fact = BridgeFactScanner(project).scanMessages().facts.single()

        assertEquals("prefix.${'$'}suffix", fact.channel)
        assertTrue(!fact.dynamic)
    }

    @Test
    fun `messages ignores triple quote content inside comments`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(listOf(
            "/* \"\"\"",
            "BasicMessageChannel<Any?>(messenger, \"fake\", codec).setMessageHandler { _, _ -> Unit }",
            "}\"\"\" */",
            "fun register(messenger: Any, codec: Any) {",
            "  BasicMessageChannel<Any?>(messenger, \"real\", codec).setMessageHandler { _, _ -> Unit }",
            "}",
        ).joinToString("\n"))

        val facts = BridgeFactScanner(project).scanMessages().facts

        assertEquals(listOf("real"), facts.map { it.channel })
    }

    @Test
    fun `v1 bridge columns count UTF8 bytes before registration and method facts`(@TempDir project: Path) {
        val line = "/* 한😀 */ MethodChannel(messenger, \"camera\").setMethodCallHandler { call, _ -> when (call.method) { \"take\" -> Unit } }"
        project.resolve("Plugin.kt").writeText(line)

        val facts = BridgeFactScanner(project).scan().facts
        val registrationPrefix = line.substring(0, line.indexOf("setMethodCallHandler"))
        val methodPrefix = line.substring(0, line.indexOf("take"))

        assertEquals(registrationPrefix.toByteArray(Charsets.UTF_8).size + 1, facts[0].location.column)
        assertEquals(methodPrefix.toByteArray(Charsets.UTF_8).size + 1, facts[1].location.column)
    }

    @Test
    fun `v2 message handler column counts UTF8 bytes after a comment prefix`(@TempDir project: Path) {
        val line = "/* 한😀 */ BasicMessageChannel<Any?>(messenger, \"camera\", codec).setMessageHandler { _, _ -> Unit }"
        project.resolve("Plugin.kt").writeText(line)

        val fact = BridgeFactScanner(project).scanMessages().facts.single()
        val prefix = line.substring(0, line.indexOf("setMessageHandler"))

        assertEquals(prefix.toByteArray(Charsets.UTF_8).size + 1, fact.location.column)
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
    fun `v1 maps every when method fact to the enclosing Kotlin function`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            class Plugin {
              override fun configureFlutterEngine(messenger: Any) {
                MethodChannel(messenger, "camera").setMethodCallHandler { call, result ->
                  when (call.method) {
                    "echo" -> result.success(Unit)
                    "failure" -> result.error("failure", null, null)
                    "slow" -> result.success(Unit)
                    "never" -> Unit
                  }
                }
              }
            }
            """.trimIndent(),
        )
        val graph = CodeGraph(listOf(
            GraphNode(NodeId("method:app/Plugin#configureFlutterEngine(Ljava/lang/Object;)V"), "configureFlutterEngine", NodeKind.METHOD,
                location = SourceLocation("Plugin.kt", 2, 3)),
        ), emptyList())

        val facts = BridgeFactScanner(project).scan(graph = graph).facts.filter { it.kind == "method-handle" }

        assertEquals(4, facts.size)
        assertTrue(facts.all { it.symbol?.usr == "method:app/Plugin#configureFlutterEngine(Ljava/lang/Object;)V" })
    }

    @Test
    fun `v1 attaches symbol for multiline Kotlin function header`(@TempDir project: Path) {
        project.resolve("MessagesAsync.kt").writeText(
            """
            class MessagesAsync {
              fun setUp(
                binaryMessenger: Any,
                api: Any,
              ): Unit {
                BasicMessageChannel<Any?>(binaryMessenger, "dev.flutter.pigeon.Api.echo", api)
                  .setMessageHandler { _, _ -> Unit }
              }
            }
            """.trimIndent(),
        )
        val graph = CodeGraph(listOf(
            GraphNode(NodeId("method:app/MessagesAsync#setUp(Ljava/lang/Object;Ljava/lang/Object;)V"), "setUp", NodeKind.METHOD,
                location = SourceLocation("MessagesAsync.kt", 2, null)),
            GraphNode(NodeId("method:app/MessagesAsync#setUp(Ljava/lang/Object;)V"), "setUp", NodeKind.METHOD,
                location = SourceLocation("MessagesAsync.kt", 20, null)),
        ), emptyList())

        val fact = BridgeFactScanner(project).scanMessages(graph = graph).facts.single()

        assertEquals("dev.flutter.pigeon.Api.echo", fact.channel)
        assertEquals("method:app/MessagesAsync#setUp(Ljava/lang/Object;Ljava/lang/Object;)V", fact.symbol?.usr)
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
    fun `snapshot symbol stays absent when two preceding methods are possible`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            fun first() { }
            fun second() {
              MethodChannel(messenger, "camera").setMethodCallHandler(handler)
            }
            """.trimIndent(),
        )
        val graph = CodeGraph(listOf(
            GraphNode(NodeId("method:app/Plugin#first()V"), "first", NodeKind.METHOD,
                location = SourceLocation("Plugin.kt", 1, 1)),
            GraphNode(NodeId("method:app/Plugin#second()V"), "second", NodeKind.METHOD,
                location = SourceLocation("Plugin.kt", 2, 1)),
            GraphNode(NodeId("method:app/Plugin#second(Ljava/lang/Object;)V"), "second", NodeKind.METHOD,
                location = SourceLocation("Plugin.kt", 3, 2)),
        ), emptyList())

        val fact = BridgeFactScanner(project).scan(graph = graph).facts.single()

        assertEquals(null, fact.symbol)
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
