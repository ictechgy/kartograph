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
    fun `messages keeps aliases with concatenation transformation or getter unresolved`(@TempDir project: Path) {
        for (initializer in listOf("\"a\" + \"b\"", "\"a\"\n  .plus(\"b\")", "\"a\"\n  get() = \"b\"")) {
            project.resolve("Plugin.kt").writeText("""
                val channelName = $initializer
                fun register(messenger: Any, codec: Any) {
                  BasicMessageChannel<Any?>(messenger, channelName, codec).setMessageHandler { _, _ -> Unit }
                }
            """.trimIndent())
            val fact = BridgeFactScanner(project).scanMessages().facts.single()
            assertTrue(fact.dynamic, initializer)
            assertEquals(null, fact.channelPrefix)
        }
    }

    @Test
    fun `messages does not decode a quoted concatenation as one literal`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText("""
            fun register(messenger: Any, codec: Any) {
              BasicMessageChannel<Any?>(messenger, "a" + "b", codec).setMessageHandler { _, _ -> Unit }
            }
        """.trimIndent())
        val fact = BridgeFactScanner(project).scanMessages().facts.single()
        assertTrue(fact.dynamic)
        assertEquals(null, fact.channelPrefix)
        assertEquals("\"a\" + \"b\"", fact.channel)
    }

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

    @Test
    fun `events emits stream-handle facts in an event-channel document`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            fun register(messenger: Any) {
              val channel = EventChannel(messenger, "dev.fluttercommunity.plus/charging")
              channel.setStreamHandler(chargingHandler)
            }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scanEvents(generatedAt = "2026-10-01T00:00:00Z")

        assertEquals("bridge-facts", document.format)
        assertEquals(2, document.version)
        assertEquals("event-channel", document.transport)
        assertEquals("flutter", document.target)
        val fact = document.facts.single()
        assertEquals("stream-handle", fact.kind)
        assertEquals("dev.fluttercommunity.plus/charging", fact.channel)
        assertTrue(!fact.dynamic)
        assertEquals(null, fact.method)
    }

    @Test
    fun `events resolves a chained stream handler and ignores null clears`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            fun register(messenger: Any) {
              EventChannel(messenger, "charging").setStreamHandler(ChargingHandler())
              EventChannel(messenger, "cleared").setStreamHandler(null)
            }
            """.trimIndent(),
        )

        val facts = BridgeFactScanner(project).scanEvents().facts

        assertEquals(listOf("stream-handle" to "charging"), facts.map { it.kind to it.channel })
    }

    @Test
    fun `events keeps dynamic names and proven prefixes without flagging sink calls`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            fun register(messenger: Any, flavor: String, events: Any) {
              val channel = EventChannel(messenger, "dev.flutter/${'$'}flavor/charging")
              channel.setStreamHandler(handler)
              events.success("tick")
            }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scanEvents()

        val fact = document.facts.single()
        assertTrue(fact.dynamic)
        assertEquals("dev.flutter/", fact.channelPrefix)
        assertTrue(document.limitations.any { it.startsWith("dynamic-event-channel-names:") })
        assertTrue(document.limitations.none { it.startsWith("unscanned-message-sends:") })
    }

    @Test
    fun `events does not resolve a mutable receiver from a conditional assignment`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            fun register(messenger: Any, alternate: Boolean) {
              var channel = EventChannel(messenger, "a")
              if (alternate) { channel = EventChannel(messenger, "b") }
              channel.setStreamHandler(handler)
            }
            """.trimIndent(),
        )

        val fact = BridgeFactScanner(project).scanEvents().facts.single()

        assertTrue(fact.dynamic)
        assertEquals(null, fact.channelPrefix)
        assertEquals("stream-handle", fact.kind)
    }

    @Test
    fun `events emits no facts for MethodChannel or BasicMessageChannel registrations`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            fun register(messenger: Any, codec: Any) {
              MethodChannel(messenger, "battery").setMethodCallHandler(handler)
              BasicMessageChannel<Any?>(messenger, "pigeon", codec).setMessageHandler { _, _ -> Unit }
            }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scanEvents()

        assertTrue(document.facts.isEmpty())
        assertEquals(null, document.target)
    }

    @Test
    fun `events scans raw Java syntax and reports source limitation`(@TempDir project: Path) {
        project.resolve("Plugin.java").writeText(
            """
            class Plugin {
              void register(Object messenger) {
                final EventChannel channel = new EventChannel(messenger, "charging");
                channel.setStreamHandler(handler);
              }
            }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scanEvents()

        assertEquals(listOf("stream-handle" to "charging"), document.facts.map { it.kind to it.channel })
        assertTrue(document.limitations.any { it.startsWith("java-source-event-channel-analysis:") })
    }

    @Test
    fun `events attaches an enclosing compiler snapshot JVM symbol`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            class Plugin {
              fun register(messenger: Any) {
                EventChannel(messenger, "charging").setStreamHandler(handler)
              }
            }
            """.trimIndent(),
        )
        val graph = CodeGraph(listOf(
            GraphNode(NodeId("method:app/Plugin#register(Ljava/lang/Object;)V"), "register", NodeKind.METHOD,
                location = SourceLocation("Plugin.kt", 2, 3)),
        ), emptyList())

        val fact = BridgeFactScanner(project).scanEvents(graph = graph).facts.single()

        assertEquals("method:app/Plugin#register(Ljava/lang/Object;)V", fact.symbol?.usr)
    }

    @Test
    fun `reports JNI interop files as uncovered evidence across transports`(@TempDir project: Path) {
        project.resolve("Native.kt").writeText(
            """
            class Native {
              init { System.loadLibrary("native-lib") }
              external fun nativeCall(): Int
            }
            """.trimIndent(),
        )

        val label = "unscanned-ffi-interop: 1 Kotlin/Java source file(s) declare JNI/native interop"
        assertTrue(BridgeFactScanner(project).scan().limitations.any { it.startsWith(label) })
        assertTrue(BridgeFactScanner(project).scanEvents().limitations.any { it.startsWith(label) })
        assertTrue(BridgeFactScanner(project).scanMessages().limitations.any { it.startsWith(label) })
    }

    @Test
    fun `does not flag ordinary Kotlin identifiers as JNI interop`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            fun register(messenger: Any) {
              val native = EventChannel(messenger, "charging")
              native.setStreamHandler(handler)
            }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scanEvents()

        assertTrue(document.limitations.none { it.startsWith("unscanned-ffi-interop:") })
        assertEquals("charging", document.facts.single().channel)
    }

    @Test
    fun `events attributes a stream handler chained through non-null assert`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            fun register(messenger: Any) {
              EventChannel(messenger, "charging")!!.setStreamHandler(ChargingHandler())
            }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scanEvents()

        val fact = document.facts.single()
        assertEquals("stream-handle", fact.kind)
        assertEquals("charging", fact.channel)
        assertTrue(!fact.dynamic)
        assertTrue(document.limitations.none { it.startsWith("unattributed-stream-handles:") })
    }

    @Test
    fun `events attributes a receiver reached through safe call or non-null assert`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            class Plugin {
              private var channel: EventChannel? = null
              private val direct = EventChannel(messenger, "direct")
              fun register(messenger: Any) {
                direct!!.setStreamHandler(handler)
                channel?.setStreamHandler(handler)
              }
            }
            """.trimIndent(),
        )

        val facts = BridgeFactScanner(project).scanEvents().facts

        assertEquals(2, facts.size)
        assertEquals("direct", facts[0].channel)
        assertTrue(!facts[0].dynamic)
        assertTrue(facts[1].dynamic)
        // mutable 이름은 literal로 확정하지 않고 원 표현을 dynamic으로 보존한다.
        assertEquals("channel", facts[1].channel)
    }

    @Test
    fun `messages counts send calls through safe call and non-null assert`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            class Plugin(codec: Any) {
              private val channel = BasicMessageChannel<Any?>(messenger, "pigeon", codec)
              fun emit() {
                channel!!.send("payload")
                channel?.send("payload")
              }
            }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scanMessages()

        assertTrue(document.limitations.any { it.startsWith("unscanned-message-sends: 2") })
    }

    @Test
    fun `does not flag JNI markers inside string literals`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText(
            """
            class Plugin {
              fun explain() {
                println("call System.loadLibrary(\"x\") to load, or declare external fun")
              }
            }
            """.trimIndent(),
        )

        val scans = listOf(
            BridgeFactScanner(project).scan().limitations,
            BridgeFactScanner(project).scanEvents().limitations,
            BridgeFactScanner(project).scanMessages().limitations,
        )

        assertTrue(scans.all { limitations ->
            limitations.none { it.startsWith("unscanned-ffi-interop:") }
        })
    }

    @Test
    fun `reports Java native methods with generics multiline and default-package JNI names`(@TempDir project: Path) {
        project.resolve("Native.java").writeText(
            """
            class Native {
              private native List<? extends Foo>
                load(String key);
            }
            """.trimIndent(),
        )
        project.resolve("Exports.kt").writeText(
            """
            class Exports {
              fun named() = Unit
            }
            fun Java_MyClass_open(): Int = 0
            """.trimIndent(),
        )

        val label = "unscanned-ffi-interop"
        assertTrue(
            BridgeFactScanner(project).scanEvents().limitations
                .any { it.startsWith("$label: 2") },
        )
    }

    @Test
    fun `expo module emits mechanism on name boundaries and methods without it`(@TempDir project: Path) {
        project.resolve("Sensor.kt").writeText(
            """
            package app
            import expo.modules.kotlin.modules.Module
            class SensorModule : Module() {
              override fun definition() = ModuleDefinition {
                Name("Sensor")
                Function("ping") { "pong" }
                AsyncFunction("fetch") { url: String -> url }
                View(SensorView::class) {
                  Prop("tint") { _: SensorView, _: String -> }
                }
              }
            }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scan(generatedAt = "2026-09-04T00:00:00Z")

        assertEquals("react-native", document.target)
        val byKind = document.facts.groupBy { it.kind }
        assertEquals(listOf("Sensor"), byKind["module-export"]?.map { it.channel })
        assertEquals(listOf("Sensor"), byKind["component-export"]?.map { it.channel })
        assertEquals(listOf("ping", "fetch"), byKind["method-handle"]?.map { it.method })
        assertTrue(byKind["module-export"]!!.all { it.mechanism == "expo" })
        assertTrue(byKind["component-export"]!!.all { it.mechanism == "expo" })
        assertTrue(byKind["method-handle"]!!.all { it.mechanism == null && !it.dynamic })
        // component-export는 첫 View 호출 자리를 가리킨다.
        assertTrue(document.facts.single { it.kind == "component-export" }.location.line >
            document.facts.single { it.kind == "module-export" }.location.line)
    }

    @Test
    fun `expo module falls back to the class name when Name is absent`(@TempDir project: Path) {
        project.resolve("Sensor.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class SensorModule : Module() {
              override fun definition() = ModuleDefinition {
                Function("ping") { "pong" }
              }
            }
            """.trimIndent(),
        )

        val facts = BridgeFactScanner(project).scan().facts

        assertEquals("SensorModule", facts.single { it.kind == "module-export" }.channel)
    }

    @Test
    fun `expo module name stays dynamic when the definition block is not visible`(@TempDir project: Path) {
        project.resolve("Sensor.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class SensorModule : Module() {
              override fun definition() = buildDefinition()
            }
            """.trimIndent(),
        )

        val fact = BridgeFactScanner(project).scan().facts.single { it.kind == "module-export" }

        assertTrue(fact.dynamic)
        // channel은 비워둘 수 없으므로 클래스명을 근거로 남긴다.
        assertEquals("SensorModule", fact.channel)
        assertEquals("expo", fact.mechanism)
    }

    @Test
    fun `expo module name stays dynamic for a non-literal Name argument`(@TempDir project: Path) {
        project.resolve("Sensor.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class SensorModule : Module() {
              override fun definition() = ModuleDefinition {
                Name(BuildConfig.MODULE_NAME)
                Function("ping") { "pong" }
              }
            }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scan()

        val export = document.facts.single { it.kind == "module-export" }
        assertTrue(export.dynamic)
        assertEquals("BuildConfig.MODULE_NAME", export.channel)
        assertTrue(document.limitations.any { it.startsWith("dynamic-expo-names:") })
        // 메서드 이름은 리터럴이면 이름 경계가 dynamic이어도 그대로 둔다.
        val method = document.facts.single { it.kind == "method-handle" }
        assertEquals("ping", method.method)
        assertEquals("BuildConfig.MODULE_NAME", method.channel)
    }

    @Test
    fun `expo scanning requires the modules import and a real Module supertype`(@TempDir project: Path) {
        project.resolve("Lookalike.kt").writeText(
            """
            class Lookalike : Module() {
              fun definition() = ModuleDefinition {
                Name("NotExpo")
                Function("ping") { "pong" }
              }
            }
            """.trimIndent(),
        )
        project.resolve("Nested.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class Nested : Outer.Module() {
              override fun definition() = ModuleDefinition { Name("Nested") }
            }
            abstract class AbstractModule : Module() {
              override fun definition() = ModuleDefinition { Name("Abstract") }
            }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scan()

        assertTrue(document.facts.isEmpty())
        assertEquals(null, document.target)
    }

    @Test
    fun `expo DSL ignores same-named calls outside the definition block`(@TempDir project: Path) {
        project.resolve("Sensor.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class SensorModule : Module() {
              private fun helper() {
                Name("NotAModuleName")
                View(Other::class)
                Function("notAMethod") { }
              }
              override fun definition() = ModuleDefinition {
                Name("Sensor")
                Function("ping") { "pong" }
              }
            }
            """.trimIndent(),
        )

        val facts = BridgeFactScanner(project).scan().facts

        assertEquals("Sensor", facts.single { it.kind == "module-export" }.channel)
        assertEquals(listOf("ping"), facts.filter { it.kind == "method-handle" }.map { it.method })
        assertTrue(facts.none { it.kind == "component-export" })
    }

    @Test
    fun `expo DSL ignores calls nested inside function lambdas`(@TempDir project: Path) {
        project.resolve("Sensor.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class SensorModule : Module() {
              override fun definition() = ModuleDefinition {
                Function("outer") {
                  Function("inner") { }
                  Name("Inner")
                }
              }
            }
            """.trimIndent(),
        )

        val facts = BridgeFactScanner(project).scan().facts

        assertEquals("SensorModule", facts.single { it.kind == "module-export" }.channel)
        assertEquals(listOf("outer"), facts.filter { it.kind == "method-handle" }.map { it.method })
    }

    @Test
    fun `expo supports a class brace or definition brace on the next line`(@TempDir project: Path) {
        project.resolve("Sensor.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class SensorModule : Module()
            {
              override fun definition(): ModuleDefinitionData = ModuleDefinition
              {
                Name("Sensor")
              }
            }
            """.trimIndent(),
        )

        val facts = BridgeFactScanner(project).scan().facts

        assertEquals("Sensor", facts.single { it.kind == "module-export" }.channel)
    }

    @Test
    fun `expo scans a fully qualified Module supertype without the import`(@TempDir project: Path) {
        project.resolve("Sensor.kt").writeText(
            """
            class SensorModule : expo.modules.kotlin.modules.Module() {
              override fun definition() = ModuleDefinition {
                Name("Sensor")
              }
            }
            """.trimIndent(),
        )

        val facts = BridgeFactScanner(project).scan().facts

        assertEquals("Sensor", facts.single { it.kind == "module-export" }.channel)
    }

    @Test
    fun `expo scans multiple modules in one file and keeps dynamic method names honest`(@TempDir project: Path) {
        project.resolve("Modules.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class AlphaModule : Module() {
              override fun definition() = ModuleDefinition {
                Function(methodName()) { }
              }
            }
            class BetaModule : Module() {
              override fun definition() = ModuleDefinition {
                Name("Beta")
                View(BetaView::class)
              }
            }
            """.trimIndent(),
        )

        val facts = BridgeFactScanner(project).scan().facts

        assertEquals(listOf("Beta"), facts.filter { it.kind == "component-export" }.map { it.channel })
        val method = facts.single { it.kind == "method-handle" }
        // method는 비워둘 수 없으므로 표현식 원문을 dynamic 근거로 남긴다.
        assertEquals("methodName()", method.method)
        assertTrue(method.dynamic)
        assertEquals(null, method.mechanism)
        // Name이 없는 정의 블록은 클래스명 폴백이고, 명시 Name은 그 값을 쓴다.
        assertEquals(
            listOf("AlphaModule", "Beta"),
            facts.filter { it.kind == "module-export" }.map { it.channel },
        )
    }

    @Test
    fun `expo reports Java sources that import the Kotlin-only module DSL`(@TempDir project: Path) {
        project.resolve("Legacy.java").writeText(
            """
            import expo.modules.kotlin.modules.Module;
            class Legacy extends Module {
            }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scan()

        assertTrue(document.facts.none { it.mechanism == "expo" })
        assertTrue(document.limitations.any { it.startsWith("unscanned-expo-java:") })
    }

    @Test
    fun `expo scans a fully qualified Module supertype even when the import is present`(@TempDir project: Path) {
        project.resolve("Mixed.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class FqnModule : expo.modules.kotlin.modules.Module() {
              override fun definition() = ModuleDefinition {
                Name("Fqn")
              }
            }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scan()

        assertEquals(listOf("Fqn"), document.facts.filter { it.kind == "module-export" }.map { it.channel })
    }

    @Test
    fun `expo skips an abstract modifier on the line before the class`(@TempDir project: Path) {
        project.resolve("Base.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            abstract
            class BaseModule : Module() {
              override fun definition() = ModuleDefinition {
                Name("Base")
              }
            }
            """.trimIndent(),
        )

        assertTrue(BridgeFactScanner(project).scan().facts.isEmpty())
    }

    @Test
    fun `expo ignores a Module default value inside the constructor`(@TempDir project: Path) {
        project.resolve("Container.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class Container(val helper: Helper = Helper()) : Module() {
              override fun definition() = ModuleDefinition {
                Name("Container")
              }
            }
            """.trimIndent(),
        )

        // 생성자 기본 인자의 `=`가 슈퍼타입 탐색을 끊어서는 안 된다.
        assertEquals(
            listOf("Container"),
            BridgeFactScanner(project).scan().facts.filter { it.kind == "module-export" }.map { it.channel },
        )
    }

    @Test
    fun `expo does not treat a Module-typed constructor default as the supertype`(@TempDir project: Path) {
        project.resolve("Container.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class Container(val module: Module = Module()) : SomethingElse {
              fun definition() = ModuleDefinition {
                Name("Container")
              }
            }
            """.trimIndent(),
        )

        assertTrue(BridgeFactScanner(project).scan().facts.none { it.mechanism == "expo" })
    }

    @Test
    fun `expo still emits facts when the class scope stays open at end of file`(@TempDir project: Path) {
        project.resolve("Truncated.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class TruncatedModule : Module() {
              override fun definition() = ModuleDefinition {
                Name("Truncated")
                Function("ping") { "pong" }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scan()

        assertEquals(listOf("Truncated"), document.facts.filter { it.kind == "module-export" }.map { it.channel })
        assertEquals(listOf("ping"), document.facts.filter { it.kind == "method-handle" }.map { it.method })
    }

    @Test
    fun `expo resolves raw strings and escapes in Name literals`(@TempDir project: Path) {
        project.resolve("Names.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class RawModule : Module() {
              override fun definition() = ModuleDefinition {
                Name(""" + "\"\"\"" + """RawName""" + "\"\"\"" + """)
              }
            }
            class EscapedModule : Module() {
              override fun definition() = ModuleDefinition {
                Name("Esc\u0041ped")
              }
            }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scan()

        assertEquals(
            listOf("RawName", "EscAped"),
            document.facts.filter { it.kind == "module-export" }.map { it.channel },
        )
    }

    @Test
    fun `expo keeps an interpolated Name as a dynamic expression`(@TempDir project: Path) {
        project.resolve("Dynamic.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class DynModule : Module() {
              override fun definition() = ModuleDefinition {
                Name("prefix" + "$" + "{suffix}")
              }
            }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scan()
        val fact = document.facts.single { it.kind == "module-export" }

        assertTrue(fact.dynamic)
        assertTrue(fact.channel!!.contains("prefix"))
    }

    @Test
    fun `expo points the column at the matching call token on a shared line`(@TempDir project: Path) {
        val source = "  AsyncFunction(\"a\") { 1 }; Function(\"b\") { 2 }"
        project.resolve("Cols.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class ColsModule : Module() {
              override fun definition() = ModuleDefinition {
            $source
              }
            }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scan()
        val second = document.facts.single { it.method == "b" }

        assertEquals(source.indexOf("Function(", "AsyncFunction(".length) + 1, second.location.column)
    }

    @Test
    fun `expo ignores import text that only appears inside a string literal`(@TempDir project: Path) {
        project.resolve("Doc.kt").writeText(
            """
            val doc = "use import expo.modules.kotlin.modules.Module to declare modules"
            class Lookalike : Module() {
              fun definition() = ModuleDefinition {
                Name("Nope")
              }
            }
            """.trimIndent(),
        )

        assertTrue(BridgeFactScanner(project).scan().facts.none { it.mechanism == "expo" })
    }

    @Test
    fun `expo collects a function call whose arguments span lines`(@TempDir project: Path) {
        project.resolve("Slow.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class SlowModule : Module() {
              override fun definition() = ModuleDefinition {
                Name("Slow")
                Function(
                  "acrossLines",
                ) { "ok" }
              }
            }
            """.trimIndent(),
        )

        val document = BridgeFactScanner(project).scan()

        assertEquals(listOf("acrossLines"), document.facts.filter { it.kind == "method-handle" }.map { it.method })
    }

    @Test
    fun `expo scans async functions with nested generic types`(@TempDir project: Path) {
        project.resolve("Generic.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class GenericModule : Module() {
              override fun definition() = ModuleDefinition {
                AsyncFunction<List<String>>("nested") { emptyList() }
                AsyncFunction<Map<String, Int>>("mapped") { emptyMap() }
                AsyncFunction<Unit>("plain") { }
              }
            }
            """.trimIndent(),
        )

        val facts = BridgeFactScanner(project).scan().facts

        assertEquals(
            listOf("nested", "mapped", "plain"),
            facts.filter { it.kind == "method-handle" }.map { it.method },
        )
    }

    @Test
    fun `expo scans DSL calls with member chains and explicit this receivers`(@TempDir project: Path) {
        project.resolve("Chain.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class ChainModule : Module() {
              override fun definition() = ModuleDefinition {
                Name("Chain")
                AsyncFunction("chained") { 1 }.let { it }
                this.Function("qualified") { 2 }
              }
            }
            """.trimIndent(),
        )

        val facts = BridgeFactScanner(project).scan().facts

        assertEquals(
            listOf("chained", "qualified"),
            facts.filter { it.kind == "method-handle" }.map { it.method },
        )
    }

    @Test
    fun `expo ignores DSL calls qualified by a receiver other than this`(@TempDir project: Path) {
        project.resolve("Other.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class OtherModule : Module() {
              override fun definition() = ModuleDefinition {
                Name("Other")
                helper.Function("hidden") { 1 }
                AsyncFunction("visible") { 2 }
              }
            }
            """.trimIndent(),
        )

        val facts = BridgeFactScanner(project).scan().facts

        assertEquals(listOf("visible"), facts.filter { it.kind == "method-handle" }.map { it.method })
    }

    @Test
    fun `expo scans a fully qualified ModuleDefinition call`(@TempDir project: Path) {
        project.resolve("Fqn.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class FqnModule : Module() {
              override fun definition() = expo.modules.kotlin.modules.ModuleDefinition {
                Name("Fqn")
                AsyncFunction("found") { 1 }
              }
            }
            """.trimIndent(),
        )

        val facts = BridgeFactScanner(project).scan().facts

        val export = facts.single { it.kind == "module-export" }
        assertEquals("Fqn", export.channel)
        assertTrue(!export.dynamic)
        assertEquals(listOf("found"), facts.filter { it.kind == "method-handle" }.map { it.method })
    }

    @Test
    fun `expo ignores a member call spelled like ModuleDefinition`(@TempDir project: Path) {
        project.resolve("Fake.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class FakeModule : Module() {
              override fun definition() = ModuleDefinition {
                Name("Fake")
              }
              fun helper() {
                other.ModuleDefinition {
                  AsyncFunction("hidden") { 1 }
                }
              }
            }
            """.trimIndent(),
        )

        val facts = BridgeFactScanner(project).scan().facts

        assertEquals("Fake", facts.single { it.kind == "module-export" }.channel)
        assertTrue(facts.none { it.kind == "method-handle" })
    }

    @Test
    fun `expo resolves this qualified Name and View calls`(@TempDir project: Path) {
        project.resolve("Self.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class SelfModule : Module() {
              override fun definition() = ModuleDefinition {
                this.Name("Self")
                this.View(SensorView::class)
              }
            }
            """.trimIndent(),
        )

        val facts = BridgeFactScanner(project).scan().facts

        assertEquals("Self", facts.single { it.kind == "module-export" }.channel)
        assertEquals("Self", facts.single { it.kind == "component-export" }.channel)
    }

    @Test
    fun `expo scans generic calls qualified by this`(@TempDir project: Path) {
        project.resolve("Both.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class BothModule : Module() {
              override fun definition() = ModuleDefinition {
                this.AsyncFunction<List<String>>("both") { emptyList() }
              }
            }
            """.trimIndent(),
        )

        val facts = BridgeFactScanner(project).scan().facts

        assertEquals(listOf("both"), facts.filter { it.kind == "method-handle" }.map { it.method })
    }

    @Test
    fun `expo scans plain Function calls with generic types`(@TempDir project: Path) {
        project.resolve("Sync.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class SyncModule : Module() {
              override fun definition() = ModuleDefinition {
                Function<Pair<String, Int>>("paired") { Pair("a", 1) }
              }
            }
            """.trimIndent(),
        )

        val facts = BridgeFactScanner(project).scan().facts

        assertEquals(listOf("paired"), facts.filter { it.kind == "method-handle" }.map { it.method })
    }

    @Test
    fun `expo ignores a generic type reference without a call paren`(@TempDir project: Path) {
        project.resolve("Ref.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class RefModule : Module() {
              override fun definition() = ModuleDefinition {
                Name("Ref")
                val ref: AsyncFunction<List<String>> = placeholder()
              }
            }
            """.trimIndent(),
        )

        val facts = BridgeFactScanner(project).scan().facts

        assertTrue(facts.none { it.kind == "method-handle" })
    }

    @Test
    fun `expo anchors the call paren before comparisons inside arguments`(@TempDir project: Path) {
        project.resolve("Anchor.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class AnchorModule : Module() {
              override fun definition() = ModuleDefinition {
                AsyncFunction<Unit>("first", flag = y > (z)) { }
              }
            }
            """.trimIndent(),
        )

        val facts = BridgeFactScanner(project).scan().facts

        assertEquals(listOf("first"), facts.filter { it.kind == "method-handle" }.map { it.method })
    }

    @Test
    fun `expo scans an FQN ModuleDefinition whose brace is on the next line`(@TempDir project: Path) {
        project.resolve("FqnBrace.kt").writeText(
            """
            import expo.modules.kotlin.modules.Module
            class FqnBraceModule : Module() {
              override fun definition() = expo.modules.kotlin.modules.ModuleDefinition
              {
                Name("FqnBrace")
              }
            }
            """.trimIndent(),
        )

        val facts = BridgeFactScanner(project).scan().facts

        assertEquals("FqnBrace", facts.single { it.kind == "module-export" }.channel)
    }
}
