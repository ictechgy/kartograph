package dev.kartograph.index

import dev.kartograph.core.EdgeKind
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.NodeKind
import dev.kartograph.core.Visibility
import dev.kartograph.index.fixture.Caller
import dev.kartograph.index.fixture.JavaFixture
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes

class ClassFileIndexerTest {
    @Test
    fun `indexes structural facts from real Kotlin class files`() {
        val graph = ClassFileIndexer().index(listOf(testClassesRoot))
        val callerClass = JvmNodeId.classId("dev/kartograph/index/fixture/Caller")
        val baseClass = JvmNodeId.classId("dev/kartograph/index/fixture/Base")
        val markerClass = JvmNodeId.classId("dev/kartograph/index/fixture/Marker")
        val callerMethod = JvmNodeId.methodId(
            "dev/kartograph/index/fixture/Caller",
            "callTwice",
            "()Ljava/lang/String;",
        )
        val dependencyMethod = JvmNodeId.methodId(
            "dev/kartograph/index/fixture/Dependency",
            "touch",
            "()V",
        )
        val dependencyClass = JvmNodeId.classId("dev/kartograph/index/fixture/Dependency")
        val dependencyField = JvmNodeId.fieldId(
            "dev/kartograph/index/fixture/Caller",
            "dependency",
            "Ldev/kartograph/index/fixture/Dependency;",
        )

        assertEquals(NodeKind.CLASS, graph.node(callerClass)?.kind)
        assertEquals(NodeKind.INTERFACE, graph.node(baseClass)?.kind)
        assertEquals(setOf(JvmModifier.FINAL), graph.node(callerClass)?.jvmModifiers)
        assertTrue(JvmModifier.ABSTRACT in graph.node(baseClass)?.jvmModifiers.orEmpty())
        assertEquals(setOf("dev/kartograph/index/fixture/Base"), graph.node(callerClass)?.supertypes)
        assertTrue(graph.edges.any {
            it.source == callerClass && it.target == baseClass && it.kind == EdgeKind.INHERITANCE
        })
        assertTrue(graph.edges.any {
            it.source == callerClass && it.target == markerClass && it.kind == EdgeKind.ANNOTATION
        })
        assertTrue(graph.edges.any {
            it.source == callerClass && it.target == callerMethod && it.kind == EdgeKind.MEMBER
        })
        assertTrue(graph.edges.any {
            it.source == callerMethod && it.target == dependencyField && it.kind == EdgeKind.FIELD_ACCESS
        })
        val call = graph.edges.single {
            it.source == callerMethod && it.target == dependencyMethod && it.kind == EdgeKind.CALL
        }
        assertEquals(2, call.weight)
        assertTrue(graph.edges.any {
            it.source == callerMethod && it.target == dependencyClass && it.kind == EdgeKind.REFERENCE
        })
    }

    @Test
    fun `records source file and first executable line`() {
        val graph = ClassFileIndexer().index(listOf(testClassesRoot))
        val callerMethod = JvmNodeId.methodId(
            "dev/kartograph/index/fixture/Caller",
            "callTwice",
            "()Ljava/lang/String;",
        )

        val location = assertNotNull(graph.node(callerMethod)?.location)
        assertEquals("ProbeFixtures.kt", location.path)
        assertTrue(assertNotNull(location.line) > 0)
    }

    @Test
    fun `members reference their owning class for reverse reachability`() {
        val graph = ClassFileIndexer().index(listOf(testClassesRoot))
        val owner = JvmNodeId.classId("dev/kartograph/index/fixture/Caller")
        val method = JvmNodeId.methodId(
            "dev/kartograph/index/fixture/Caller",
            "callTwice",
            "()Ljava/lang/String;",
        )
        val field = JvmNodeId.fieldId(
            "dev/kartograph/index/fixture/Caller",
            "dependency",
            "Ldev/kartograph/index/fixture/Dependency;",
        )

        assertTrue(graph.edges.any { edge ->
            edge.source == method && edge.target == owner && edge.kind == EdgeKind.REFERENCE
        })
        assertTrue(graph.edges.any { edge ->
            edge.source == field && edge.target == owner && edge.kind == EdgeKind.REFERENCE
        })
    }

