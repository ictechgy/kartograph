package dev.kartograph.index

import dev.kartograph.core.*
import dev.kartograph.index.fixture.directinterface.*
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.coroutines.Continuation
import kotlin.io.path.createDirectories
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

/** 실제 javac/Kotlin 산출물과 JVM body를 declaration·raw CALL·dispatch별로 독립 검증한다. */
class DirectInterfaceDeclarationJvmTest {
    private val sdk = Path.of(System.getProperty("java.home"))

    @Test
    fun `real Kotlin List Continuation and project interface preserve raw and dispatch facts`(@TempDir root: Path) {
        val source = Path.of(ProjectContract::class.java.protectionDomain.codeSource.location.toURI()).resolve(PACKAGE)
        val classes = root.resolve("classes")
        val target = classes.resolve(PACKAGE).createDirectories()
        Files.list(source).use { files -> files.filter { it.toString().endsWith(".class") }.forEach { Files.copy(it, target.resolve(it.fileName)) } }
        val stdlib = Path.of(Continuation::class.java.protectionDomain.codeSource.location.toURI())
        val cache = ClassIndexCache(root.resolve("cache"), "direct-interface-kotlin")
        fun index() = ClassFileIndexer(sdk, cache).indexWithObservations(listOf(classes), listOf(stdlib))
        val cold = index()
        val warm = index()
        assertEquals(0, warm.statistics.parsedClasses)
        assertEquals(cold.graph.nodes, warm.graph.nodes)
        assertEquals(cold.graph.edges, warm.graph.edges)
        assertEquals(cold.graph.externalCalls, warm.graph.externalCalls)
        assertEquals(warm.graph.externalCalls, warm.withHierarchy(warm.hierarchy).graph.externalCalls)
        assertEquals(warm.graph.edges, warm.withHierarchy(warm.hierarchy).graph.edges)
        val list = warm.graph.externalCalls.single { it.owner == "java/util/List" && it.name == "size" }
        val continuation = warm.graph.externalCalls.single { it.owner == "kotlin/coroutines/Continuation" && it.name == "resumeWith" }
        for ((call, body) in listOf(list to "ProjectList#size()I", continuation to "ProjectContinuation#resumeWith(Ljava/lang/Object;)V")) {
            assertEquals(InvocationKind.INTERFACE, call.kind)
            assertEquals(CallDeclaration(call.target, CallDeclarationScope.EXTERNAL, listOf(call.owner)), call.declaration)
            assertContains(call.resolvedTargets, NodeId("method:$PACKAGE/$body"))
            assertFalse(call.target in warm.graph.nodes)
        }
        val directCaller = NodeId("method:$PACKAGE/DirectInterfaceFixturesKt#direct(L$PACKAGE/ProjectContract;)I")
        val contract = NodeId("method:$PACKAGE/ProjectContract#value()I")
        val body = NodeId("method:$PACKAGE/ProjectImplementation#value()I")
        assertTrue(warm.graph.edges.any { it.source == directCaller && it.target == contract && it.kind == EdgeKind.CALL && it.origin == EdgeOrigin.BYTECODE })
        assertTrue(warm.graph.edges.any { it.source == directCaller && it.target == body && it.origin == EdgeOrigin.DISPATCH_MODEL })
        assertTrue(warm.graph.externalCalls.none { it.caller == directCaller && it.target == contract })
        val inheritedCall = warm.graph.externalCalls.single { it.owner == "$PACKAGE/InheritedContract" && it.name == "value" }
        assertNull(inheritedCall.declaration)
        assertEquals(61, direct(ProjectImplementation()))
        assertEquals(61, inherited(ProjectImplementation()))
        assertEquals(47, externalList(ProjectList()))
        val instance = ProjectContinuation()
        resume(instance)
        assertEquals("resumed", instance.observed)
    }

