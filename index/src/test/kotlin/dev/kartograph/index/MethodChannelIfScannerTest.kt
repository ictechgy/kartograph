package dev.kartograph.index

import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class MethodChannelIfScannerTest {
    @Test
    fun `unmatched owner declarations and unavailable this owners cannot retain literals`(@TempDir project: Path) {
        for (source in listOf(
            "class `Plugin` {\nvar channel = MethodChannel(messenger, \"old\")\nfun register() {\nthis.channel = obtainChannel()\nchannel.setMethodCallHandler { call, result -> if (call.method == \"after\") { result.success(null) } }\n}\n}",
            "val channel = MethodChannel(messenger, \"old\")\nfun register() {\nthis.channel = obtainChannel()\nchannel.setMethodCallHandler { call, result -> if (call.method == \"after\") { result.success(null) } }\n}",
        )) {
            project.resolve("Plugin.kt").writeText(source)
            val document = BridgeFactScanner(project).scan()
            assertTrue(document.facts.all { it.channel == null && it.dynamic }, source)
            assertTrue(document.limitations.any { it.startsWith("unscanned-channel-bindings:") }, source)
        }
    }

    @Test
    fun `class literals are not unmatched owner declarations`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText("""
            val channel = MethodChannel(messenger, "camera")
            val type = Any::class.java
            channel.setMethodCallHandler { call, result ->
                if (call.method == "take") { result.success(null) }
            }
        """.trimIndent())
        val document = BridgeFactScanner(project).scan()
        assertTrue(document.facts.all { it.channel == "camera" && !it.dynamic })
        assertTrue(document.limitations.none { it.startsWith("unscanned-channel-bindings:") })
    }

    @Test
    fun `anonymous object member writes preserve inner and outer declaration ownership`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText("""
            class Outer {
                var channel = MethodChannel(messenger, "outer")
                val plugin = object {
                    var channel = MethodChannel(messenger, "inner")
                    fun register() {
                        this.channel = obtainChannel()
                        channel.setMethodCallHandler { call, result ->
                            if (call.method == "inner") { result.success(null) }
                        }
                    }
                }
                fun registerOuter() {
                    channel.setMethodCallHandler { call, result ->
                        if (call.method == "outer") { result.success(null) }
                    }
                }
            }
        """.trimIndent())
        val document = BridgeFactScanner(project).scan()
        val methods = document.facts.filter { it.kind == "method-handle" }
        assertTrue(methods.first { it.method == "inner" }.let { it.channel == null && it.dynamic })
        assertTrue(methods.first { it.method == "outer" }.let { it.channel == "outer" && !it.dynamic })
        assertTrue(document.limitations.any { it.startsWith("unresolved-channel-writes:") })
    }

    @Test
    fun `delegated object headers keep class member bindings explicitly unknown`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText("""
            interface Marker
            class Plugin : Marker by object : Marker {} {
                var channel = MethodChannel(messenger, "old")
                fun register() {
                    this.channel = obtainChannel()
                    channel.setMethodCallHandler { call, result ->
                        if (call.method == "after") { result.success(null) }
                    }
                }
            }
        """.trimIndent())
        val document = BridgeFactScanner(project).scan()
        assertTrue(document.facts.all { it.channel == null && it.dynamic })
        assertTrue(document.limitations.any { it.startsWith("unscanned-channel-bindings:") && "Plugin.kt" in it })
    }

    @Test
    fun `labelled this writes keep receiver ownership explicitly unknown`(@TempDir project: Path) {
        for (source in listOf(
            "class Plugin {\nvar channel = MethodChannel(messenger, \"old\")\nfun register() { this@Plugin.channel = obtainChannel()\nchannel.setMethodCallHandler { call, result -> if (call.method == \"after\") { result.success(null) } }\n}\n}",
            "class Outer {\nvar channel = MethodChannel(messenger, \"old\")\ninner class Inner { fun register() { this@Outer.channel = obtainChannel()\nchannel.setMethodCallHandler { call, result -> if (call.method == \"after\") { result.success(null) } }\n}\n}\n}",
        )) {
            project.resolve("Plugin.kt").writeText(source)
            val document = BridgeFactScanner(project).scan()
            assertTrue(document.facts.all { it.channel == null && it.dynamic }, source)
            assertTrue(document.limitations.any { it.startsWith("unscanned-channel-bindings:") && "Plugin.kt" in it }, source)
        }
    }

    @Test
    fun `if handler UTF8 columns include original unicode comments`(@TempDir project: Path) {
        val handler = "channel.setMethodCallHandler { call, result -> /* 한글😀 */ if (call.method == \"take\") { result.success(null) } }"
        project.resolve("Plugin.kt").writeText("val channel = MethodChannel(messenger, \"camera\")\n$handler\n")
        val fact = BridgeFactScanner(project).scan().facts.single { it.kind == "method-handle" }
        val prefix = handler.substring(0, handler.indexOf("\"take\"") + 1)
        assertEquals(2, fact.location.line)
        assertEquals(prefix.toByteArray(Charsets.UTF_8).size + 1, fact.location.column)
    }

    @Test
    fun `constructor argument lambdas do not become the class owner scope`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText("""
            class Plugin : ExternalBase({ Unit }) {
                var channel = MethodChannel(messenger, "field")
                fun register() {
                    if (enabled) this.channel = obtainChannel()
                    channel.setMethodCallHandler { call, result ->
                        if (call.method == "field") { result.success(null) }
                    }
                }
            }
        """.trimIndent())
        val document = BridgeFactScanner(project).scan()
        assertTrue(document.facts.all { it.channel == null && it.dynamic })
        assertTrue(document.limitations.any { it.startsWith("unresolved-channel-writes:") })
    }

    @Test
    fun `string literal prefixes do not prove the complete name value`(@TempDir project: Path) {
        for (initializer in listOf("\"prefix\" + suffix", "\"prefix\"\n    .lowercase()", "\"prefix\"\n    get() = computedName")) {
            project.resolve("Plugin.kt").writeText("""
                val name = $initializer
                val channel = MethodChannel(messenger, name)
                channel.setMethodCallHandler { call, result ->
                    if (call.method == "current") { result.success(null) }
                }
            """.trimIndent())
            val document = BridgeFactScanner(project).scan()
            assertTrue(document.facts.all { it.channel == null && it.dynamic }, initializer)
            assertTrue(document.limitations.any { it.startsWith("unscanned-channel-bindings:") }, initializer)
        }
    }

    @Test
    fun `binding parameters outside ordinary functions cannot reuse an outer channel`(@TempDir project: Path) {
        for (source in listOf(
            "val channel = MethodChannel(messenger, \"outer\")\nfun register() { for (channel in channels) { channel.setMethodCallHandler { call, result -> if (call.method == \"inner\") { result.success(null) } } } }",
            "val channel = MethodChannel(messenger, \"outer\")\nfun register() { try { fail() } catch (channel: CustomError) { channel.setMethodCallHandler { call, result -> if (call.method == \"inner\") { result.success(null) } } } }",
            "val channel = MethodChannel(messenger, \"outer\")\nvar target: MethodChannel = initial\nset(channel) { channel.setMethodCallHandler { call, result -> if (call.method == \"inner\") { result.success(null) } } }",
        )) {
            project.resolve("Plugin.kt").writeText(source)
            val document = BridgeFactScanner(project).scan()
            assertTrue(document.facts.filter { it.kind == "method-handle" }.all { it.channel == null && it.dynamic }, source)
            assertTrue(document.limitations.any { it.startsWith("unattributed-method-handles:") }, source)
        }
    }

    @Test
    fun `conditional constructors cannot restore a definite post-branch binding`(@TempDir project: Path) {
        for (write in listOf("if (enabled) channel = MethodChannel(messenger, \"new\")", "if (enabled)\n        channel = MethodChannel(messenger, \"new\")", "if (enabled) Unit else channel = MethodChannel(messenger, \"new\")")) {
            project.resolve("Plugin.kt").writeText("""
                fun register() {
                    var channel = MethodChannel(messenger, "old")
                    $write
                    channel.setMethodCallHandler { call, result ->
                        if (call.method == "current") { result.success(null) }
                    }
                }
            """.trimIndent())
            val document = BridgeFactScanner(project).scan()
            assertTrue(document.facts.all { it.channel == null && it.dynamic }, write)
            assertTrue(document.limitations.any { it.startsWith("unresolved-channel-writes:") }, write)
        }
    }

    @Test
    fun `a qualified constructor assignment does not replace an unrelated local`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText("""
            fun register() {
                val channel = MethodChannel(messenger, "local")
                other . channel = MethodChannel(messenger, "foreign")
                channel.setMethodCallHandler { call, result ->
                    if (call.method == "local") { result.success(null) }
                }
            }
        """.trimIndent())
        assertTrue(BridgeFactScanner(project).scan().facts.all { it.channel == "local" && !it.dynamic })
    }

    @Test
    fun `own field writes do not invalidate a shadowing local and retain field gaps`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText("""
            class Plugin {
                var channel = MethodChannel(messenger, "field")
                fun register() {
                    val channel = MethodChannel(messenger, "local")
                    if (enabled) this.channel = obtainChannel()
                    channel.setMethodCallHandler { call, result ->
                        if (call.method == "local") { result.success(null) }
                    }
                    this.channel.setMethodCallHandler { call, result ->
                        if (call.method == "field") { result.success(null) }
                    }
                }
            }
        """.trimIndent())
        val facts = BridgeFactScanner(project).scan().facts.filter { it.kind == "method-handle" }
        assertEquals("local", facts.first { it.method == "local" }.channel)
        assertTrue(facts.first { it.method == "field" }.let { it.channel == null && it.dynamic })
    }

    @Test
    fun `unbraced writes invalidate a literal channel with source evidence`(@TempDir project: Path) {
        for (write in listOf("if (enabled) channel = obtainChannel()", "if (enabled) Unit else channel = obtainChannel()", "while (enabled) channel = obtainChannel()")) {
            project.resolve("Plugin.kt").writeText("""
                fun register() {
                    var channel = MethodChannel(messenger, "old")
                    $write
                    channel.setMethodCallHandler { call, result ->
                        if (call.method == "current") { result.success(null) }
                    }
                }
            """.trimIndent())
            val document = BridgeFactScanner(project).scan()
            assertTrue(document.facts.filter { it.kind == "method-handle" }.all { it.channel == null && it.dynamic }, write)
            assertTrue(document.limitations.any { it.startsWith("unresolved-channel-writes:") && "Plugin.kt:3" in it }, write)
        }
    }

    @Test
    fun `string names and channel instances cannot replace each other`(@TempDir project: Path) {
        for (source in listOf(
            "val channel = MethodChannel(messenger, \"outer\")\nfun register() { val channel = \"wrong-kind\"\nchannel.setMethodCallHandler { call, result -> if (call.method == \"handle\") { result.success(null) } }\n}",
            "val instance = MethodChannel(messenger, \"outer\")\nval channel = MethodChannel(messenger, instance)\nchannel.setMethodCallHandler { call, result -> if (call.method == \"handle\") { result.success(null) } }",
        )) {
            project.resolve("Plugin.kt").writeText(source)
            val document = BridgeFactScanner(project).scan()
            assertTrue(document.facts.all { it.channel == null && it.dynamic }, source)
            assertTrue(document.limitations.any { it.startsWith("incompatible-channel-bindings:") }, source)
        }
    }

    @Test
    fun `unsupported declaration bindings fail closed with an explicit gap`(@TempDir project: Path) {
        for (source in listOf(
            "val channel = MethodChannel(messenger, \"outer\")\nclass Plugin(channel: MethodChannel) { init { channel.setMethodCallHandler { call, result -> if (call.method == \"inner\") { result.success(null) } } } }",
            "val channel = MethodChannel(messenger, \"outer\")\nfun register() { val (channel, other) = pair\nchannel.setMethodCallHandler { call, result -> if (call.method == \"inner\") { result.success(null) } }\n}",
        )) {
            project.resolve("Plugin.kt").writeText(source)
            val document = BridgeFactScanner(project).scan()
            assertTrue(document.facts.filter { it.kind == "method-handle" }.all { it.channel == null && it.dynamic }, source)
            assertTrue(document.limitations.any { it.startsWith("unscanned-channel-bindings:") }, source)
        }
    }

    @Test
    fun `compound name writes do not retain a stale literal`(@TempDir project: Path) {
        for (declaration in listOf("var name = \"old\"\n                name += suffix", "var name = \"old\"; name += suffix")) {
            project.resolve("Plugin.kt").writeText("""
            fun register() {
                $declaration
                val channel = MethodChannel(messenger, name)
                channel.setMethodCallHandler { call, result ->
                    if (call.method == "current") { result.success(null) }
                }
            }
        """.trimIndent())
            val document = BridgeFactScanner(project).scan()
            assertTrue(document.facts.all { it.channel == null && it.dynamic }, declaration)
            assertTrue(document.limitations.any { it.startsWith("unresolved-channel-writes:") || it.startsWith("unscanned-channel-bindings:") }, declaration)
        }
    }

    @Test
    fun `foreign field writes and named arguments do not mutate a local channel binding`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText("""
            fun register() {
                val channel = MethodChannel(messenger, "local")
                if (enabled) other . channel = obtainChannel()
                configure(channel = unrelated)
                channel.setMethodCallHandler { call, result ->
                    if (call.method == "local") { result.success(null) }
                }
            }
        """.trimIndent())
        val document = BridgeFactScanner(project).scan()
        assertTrue(document.facts.all { it.channel == "local" && !it.dynamic })
    }

    @Test
    fun `unmodeled extension and implicit receiver scopes never reuse a literal channel`(@TempDir project: Path) {
        for (body in listOf(
            "val channel = MethodChannel(messenger, \"outer\")\nfun Any.extension(channel: Other) {\nchannel.setMethodCallHandler { call, result -> if (call.method == \"foreign\") { result.success(null) } }\n}",
            "val it = MethodChannel(messenger, \"outer\")\nother.let {\nit.setMethodCallHandler { call, result -> if (call.method == \"foreign\") { result.success(null) } }\n}",
        )) {
            project.resolve("Plugin.kt").writeText(body)
            val document = BridgeFactScanner(project).scan()
            assertTrue(document.facts.filter { it.kind == "method-handle" }.all { it.channel == null && it.dynamic }, body)
            assertTrue(document.limitations.any { it.startsWith("unattributed-method-handles: 1") }, body)
        }
    }

    @Test
    fun `opaque writes invalidate a previously literal channel including branch writes`(@TempDir project: Path) {
        for (write in listOf("channel = otherFactory()", "if (enabled) { channel = otherFactory() }")) {
            project.resolve("Plugin.kt").writeText("""
                fun register() {
                    var channel = MethodChannel(messenger, "outer")
                    $write
                    channel.setMethodCallHandler { call, result ->
                        if (call.method == "opaque") { result.success(null) }
                    }
                }
            """.trimIndent())
            val document = BridgeFactScanner(project).scan()
            assertTrue(document.facts.filter { it.kind == "method-handle" }.all { it.channel == null && it.dynamic }, write)
            assertTrue(document.limitations.any { it.startsWith("unattributed-method-handles: 1") }, write)
        }
    }

    @Test
    fun `never promotes an if expression owned by another statement`(@TempDir project: Path) {
        val prefixes = listOf("if (enabled)", "while (enabled)", "val ignored =", "fun unrelated(call: Other) =")
        for (prefix in prefixes) {
            project.resolve("Plugin.kt").writeText("""
                val channel = MethodChannel(messenger, "camera")
                channel.setMethodCallHandler { call, result ->
                    $prefix
                        if (call.method == "guarded") { result.success(null) } else Unit
                }
            """.trimIndent())
            val document = BridgeFactScanner(project).scan()
            assertTrue(document.facts.none { it.kind == "method-handle" }, prefix)
            assertTrue(document.limitations.any { it.startsWith("unscanned-method-conditions:") }, prefix)
        }
    }

    @Test
    fun `unsupported typed callback headers retain an explicit gap`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText("""
            val channel = MethodChannel(messenger, "camera")
            channel.setMethodCallHandler { call: MethodCall, result: MethodChannel.Result ->
                if (call.method == "typed") { result.success(null) }
            }
        """.trimIndent())
        val document = BridgeFactScanner(project).scan()
        assertTrue(document.facts.none { it.kind == "method-handle" })
        assertTrue(document.limitations.any { it.startsWith("unscanned-method-conditions: 1") })
    }

    @Test
    fun `unknown local and function parameter channels hide outer literal bindings`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText("""
            val channel = MethodChannel(messenger, "outer")
            fun parameter(channel: Other) {
                channel.setMethodCallHandler { call, result ->
                    if (call.method == "parameter") { result.success(null) }
                }
            }
            fun local() {
                val channel = otherFactory()
                channel.setMethodCallHandler { call, result ->
                    if (call.method == "local") { result.success(null) }
                }
            }
        """.trimIndent())
        val document = BridgeFactScanner(project).scan()
        assertTrue(document.facts.filter { it.kind == "method-handle" }.all { it.channel == null && it.dynamic })
        assertTrue(document.limitations.any { it.startsWith("unattributed-method-handles: 2") })
    }

    @Test
    fun `companion constants stay visible inside companion registration functions`(@TempDir project: Path) {
        for (header in listOf("companion object", "companion object Channels")) {
            project.resolve("Plugin.kt").writeText("""
                class Camera {
                    $header {
                        private const val CHANNEL = "camera"
                        fun register() {
                            val channel = MethodChannel(messenger, CHANNEL)
                            channel.setMethodCallHandler { call, result ->
                                if (call.method == "take") { result.success(null) }
                            }
                        }
                    }
                    fun registerOutside() {
                        val channel = MethodChannel(messenger, CHANNEL)
                        channel.setMethodCallHandler { call, result ->
                            if (call.method == "outside") { result.success(null) }
                        }
                    }
                }
                class Unrelated {
                    fun register() {
                        val channel = MethodChannel(messenger, CHANNEL)
                        channel.setMethodCallHandler { call, result ->
                            if (call.method == "unknown") { result.success(null) }
                        }
                    }
                }
            """.trimIndent())
            val document = BridgeFactScanner(project).scan()
            val methods = document.facts.filter { it.kind == "method-handle" }.associateBy { it.method }
            assertEquals(setOf("take", "outside", "unknown"), methods.keys, header)
            assertTrue(methods.getValue("take").let { it.channel == "camera" && !it.dynamic }, header)
            assertTrue(methods.getValue("outside").let { it.channel == "camera" && !it.dynamic }, header)
            assertTrue(methods.getValue("unknown").let { it.channel == "CHANNEL" && it.dynamic }, header)
        }
    }

    @Test
    fun `resolves companion constants within their own enclosing class`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText("""
            class Camera {
                companion object { private const val CHANNEL = "camera" }
                fun register() {
                    val channel = MethodChannel(messenger, CHANNEL)
                    channel.setMethodCallHandler { call, result ->
                        if (call.method == "take") { result.success(null) }
                    }
                }
            }
            class Location {
                companion object { private const val CHANNEL = "location" }
                fun register() {
                    val channel = MethodChannel(messenger, CHANNEL)
                    channel.setMethodCallHandler { call, result ->
                        if (call.method == "locate") { result.success(null) }
                    }
                }
            }
        """.trimIndent())

        val document = BridgeFactScanner(project).scan()
        assertEquals(listOf("camera" to "take", "location" to "locate"),
            document.facts.filter { it.kind == "method-handle" }.map { it.channel to it.method })
        assertTrue(document.facts.none { it.dynamic })
    }

    @Test
    fun `records the Flutter hardware smoke test if handler and ignores cleanup`(@TempDir project: Path) {
        project.resolve("MainActivity.kt").writeText("""
            private const val METHOD_CHANNEL_NAME = "com.example.android_hardware_smoke_test/native_support"
            fun configure() {
                methodChannel = MethodChannel(messenger, METHOD_CHANNEL_NAME)
                methodChannel?.setMethodCallHandler { call, result ->
                    if (call.method == "impeller_backend") {
                        result.success(impellerBackend)
                    } else {
                        result.notImplemented()
                    }
                }
                nativeDriverChannel = MethodChannel(messenger, "native_driver")
                nativeDriverChannel?.setMethodCallHandler { call, result ->
                    when (call.method) {
                        "sdk_version" -> result.success(34)
                        "is_emulator" -> result.success(false)
                        "ping" -> result.success(null)
                        else -> result.notImplemented()
                    }
                }
            }
            fun cleanup() {
                methodChannel?.setMethodCallHandler(null)
                nativeDriverChannel?.setMethodCallHandler(null)
            }
        """.trimIndent())

        val document = BridgeFactScanner(project).scan()

        assertEquals(listOf("impeller_backend", "sdk_version", "is_emulator", "ping"),
            document.facts.filter { it.kind == "method-handle" }.map { it.method })
        assertEquals(listOf("com.example.android_hardware_smoke_test/native_support", "native_driver"),
            document.facts.filter { it.kind == "channel-register" }.map { it.channel })
        assertTrue(document.limitations.none { it.startsWith("unscanned-method-handlers:") })
        assertEquals(null, document.facts.first { it.method == "impeller_backend" }.symbol)
        assertTrue(document.limitations.any { it.startsWith("missing-handler-usrs: source scanning cannot resolve JVM identifiers for 4") })
    }

    @Test
    fun `only accepts literal equality on the registered call parameter in direct branches`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText("""
            val camera = MethodChannel(messenger, "camera")
            camera.setMethodCallHandler { request, result ->
                if (request.method == "take") { result.success(null) }
                else if ("stop" == request.method) { result.success(null) }
                if (request.method == "gated" && enabled) { result.success(null) }
                if (request.method == dynamicName) { result.success(null) }
                if (other.method == "foreign") { result.success(null) }
                val text = "if (request.method == \"text\") { }"
                fun unrelated(request: Call) {
                    if (request.method == "nested") { result.success(null) }
                }
            }
            val location = MethodChannel(messenger, "location")
            location.setMethodCallHandler { call, result ->
                if (call.method == "locate") { result.success(null) }
            }
            if (call.method == "outside") { Unit }
        """.trimIndent())

        val document = BridgeFactScanner(project).scan()

        assertEquals(listOf("camera" to "take", "camera" to "stop", "location" to "locate"),
            document.facts.filter { it.kind == "method-handle" }.map { it.channel to it.method })
        assertTrue(document.limitations.any { it.startsWith("unscanned-method-conditions:") })
    }

    @Test
    fun `ignores null cleanup in variable chained and multiline forms`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText("""
            val camera = MethodChannel(messenger, "camera")
            camera.setMethodCallHandler(null)
            MethodChannel(messenger, "direct").setMethodCallHandler(null)
            MethodChannel(messenger, "multiline")
                .setMethodCallHandler(
                    null
                )
            camera.setMethodCallHandler(callback)
        """.trimIndent())

        val document = BridgeFactScanner(project).scan()

        assertEquals(listOf("camera"), document.facts.map { it.channel })
        assertEquals(listOf("unscanned-method-handlers: 1 handler callback(s) are not inline lambdas"), document.limitations)
    }

    @Test
    fun `does not leak a shadowed channel binding outside its lexical block`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText("""
            fun register() {
                val channel = MethodChannel(messenger, "outer")
                run {
                    val channel = MethodChannel(messenger, "inner")
                    channel.setMethodCallHandler { call, result ->
                        if (call.method == "inside") { result.success(null) }
                    }
                }
                channel.setMethodCallHandler { call, result ->
                    if (call.method == "outside") { result.success(null) }
                }
            }
        """.trimIndent())

        assertEquals(listOf("inner" to "inside", "outer" to "outside"),
            BridgeFactScanner(project).scan().facts.filter { it.kind == "method-handle" }.map { it.channel to it.method })
    }

    @Test
    fun `keeps multiline lambda when handlers while collecting if handlers`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText("""
            val channel = MethodChannel(messenger, "camera")
            channel.setMethodCallHandler(
                { call, result ->
                    if (call.method == "start") { result.success(null) }
                    when (call.method) { "stop" -> result.success(null) }
                }
            )
        """.trimIndent())

        assertEquals(listOf("start", "stop"),
            BridgeFactScanner(project).scan().facts.filter { it.kind == "method-handle" }.map { it.method })
    }

    @Test
    fun `does not infer routes after a call parameter is shadowed or a chain is guarded`(@TempDir project: Path) {
        project.resolve("Plugin.kt").writeText("""
            val camera = MethodChannel(messenger, "camera")
            camera.setMethodCallHandler { call, result ->
                val call = anotherRequest
                if (call.method == "foreign") { result.success(null) }
            }
            val location = MethodChannel(messenger, "location")
            location.setMethodCallHandler { call, result ->
                if (enabled) { Unit }
                else if (call.method == "guarded") { result.success(null) }
            }
        """.trimIndent())

        val document = BridgeFactScanner(project).scan()
        assertTrue(document.facts.none { it.kind == "method-handle" })
        assertTrue(document.limitations.any { it.startsWith("unscanned-method-conditions:") })
    }
}