    @Test
    fun `treats compiler line zero as an unknown source line`(@TempDir directory: Path) {
        directory.resolve("SyntheticCoroutine.class").writeBytes(classWithLineZero())

        val graph = ClassFileIndexer().index(listOf(directory))
        val method = graph.node(JvmNodeId.methodId("dev/fixture/SyntheticCoroutine", "resume", "()V"))

        assertEquals("SyntheticCoroutine.kt", method?.location?.path)
        assertEquals(null, method?.location?.line)
    }

    @Test
    fun `method descriptors retain project types without executable instructions`() {
        val graph = ClassFileIndexer().index(listOf(testClassesRoot))
        val consumer = JvmNodeId.methodId(
            "dev/kartograph/index/fixture/SignatureConsumer",
            "consume",
            "(Ldev/kartograph/index/fixture/SignatureOnly;)Ldev/kartograph/index/fixture/SignatureOnly;",
        )
        val signatureType = JvmNodeId.classId("dev/kartograph/index/fixture/SignatureOnly")

        assertTrue(graph.edges.any { edge ->
            edge.source == consumer && edge.target == signatureType && edge.kind == EdgeKind.REFERENCE
        })
    }

    @Test
    fun `uses InnerClasses access flags for nested class visibility`() {
        val graph = ClassFileIndexer().index(listOf(testClassesRoot))
        val privateNested = JvmNodeId.classId("dev/kartograph/index/fixture/NestedOwner\$PrivateNested")

        assertEquals(Visibility.PRIVATE, graph.node(privateNested)?.jvmVisibility)
    }

    @Test
    fun `records method handles referenced by invokedynamic call sites`() {
        val javaClassesRoot = Path.of(requireNotNull(JavaFixture::class.java.protectionDomain.codeSource).location.toURI())
        val graph = ClassFileIndexer().index(listOf(javaClassesRoot))
        val owner = "dev/kartograph/index/fixture/JavaFixture"
        val factory = JvmNodeId.methodId(owner, "methodReference", "()Ljava/lang/Runnable;")
        val target = JvmNodeId.methodId(owner, "hiddenStatic", "()V")

        assertTrue(graph.edges.any { edge ->
            edge.source == factory && edge.target == target && edge.kind == EdgeKind.CALL
        })
    }

    @Test
    fun `records constructed and class literal reference types`() {
        val javaClassesRoot = Path.of(requireNotNull(JavaFixture::class.java.protectionDomain.codeSource).location.toURI())
        val graph = ClassFileIndexer().index(listOf(javaClassesRoot))
        val owner = "dev/kartograph/index/fixture/JavaFixture"
        val expectedTargets = mapOf(
            "constructedType" to "dev/kartograph/index/fixture/ConstructedOnly",
            "classLiteral" to "dev/kartograph/index/fixture/LiteralOnly",
            "arrayClassLiteral" to "dev/kartograph/index/fixture/ArrayLiteralOnly",
        )

        expectedTargets.forEach { (methodName, targetName) ->
            val source = graph.nodeIds.single { nodeId ->
                nodeId.value.startsWith("method:$owner#$methodName(")
            }
            assertTrue(graph.edges.any { edge ->
                edge.source == source && edge.target == JvmNodeId.classId(targetName) && edge.kind == EdgeKind.REFERENCE
            })
        }
    }

    @Test
    fun `connects interface methods to project implementations`() {
        val javaClassesRoot = Path.of(requireNotNull(JavaFixture::class.java.protectionDomain.codeSource).location.toURI())
        val graph = ClassFileIndexer().index(listOf(javaClassesRoot))
        val contractMethod = JvmNodeId.methodId(
            "dev/kartograph/index/fixture/DispatchContract",
            "invoke",
            "()Ljava/lang/Object;",
        )
        val implementationMethod = JvmNodeId.methodId(
            "dev/kartograph/index/fixture/DispatchImplementation",
            "invoke",
            "()Ljava/lang/Object;",
        )

        assertTrue(graph.edges.any { edge ->
            edge.source == contractMethod && edge.target == implementationMethod && edge.kind == EdgeKind.OVERRIDE
        })
    }

