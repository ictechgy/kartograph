package dev.kartograph.index

import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ReactNativeEventScannerTest {
    @Test
    fun `fully qualified RN emissions need no import and malformed closers stay dynamic`(@TempDir root: Path) {
        root.resolve("Events.kt").writeText("""
            fun notify() {
              context.getJSModule(com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit("ready", null)
              context.getJSModule(com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit("broken"])
            }
        """.trimIndent())
        val facts = BridgeFactScanner(root).scanReactNativeEvents().facts
        assertEquals(2, facts.size)
        assertEquals("ready", facts[0].channel)
        assertEquals(false, facts[0].dynamic)
        assertEquals(true, facts[1].dynamic)
        assertEquals("\"broken\"", facts[1].channel)
    }

    @Test
    fun `typed RN event emissions preserve literal dynamic and multiline names`(@TempDir root: Path) {
        root.resolve("Events.kt").writeText("""
            import com.facebook.react.modules.core.DeviceEventManagerModule
            class Events {
              fun notify() {
                context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                  .emit("ready", null)
                context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(name, null)
                other.emit("unrelated", null)
              }
            }
        """.trimIndent())
        val document = BridgeFactScanner(root).scanReactNativeEvents()
        assertEquals("react-native-event", document.transport)
        assertEquals("react-native", document.target)
        assertEquals(2, document.version)
        assertEquals(listOf("ready", "name"), document.facts.map { it.channel })
        assertEquals(listOf(false, true), document.facts.map { it.dynamic })
        assertTrue(document.facts.all { it.kind == "event-emit" })
        assertTrue(document.limitations.any { it.startsWith("missing-event-usrs: 2") })
    }

    @Test
    fun `comments and quoted fake code cannot create event emissions`(@TempDir root: Path) {
        root.resolve("Events.java").writeText("""
            import com.facebook.react.modules.core.DeviceEventManagerModule;
            class Events {
              void notify() {
                // context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit("fake", null);
                String fake = "context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(\"fake\", null)";
                context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit("real", null);
              }
            }
        """.trimIndent())
        assertEquals(listOf("real"), BridgeFactScanner(root).scanReactNativeEvents().facts.map { it.channel })
    }
}
