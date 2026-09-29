package dev.kartograph.index

import dev.kartograph.core.CallResolution
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/**
 * 하위 인터페이스를 수신 타입으로 부른 상속 추상 메서드가 JVM 인터페이스 메서드 해석이 고르는 선언으로 CALL 간선을 얻는지
 * javac 바이트코드로 고정한다. 수정 전에는 `invokeinterface Sub.m`의 `Sub#m` 정점이 없어 간선이 사라졌다.
 */
class InheritedInterfaceCallTest {
    @Test
    fun `sub-interface receivers call the maximally specific abstract declaration`(@TempDir root: Path) {
        val classes = compileJava(root, """
            package probe;
            interface Base { String m(); }
            interface Sub extends Base {}
            interface Mid extends Base { String m(); }
            interface Leaf extends Mid {}
            interface Left { String both(); }
            interface Right { String both(); }
            interface Joined extends Left, Right {}
            interface Defaults { default String d() { return ""; } }
            interface SubDefaults extends Defaults {}
            interface Named { String toString(); }
            interface SubNamed extends Named {}
            abstract class Partial implements Base {}
            public final class Entry {
                String inherited(Sub value) { return value.m(); }
                String redeclared(Leaf value) { return value.m(); }
                String diamond(Joined value) { return value.both(); }
                String defaults(SubDefaults value) { return value.d(); }
                String objectMethod(SubNamed value) { return value.toString(); }
                String classReceiver(Partial value) { return value.m(); }
                String direct(Base value) { return value.m(); }
            }
        """.trimIndent())

        val graph = ClassFileIndexer().index(listOf(classes))
        val calls = graph.edges.filter { it.kind == EdgeKind.CALL && it.source.value.startsWith("method:probe/Entry#") }
            .groupBy({ it.source.value.substringAfter('#').substringBefore('(') }, { it.target.value })

        assertEquals(listOf("method:probe/Base#m()Ljava/lang/String;"), calls["inherited"])
        assertEquals(listOf("method:probe/Mid#m()Ljava/lang/String;"), calls["redeclared"], "the redeclaration hides Base#m")
        assertEquals(listOf("method:probe/Left#both()Ljava/lang/String;", "method:probe/Right#both()Ljava/lang/String;"), calls["diamond"]?.sorted())
        assertEquals(null, calls["defaults"], "default methods are dispatch candidates, not added calls")
        assertEquals(null, calls["objectMethod"], "interface resolution picks java/lang/Object first")
        assertEquals(null, calls["classReceiver"], "class receivers keep the dispatch model")
        assertEquals(listOf("method:probe/Base#m()Ljava/lang/String;"), calls["direct"])
        // 실행 대상 해석은 그대로다 — 구현이 없는 상속 호출은 여전히 external-dispatch로 센다.
        assertEquals(CallResolution.UNRESOLVED, graph.externalCalls.single { it.owner == "probe/Sub" }.resolution)
        assertEquals(
            listOf("method:probe/Defaults#d()Ljava/lang/String;"),
            graph.edges.filter { it.origin == EdgeOrigin.DISPATCH_MODEL && it.source.value.contains("#defaults(") }.map { it.target.value },
        )
    }

    private fun compileJava(root: Path, source: String): Path {
        val sourceFile = root.resolve("Entry.java")
        Files.writeString(sourceFile, source)
        val classes = root.resolve("classes").createDirectories()
        val errors = ByteArrayOutputStream()
        val arguments = arrayOf("--release", "17", "-g", "-d", classes.toString(), sourceFile.toString())
        assertEquals(0, requireNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, errors, *arguments), errors.toString())
        return classes
    }
}