    @Test
    fun `real javac public Object redeclaration and default use only direct external headers`(@TempDir root: Path) {
        val classes = compile(root, mapOf("p/Entry.java" to """
            package p;
            public class Entry {
                public interface Textual { String toString(); }
                public interface Parent { default int value() { return 7; } }
                public interface Child extends Parent {}
                public interface Plain {}
                public interface PrivateContract { private int hidden() { return 9; } default int invokePrivate() { return hidden(); } }
                public static class PrivateImpl implements PrivateContract {}
                public static class Printable implements Textual, Plain { public String toString() { return "printable"; } }
                public static class Implementation implements Child {}
                public static class Values extends java.util.AbstractList<String> {
                    public int size() { return 47; } public String get(int index) { return "element"; }
                }
                public static String textual(Textual value) { return value.toString(); }
                public static String plain(Plain value) { return value.toString(); }
                public static int direct(Parent value) { return value.value(); }
                public static int inherited(Child value) { return value.value(); }
                public static int list(java.util.List<String> value) { return value.size(); }
            }
        """.trimIndent()))
        val allProject = ClassFileIndexer(sdk).indexWithObservations(listOf(classes))
        assertTrue(allProject.graph.externalCalls.none { it.owner == "p/Entry\$Textual" })
        val privateTarget = NodeId("method:p/Entry\$PrivateContract#hidden()I")
        val privateCall = allProject.graph.edges.single { it.target == privateTarget && it.kind == EdgeKind.CALL }
        assertEquals(EdgeOrigin.BYTECODE, privateCall.origin)
        assertTrue(allProject.graph.externalCalls.none { it.target == privateTarget })
        val dependency = root.resolve("interfaces.jar")
        jar(classes, dependency, listOf("p/Entry\$Textual.class", "p/Entry\$Parent.class", "p/Entry\$Child.class"))
        listOf("Textual", "Parent", "Child").forEach { Files.delete(classes.resolve("p/Entry\$$it.class")) }
        val indexed = ClassFileIndexer(sdk).indexWithObservations(listOf(classes), listOf(dependency))
        val graph = indexed.graph
        assertContains(graph.edges, privateCall)
        assertTrue(graph.externalCalls.none { it.target == privateTarget })
        for (owner in listOf("p/Entry\$Textual", "p/Entry\$Parent", "java/util/List")) {
            val call = graph.externalCalls.single { it.owner == owner && it.kind == InvocationKind.INTERFACE }
            assertEquals(call.target, assertNotNull(call.declaration).target)
            assertEquals(CallDeclarationScope.EXTERNAL, call.declaration?.scope)
            assertEquals(listOf(owner), call.declaration?.ownerChain)
        }
        assertNull(graph.externalCalls.single { it.owner == "p/Entry\$Child" && it.name == "value" }.declaration)
        val plain = graph.externalCalls.single { it.caller.value.contains("#plain(") && it.name == "toString" }
        assertEquals(InvocationKind.VIRTUAL, plain.kind)
        assertEquals("java/lang/Object", plain.owner)
        assertEquals(plain.target, plain.declaration?.target)
        URLClassLoader(arrayOf(classes.toUri().toURL(), dependency.toUri().toURL()), null).use { loader ->
            val entry = loader.loadClass("p.Entry")
            val printable = loader.loadClass("p.Entry\$Printable").getConstructor().newInstance()
            val implementation = loader.loadClass("p.Entry\$Implementation").getConstructor().newInstance()
            assertEquals("printable", entry.getMethod("textual", loader.loadClass("p.Entry\$Textual")).invoke(null, printable))
            assertEquals("printable", entry.getMethod("plain", loader.loadClass("p.Entry\$Plain")).invoke(null, printable))
            assertEquals(7, entry.getMethod("direct", loader.loadClass("p.Entry\$Parent")).invoke(null, implementation))
            assertEquals(7, entry.getMethod("inherited", loader.loadClass("p.Entry\$Child")).invoke(null, implementation))
            assertEquals(9, loader.loadClass("p.Entry\$PrivateContract").getMethod("invokePrivate")
                .invoke(loader.loadClass("p.Entry\$PrivateImpl").getConstructor().newInstance()))
            assertEquals(47, entry.getMethod("list", java.util.List::class.java).invoke(null, loader.loadClass("p.Entry\$Values").getConstructor().newInstance()))
        }
    }

