package dev.kartograph.index

import java.nio.file.Files
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

    @Test
    fun `a double quote character literal does not expose commented emissions`(@TempDir root: Path) {
        // `'"'` 안의 `"`를 문자열 시작으로 읽으면 뒤 주석이 코드로 남아 주석 속 emit이 사실이 된다.
        root.resolve("Events.kt").writeText("""
            import com.facebook.react.modules.core.DeviceEventManagerModule
            class Events {
              fun isQuote(c: Char) = c == '"'
              fun notify() {
                // context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit("legacyEvent", null)
                context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit("ready", null)
              }
            }
        """.trimIndent())
        val facts = BridgeFactScanner(root).scanReactNativeEvents().facts
        assertEquals(listOf("ready"), facts.map { it.channel })
        assertEquals(listOf(6), facts.map { it.location.line })
    }

    @Test
    fun `RN event documents share the project value of the other exchange documents`(@TempDir parent: Path) {
        // isthmus는 project 문자열이 같아야 조인한다. POSIX에서는 `\`가 이름 문자라 Windows 구분자 차이를 그대로 재현한다.
        val root = Files.createDirectories(parent.resolve("work\\app"))
        root.resolve("Events.kt").writeText("""
            import com.facebook.react.modules.core.DeviceEventManagerModule
            class Events {
              fun notify() {
                context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit("ready", null)
              }
            }
        """.trimIndent())
        val scanner = BridgeFactScanner(root)
        val projects = listOf(
            scanner.scanReactNativeEvents().project, scanner.scan().project, scanner.scanMessages().project, scanner.scanEvents().project,
            RouteCallScanner(root).scan().project, RouteDeclScanner(root).scan().project, SchemaFactScanner(root).scan().project,
        )
        val canonical = root.toRealPath().toString().replace('\\', '/')
        assertEquals(List(projects.size) { canonical }, projects)
    }

    @Test
    fun `project identity writes Windows separators as forward slashes`() {
        // Windows 파일 시스템 없이 문자열 규칙만 본다 — POSIX에서는 `\`가 이름 문자라 경로가 그대로 남는다.
        assertEquals("C:/work/app", projectIdentity(Path.of("C:\\work\\app")))
        assertEquals("/work/app", projectIdentity(Path.of("/work/app")))
    }
}