    @Test
    fun `framework callback edges exclude private and static helpers`(@TempDir directory: Path) {
        directory.resolve("Callback.class").writeBytes(frameworkCallbackClass())

        val graph = ClassFileIndexer().index(listOf(directory))
        val owner = JvmNodeId.classId("dev/fixture/Callback")
        val targets = graph.outgoingEdgesFrom(owner)
            .filter { edge -> edge.kind == EdgeKind.REFERENCE }
            .map { edge -> edge.target }

        assertTrue(JvmNodeId.methodId("dev/fixture/Callback", "<init>", "()V") in targets)
        assertTrue(JvmNodeId.methodId("dev/fixture/Callback", "onPageFinished", "()V") in targets)
        assertFalse(JvmNodeId.methodId("dev/fixture/Callback", "helper", "()V") in targets)
        assertFalse(JvmNodeId.methodId("dev/fixture/Callback", "utility", "()V") in targets)
    }

    @Test
    fun `ASM visitor subclasses expose externally invoked callback members`(@TempDir directory: Path) {
        directory.resolve("AsmCallback.class").writeBytes(
            frameworkCallbackClass("dev/fixture/AsmCallback", "org/objectweb/asm/ClassVisitor", "visit"),
        )

        val graph = ClassFileIndexer().index(listOf(directory))
        val owner = JvmNodeId.classId("dev/fixture/AsmCallback")

        assertTrue(
            graph.outgoingEdgesFrom(owner).any { edge ->
                edge.kind == EdgeKind.REFERENCE &&
                    edge.target == JvmNodeId.methodId("dev/fixture/AsmCallback", "visit", "()V")
            },
        )
    }

    @Test
    fun `duplicate class roots do not double edge weights`() {
        val graph = ClassFileIndexer().index(listOf(testClassesRoot, testClassesRoot))
        val callerMethod = JvmNodeId.methodId(
            "dev/kartograph/index/fixture/Caller",
            "callTwice",
            "()Ljava/lang/String;",
        )
        val dependencyMethod = JvmNodeId.methodId(
            "dev/kartograph/index/fixture/Dependency",
            "touch",
            "()V",
        )

        val call = graph.edges.single {
            it.source == callerMethod && it.target == dependencyMethod && it.kind == EdgeKind.CALL
        }
        assertEquals(2, call.weight)
    }

    @Test
    fun `duplicate class names use facts from the first root`(@TempDir directory: Path) {
        val firstRoot = directory.resolve("first").createDirectories()
        val secondRoot = directory.resolve("second").createDirectories()
        firstRoot.resolve("Duplicate.class").writeBytes(duplicateClass("First.kt", Opcodes.ACC_FINAL))
        secondRoot.resolve("Duplicate.class").writeBytes(duplicateClass("Second.kt", Opcodes.ACC_ABSTRACT))

        val graph = ClassFileIndexer().index(listOf(firstRoot, secondRoot))
        val duplicate = graph.node(JvmNodeId.classId("dev/fixture/Duplicate"))

        assertEquals("First.kt", duplicate?.location?.path)
        assertEquals(setOf(JvmModifier.FINAL), duplicate?.jvmModifiers)
    }

    @Test
    fun `marks every class from an Android R jar as synthesized`(@TempDir directory: Path) {
        val resourceJar = directory.resolve("R.jar")
        JarOutputStream(Files.newOutputStream(resourceJar)).use { output ->
            output.putNextEntry(JarEntry("dev/fixture/R.class"))
            output.write(duplicateClass("R.java", Opcodes.ACC_FINAL, "dev/fixture/R"))
            output.closeEntry()
        }

        val graph = ClassFileIndexer().index(listOf(resourceJar))

        assertTrue(graph.node(JvmNodeId.classId("dev/fixture/R"))?.synthesized == true)
    }