    @Test
    fun `dependency v1 v2 v1 keeps caller bytes and refreshes cached direct declaration`(@TempDir root: Path) {
        val classes = compile(root.resolve("v1"), mapOf(
            "p/Contract.java" to "package p; public interface Contract { int metric(); }",
            "lib/Switch.java" to "package lib; public interface Switch extends p.Contract {}",
            "p/Implementation.java" to "package p; public class Implementation implements lib.Switch { public int metric() { return 53; } }",
            "p/Entry.java" to "package p; public class Entry { public static int run(lib.Switch value) { return value.metric(); } }",
        ))
        val first = root.resolve("v1.jar")
        jar(classes, first, listOf("lib/Switch.class"))
        val projectV1 = root.resolve("project-v1")
        Files.createDirectories(projectV1.resolve("lib"))
        Files.copy(classes.resolve("lib/Switch.class"), projectV1.resolve("lib/Switch.class"))
        Files.delete(classes.resolve("lib/Switch.class"))
        val changed = compile(root.resolve("v2"), mapOf("lib/Switch.java" to
            "package lib; public interface Switch extends p.Contract { int metric(); }"), listOf(classes))
        val second = root.resolve("v2.jar")
        jar(changed, second, listOf("lib/Switch.class"))
        val active = root.resolve("dependency.jar")
        val cache = ClassIndexCache(root.resolve("cache"), "direct-interface-switch")
        val bytes = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(classes.resolve("p/Entry.class"))).toList()
        fun index() = ClassFileIndexer(sdk, cache).indexWithObservations(listOf(classes), listOf(active))
        fun call(indexed: IndexedClasses) = indexed.graph.externalCalls.single { it.owner == "lib/Switch" && it.name == "metric" }
        fun install(source: Path) { Files.copy(source, active, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        fun execute() = URLClassLoader(arrayOf(classes.toUri().toURL(), active.toUri().toURL()), null).use { loader ->
            assertEquals(53, loader.loadClass("p.Entry").getMethod("run", loader.loadClass("lib.Switch"))
                .invoke(null, loader.loadClass("p.Implementation").getConstructor().newInstance()))
        }
        install(first); execute()
        val cold = index(); val warm = index()
        assertNull(call(cold).declaration)
        assertEquals(cold.graph.externalCalls, warm.graph.externalCalls)
        assertEquals(0, warm.statistics.parsedClasses)
        install(second); execute()
        val replaced = index(); val after = call(replaced)
        assertEquals(CallDeclaration(after.target, CallDeclarationScope.EXTERNAL, listOf("lib/Switch")), after.declaration)
        assertEquals(0, replaced.statistics.parsedClasses)
        assertEquals(1, replaced.statistics.hierarchyParsedJars)
        assertEquals(listOf(NodeId("method:p/Implementation#metric()I")), after.resolvedTargets)
        assertEquals(call(warm), after.copy(declaration = null))
        assertEquals(warm.graph.edges, replaced.graph.edges)
        assertEquals(after, call(warm.withHierarchy(replaced.hierarchy)))
        assertEquals(after, call(index()))
        assertEquals(after, call(ClassFileIndexer(sdk).indexWithObservations(listOf(classes), listOf(active))))
        install(first); execute()
        val restored = index()
        assertEquals(call(cold), call(restored))
        assertEquals(call(cold), call(replaced.withHierarchy(cold.hierarchy)))
        assertNull(call(replaced.withHierarchy(ClassHierarchy.EMPTY)).declaration)
        assertEquals(bytes, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(classes.resolve("p/Entry.class"))).toList())
        assertNull(call(ClassFileIndexer(sdk).indexWithObservations(listOf(classes), listOf(first, second))).declaration)
        assertNotNull(call(ClassFileIndexer(sdk).indexWithObservations(listOf(classes), listOf(second, first))).declaration)
        // 같은 owner의 프로젝트 header는 dependency 순서보다 우선한다.
        val projectFirst = ClassFileIndexer(sdk).indexWithObservations(listOf(classes, changed), listOf(first))
        assertTrue(projectFirst.graph.externalCalls.none { it.owner == "lib/Switch" && it.name == "metric" })
        val firstProjectHeader = ClassFileIndexer(sdk).indexWithObservations(listOf(classes, projectV1, changed), listOf(second))
        assertNull(call(firstProjectHeader).declaration)
        val reordered = ClassFileIndexer(sdk).indexWithObservations(listOf(classes, changed, projectV1), listOf(first))
        assertTrue(reordered.graph.externalCalls.none { it.owner == "lib/Switch" && it.name == "metric" })
    }

