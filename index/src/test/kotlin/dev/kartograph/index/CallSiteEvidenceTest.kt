package dev.kartograph.index

import dev.kartograph.core.EdgeKind
import java.nio.file.Path
import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes

class CallSiteEvidenceTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "KARTOGRAPH_KOTLIN_COMPILER_JAR", matches = ".+")
    fun `cached Kotlin inline relay keeps B caller evidence without synthetic A caller`(@TempDir root: Path) {
        val source = root.resolve("src/probe").createDirectories()
        val a = source.resolve("A.kt").apply { writeText("package probe\n\nobject Target { @JvmStatic fun hit() {} }\ninline fun relay() { Target.hit() }\n") }
        val b = source.resolve("B.kt").apply {
            writeText("package probe\n\nfun caller() { relay() }\n\nfun direct() { Target.hit() }\n")
        }
        val classes = root.resolve("classes").createDirectories()
        val compilerJar = requireNotNull(System.getenv("KARTOGRAPH_KOTLIN_COMPILER_JAR"))
        val compilerLib = requireNotNull(System.getenv("KARTOGRAPH_KOTLIN_COMPILER_LIB"))
        val javaHome = requireNotNull(System.getenv("KARTOGRAPH_JAVA_HOME"))
        require(Files.isRegularFile(Path.of(compilerJar)))
        val process = ProcessBuilder(javaHome + "/bin/java", "-cp", compilerLib + "/*", "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-jvm-target", "21", "-d", classes.toString(), a.toString(), b.toString())
            .redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        assertEquals(0, process.waitFor(), output)

        val graph = ClassFileIndexer().index(listOf(classes))
        val caller = JvmNodeId.methodId("probe/BKt", "caller", "()V")
        val target = JvmNodeId.methodId("probe/Target", "hit", "()V")
        val callerEdge = graph.edges.single { it.source == caller && it.target == target && it.kind == EdgeKind.CALL }
        assertTrue(callerEdge.callSiteLines.isEmpty())
        val direct = JvmNodeId.methodId("probe/BKt", "direct", "()V")
        assertTrue(graph.edges.single { it.source == direct && it.target == target && it.kind == EdgeKind.CALL }.callSiteLines.isNotEmpty(), graph.edges.toString())
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KARTOGRAPH_KOTLIN_COMPILER_JAR", matches = ".+")
    fun `same-file Kotlin inline relay omits mapped body line but keeps direct call evidence`(@TempDir root: Path) {
        val source = root.resolve("src/probe").createDirectories().resolve("A.kt").apply {
            writeText("package probe\n\nobject Target { @JvmStatic fun hit() {} }\ninline fun relay() { Target.hit() }\nfun caller() { relay() }\nfun direct() { Target.hit() }\nfun external() { System.currentTimeMillis() }\n")
        }
        val classes = root.resolve("classes").createDirectories()
        val compilerJar = requireNotNull(System.getenv("KARTOGRAPH_KOTLIN_COMPILER_JAR"))
        val compilerLib = requireNotNull(System.getenv("KARTOGRAPH_KOTLIN_COMPILER_LIB"))
        val javaHome = requireNotNull(System.getenv("KARTOGRAPH_JAVA_HOME"))
        require(Files.isRegularFile(Path.of(compilerJar)))
        val process = ProcessBuilder(javaHome + "/bin/java", "-cp", compilerLib + "/*", "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-jvm-target", "21", "-d", classes.toString(), source.toString())
            .redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        assertEquals(0, process.waitFor(), output)

        val graph = ClassFileIndexer().index(listOf(classes))
        val caller = JvmNodeId.methodId("probe/AKt", "caller", "()V")
        val target = JvmNodeId.methodId("probe/Target", "hit", "()V")
        assertTrue(graph.edges.single { it.source == caller && it.target == target && it.kind == EdgeKind.CALL }.callSiteLines.isEmpty(), graph.edges.toString())
        val direct = JvmNodeId.methodId("probe/AKt", "direct", "()V")
        assertTrue(graph.edges.single { it.source == direct && it.target == target && it.kind == EdgeKind.CALL }.callSiteLines.isNotEmpty(), graph.edges.toString())
        assertEquals("A.kt", graph.node(caller)?.location?.path)
        assertEquals(5, graph.node(caller)?.location?.line)
        val external = JvmNodeId.methodId("probe/AKt", "external", "()V")
        assertEquals("A.kt", graph.node(external)?.location?.path)
        assertEquals(7, graph.node(external)?.location?.line)
        assertEquals(7, graph.externalCalls.single { it.caller == external }.location?.line)
    }

    @Test
    fun `foreign SMAP lines omit reference evidence without relocating existing locations`(@TempDir root: Path) {
        val classes = root.resolve("classes").createDirectories()
        classes.resolve("demo/Facade.class").also { it.parent.createDirectories() }.writeBytes(smapCallerClass())
        classes.resolve("demo/Target.class").writeBytes(targetClass())

        val graph = ClassFileIndexer().indexWithObservations(listOf(classes)).graph
        val caller = graph.node(JvmNodeId.methodId("demo/Facade", "call", "()V"))!!

        assertEquals("Facade.kt", caller.location?.path)
        assertEquals(10, caller.location?.line)
        assertTrue(graph.edges.single { it.kind == EdgeKind.CALL }.callSiteLines.isEmpty())
    }

    @Test
    fun `own source identity SMAP preserves reference line and raw caller location`(@TempDir root: Path) {
        val classes = root.resolve("classes").createDirectories()
        classes.resolve("demo/Facade.class").also { it.parent.createDirectories() }
            .writeBytes(smapCallerClass(ownSource = true))
        classes.resolve("demo/Target.class").writeBytes(targetClass())
        val graph = ClassFileIndexer().index(listOf(classes))
        assertEquals(listOf(10), graph.edges.single { it.kind == EdgeKind.CALL }.callSiteLines)
        assertEquals(10, graph.node(JvmNodeId.methodId("demo/Facade", "call", "()V"))?.location?.line)
    }

    @Test
    fun `same source nonidentity SMAP omits evidence without a compiler environment`(@TempDir root: Path) {
        val classes = root.resolve("classes").createDirectories()
        classes.resolve("demo/Facade.class").also { it.parent.createDirectories() }
            .writeBytes(smapCallerClass(ownSource = true, mappedInputLine = 5))
        classes.resolve("demo/Target.class").writeBytes(targetClass())
        val graph = ClassFileIndexer().index(listOf(classes))
        assertTrue(graph.edges.single { it.kind == EdgeKind.CALL }.callSiteLines.isEmpty())
        assertEquals(10, graph.node(JvmNodeId.methodId("demo/Facade", "call", "()V"))?.location?.line)
    }

    @Test
    fun `javac call edges retain distinct direct call-site lines and repeated calls`(@TempDir root: Path) {
        val source = root.resolve("src/demo").createDirectories()
        val targets = source.resolve("Targets.java").apply {
            writeText(
                """
                package demo;
                public final class Targets {
                    public static void direct() {}
                    public static void overloaded(String value) {}
                    public static void overloaded(int value) {}
                }
                """.trimIndent() + "\n",
            )
        }
        val caller = source.resolve("Caller.java").apply {
            writeText(
                """
                package demo;
                public final class Caller {
                    public static void calls() {
                        Targets.direct();
                        Targets.overloaded("text");
                        Targets.overloaded(42);
                    }
                    public static void repeated() {
                        Targets.direct();
                        Targets.direct();
                    }
                }
                """.trimIndent() + "\n",
            )
        }
        val classes = root.resolve("classes").createDirectories()
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, null, null, "-g", "-d", classes.toString(), targets.toString(), caller.toString()))

        val graph = ClassFileIndexer().index(listOf(classes))
        fun lines(target: String, descriptor: String, callerName: String) = graph.edges.single {
            it.source == JvmNodeId.methodId("demo/Caller", callerName, "()V") &&
                it.target == JvmNodeId.methodId("demo/Targets", target, descriptor) && it.kind == EdgeKind.CALL
        }.callSiteLines

        assertEquals(listOf(4), lines("direct", "()V", "calls"))
        assertEquals(listOf(5), lines("overloaded", "(Ljava/lang/String;)V", "calls"))
        assertEquals(listOf(6), lines("overloaded", "(I)V", "calls"))
        assertEquals(listOf(9, 10), lines("direct", "()V", "repeated"))

        val cache = ClassIndexCache(root.resolve("cache"))
        ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        val warm = ClassFileIndexer(cache).indexWithObservations(listOf(classes))
        assertEquals(graph.edges, warm.graph.edges)
        assertTrue(warm.statistics.cacheHits > 0)
    }

    private fun smapCallerClass(ownSource: Boolean = false, mappedInputLine: Int? = null): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "demo/Facade", null, "java/lang/Object", null)
        val fileId = if (ownSource) 1 else 2
        val inputLine = mappedInputLine ?: if (ownSource) 10 else 1
        visitSource("Facade.kt", "SMAP\nFacade.kt\nKotlin\n*F\n+ 1 Facade.kt\nFacade.kt\n+ 2 B.kt\nB.kt\n*L\n$inputLine#$fileId:10\n*E\n")
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "call", "()V", null, null).apply {
            val start = Label()
            visitCode()
            visitLabel(start)
            visitLineNumber(10, start)
            visitMethodInsn(Opcodes.INVOKESTATIC, "demo/Target", "run", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        visitEnd()
    }.toByteArray()

    private fun targetClass(): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "demo/Target", null, "java/lang/Object", null)
        visitSource("Target.kt", null)
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()V", null, null).apply {
            visitCode()
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        visitEnd()
    }.toByteArray()
}