    @Test
    fun `marks Android generated class names as synthesized outside R jars`(@TempDir directory: Path) {
        val generatedNames = listOf("BuildConfig", "BR", "R", "R${'$'}string", "Manifest", "Manifest${'$'}permission")
        generatedNames.forEach { name ->
            directory.resolve("$name.class").writeBytes(
                duplicateClass("$name.java", Opcodes.ACC_FINAL, "dev/fixture/$name"),
            )
        }

        val graph = ClassFileIndexer().index(listOf(directory))

        assertTrue(generatedNames.all { name ->
            graph.node(JvmNodeId.classId("dev/fixture/$name"))?.synthesized == true
        })
    }

    @Test
    fun `does not classify user classes as generated from name suffix alone`(@TempDir directory: Path) {
        directory.resolve("Repository_Impl.class").writeBytes(
            duplicateClass("Repository_Impl.kt", Opcodes.ACC_FINAL, "dev/fixture/Repository_Impl"),
        )
        directory.resolve("ManualJsonAdapter.class").writeBytes(
            duplicateClass("ManualJsonAdapter.kt", Opcodes.ACC_FINAL, "dev/fixture/ManualJsonAdapter"),
        )

        val graph = ClassFileIndexer().index(listOf(directory))

        assertFalse(graph.nodes.getValue(JvmNodeId.classId("dev/fixture/Repository_Impl")).synthesized)
        assertFalse(graph.nodes.getValue(JvmNodeId.classId("dev/fixture/ManualJsonAdapter")).synthesized)
    }

    @Test
    fun `marks an exact Room sibling of an annotated database as synthesized`(@TempDir directory: Path) {
        directory.resolve("AppDatabase.class").writeBytes(
            annotatedClass(
                "AppDatabase.kt",
                "dev/fixture/AppDatabase",
                "Landroidx/room/Database;",
            ),
        )
        directory.resolve("AppDatabase_Impl.class").writeBytes(
            duplicateClass("AppDatabase_Impl.kt", Opcodes.ACC_FINAL, "dev/fixture/AppDatabase_Impl"),
        )

        val graph = ClassFileIndexer().index(listOf(directory))

        assertTrue(graph.nodes.getValue(JvmNodeId.classId("dev/fixture/AppDatabase_Impl")).synthesized)
    }

    @Test
    fun `missing class root is rejected without echoing its absolute path`(@TempDir directory: Path) {
        val missingRoot = directory.resolve("private-user-segment").resolve("missing")

        val error = assertFailsWith<ClassIndexingException> {
            ClassFileIndexer().index(listOf(missingRoot))
        }

        assertTrue(error.message.orEmpty().contains("class root does not exist"))
        assertFalse(error.message.orEmpty().contains(directory.toString()))
    }

    @Test
    fun `invalid class file fails instead of returning a partial graph`(@TempDir directory: Path) {
        directory.resolve("Broken.class").writeText("not bytecode")

        val error = assertFailsWith<ClassIndexingException> {
            ClassFileIndexer().index(listOf(directory))
        }

        assertTrue(error.message.orEmpty().contains("invalid class file"))
        assertFalse(error.message.orEmpty().contains(directory.toString()))
    }

    @Test
    fun `truncated class file fails through the sanitized indexing exception`(@TempDir directory: Path) {
        directory.resolve("Truncated.class").writeBytes(truncatedClass())

        val error = assertFailsWith<ClassIndexingException> {
            ClassFileIndexer().index(listOf(directory))
        }

        assertEquals("invalid class file", error.message)
        assertFalse(error.message.orEmpty().contains(directory.toString()))
    }

    @Test
    fun `truncated class inside a jar fails through the sanitized indexing exception`(@TempDir directory: Path) {
        val jar = directory.resolve("broken.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry("dev/fixture/Truncated.class"))
            output.write(truncatedClass())
            output.closeEntry()
        }

        val error = assertFailsWith<ClassIndexingException> {
            ClassFileIndexer().index(listOf(jar))
        }

        assertEquals("invalid class file in class JAR", error.message)
        assertFalse(error.message.orEmpty().contains(directory.toString()))
    }