    @Test
    fun `explicit target SDK 17 21 17 changes direct List declaration with identical caller bytes`(@TempDir root: Path) {
        val home17 = System.getenv("KARTOGRAPH_JDK17_HOME")
        val home21 = System.getenv("KARTOGRAPH_JDK21_HOME")
        org.junit.jupiter.api.Assumptions.assumeTrue(!home17.isNullOrBlank() && !home21.isNullOrBlank(),
            "explicit JDK 17 and JDK 21 homes are required")
        val jdk17 = Path.of(home17)
        val jdk21 = Path.of(home21)
        val source = Files.writeString(root.resolve("Entry.java"),
            "public class Entry { public static java.util.List<String> run(java.util.List<String> value) { return value.reversed(); } }")
        val classes = root.resolve("classes").createDirectories()
        val process = ProcessBuilder(jdk21.resolve("bin/javac").toString(), "--release", "21", "-g", "-d", classes.toString(), source.toString())
            .redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        assertEquals(0, process.waitFor(), output)
        val bytes = Files.readAllBytes(classes.resolve("Entry.class")).toList()
        val cache = ClassIndexCache(root.resolve("cache"), "direct-interface-sdk")
        fun index(home: Path) = ClassFileIndexer(home, cache).indexWithObservations(listOf(classes))
        fun call(value: IndexedClasses) = value.graph.externalCalls.single { it.owner == "java/util/List" && it.name == "reversed" }
        val old = index(jdk17)
        val current = index(jdk21)
        val restored = index(jdk17)
        assertNull(call(old).declaration)
        assertEquals(CallDeclaration(call(current).target, CallDeclarationScope.EXTERNAL, listOf("java/util/List")), call(current).declaration)
        assertEquals(call(old), call(current).copy(declaration = null))
        assertEquals(call(old), call(restored))
        assertEquals(0, current.statistics.parsedClasses)
        assertEquals(0, restored.statistics.parsedClasses)
        assertEquals(current.graph.externalCalls, old.withHierarchy(current.hierarchy).graph.externalCalls)
        assertEquals(old.graph.externalCalls, current.withHierarchy(old.hierarchy).graph.externalCalls)
        assertEquals(bytes, Files.readAllBytes(classes.resolve("Entry.class")).toList())
    }

    private fun compile(root: Path, sources: Map<String, String>, classpath: List<Path> = emptyList()): Path {
        val files = sources.map { (name, text) -> root.resolve("src/$name").also { it.parent.createDirectories(); Files.writeString(it, text) } }
        val classes = root.resolve("classes").createDirectories()
        val errors = ByteArrayOutputStream()
        val options = listOf("--release", "17", "-g", "-d", classes.toString()) +
            if (classpath.isEmpty()) emptyList() else listOf("-classpath", classpath.joinToString(java.io.File.pathSeparator))
        assertEquals(0, assertNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, errors,
            *(options + files.map(Path::toString)).toTypedArray()), errors.toString())
        return classes
    }
    private fun jar(classes: Path, target: Path, names: List<String>) {
        JarOutputStream(Files.newOutputStream(target)).use { jar -> names.forEach { name ->
            jar.putNextEntry(JarEntry(name)); jar.write(Files.readAllBytes(classes.resolve(name))); jar.closeEntry()
        } }
    }
    private companion object { const val PACKAGE = "dev/kartograph/index/fixture/directinterface" }
}
