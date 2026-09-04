package dev.kartograph.index

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class BridgeFactScannerTest {
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
        assertEquals(".", document.project)
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
}