    @Test
    fun `indexes declared caught and multidimensional array reference types`(@TempDir directory: Path) {
        directory.resolve("InstructionReferences.class").writeBytes(classWithInstructionReferences())
        listOf("DeclaredException", "CaughtOnly", "ArrayOnly").forEach { name ->
            directory.resolve("$name.class").writeBytes(
                duplicateClass("$name.java", Opcodes.ACC_FINAL, "dev/fixture/$name"),
            )
        }

        val graph = ClassFileIndexer().index(listOf(directory))
        val source = JvmNodeId.methodId("dev/fixture/InstructionReferences", "inspect", "()V")

        val expectedTargets = setOf(
            JvmNodeId.classId("dev/fixture/DeclaredException"),
            JvmNodeId.classId("dev/fixture/CaughtOnly"),
            JvmNodeId.classId("dev/fixture/ArrayOnly"),
            JvmNodeId.classId("dev/fixture/InstructionReferences"),
        )
        val actualTargets = graph.edges.filter { edge -> edge.source == source && edge.kind == EdgeKind.REFERENCE }
            .mapTo(mutableSetOf()) { edge -> edge.target }

        assertEquals(expectedTargets, actualTargets)
    }

    private val testClassesRoot: Path
        get() = Path.of(requireNotNull(Caller::class.java.protectionDomain.codeSource).location.toURI())

    private fun duplicateClass(
        sourceFile: String,
        modifier: Int,
        internalName: String = "dev/fixture/Duplicate",
    ): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC or modifier, internalName, null, "java/lang/Object", null)
        visitSource(sourceFile, null)
        visitEnd()
    }.toByteArray()

    private fun annotatedClass(sourceFile: String, internalName: String, annotationDescriptor: String): ByteArray =
        ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
            visitSource(sourceFile, null)
            visitAnnotation(annotationDescriptor, false).visitEnd()
            visitEnd()
        }.toByteArray()

    private fun truncatedClass(): ByteArray = byteArrayOf(
        0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
        0x00, 0x00, 0x00, 0x3D, 0x00,
    )

    private fun classWithInstructionReferences(): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "dev/fixture/InstructionReferences", null, "java/lang/Object", null)
        visitSource("InstructionReferences.java", null)
        visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "inspect",
            "()V",
            null,
            arrayOf("dev/fixture/DeclaredException"),
        ).apply {
            val start = Label()
            val end = Label()
            val handler = Label()
            val done = Label()
            visitCode()
            visitTryCatchBlock(start, end, handler, "dev/fixture/CaughtOnly")
            visitLabel(start)
            visitInsn(Opcodes.NOP)
            visitLabel(end)
            visitJumpInsn(Opcodes.GOTO, done)
            visitLabel(handler)
            visitInsn(Opcodes.POP)
            visitLabel(done)
            visitInsn(Opcodes.ICONST_1)
            visitInsn(Opcodes.ICONST_1)
            visitMultiANewArrayInsn("[[Ldev/fixture/ArrayOnly;", 2)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 0)
            visitEnd()
        }
        visitEnd()
    }.toByteArray()

    private fun classWithLineZero(): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "dev/fixture/SyntheticCoroutine", null, "java/lang/Object", null)
        visitSource("SyntheticCoroutine.kt", null)
        visitMethod(Opcodes.ACC_PUBLIC, "resume", "()V", null, null).apply {
            val start = Label()
            visitCode()
            visitLabel(start)
            visitLineNumber(0, start)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 1)
            visitEnd()
        }
        visitEnd()
    }.toByteArray()

    private fun frameworkCallbackClass(
        internalName: String = "dev/fixture/Callback",
        superName: String = "android/webkit/WebViewClient",
        callbackName: String = "onPageFinished",
    ): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, superName, null)
        visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).visitEnd()
        visitMethod(Opcodes.ACC_PUBLIC, callbackName, "()V", null, null).visitEnd()
        visitMethod(Opcodes.ACC_PRIVATE, "helper", "()V", null, null).visitEnd()
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "utility", "()V", null, null).visitEnd()
        visitEnd()
    }.toByteArray()
}
