package dev.kartograph.index

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class TargetJdkHeadersTest {
    @Test
    fun `explicit headers are lazy and never borrow missing host classes`(@TempDir root: Path) {
        val sdk = sdk(root, "sdk", "java/util/List" to type("java/util/List", "java/util/Collection"),
            "java/util/Collection" to type("java/util/Collection"), "java/lang/Broken" to byteArrayOf(1),
            "javax/net/Existing" to type("javax/net/Existing"))
        val indexer = ClassHierarchyIndexer(targetJdkHome = sdk)
        assertEquals(setOf("java/util/Collection"), indexer.index(emptyList(), listOf("java/util/List")).directSupertypesOf("java/util/List"))
        assertNotNull(indexer.targetJdkFingerprint)
        assertFailsWith<ClassHierarchyIndexingException> { indexer.index(emptyList(), listOf("java/lang/String")) }
        assertNull(indexer.targetJdkFingerprint)
        assertFailsWith<ClassHierarchyIndexingException> { indexer.index(emptyList(), listOf("java/lang/Broken")) }
        assertFailsWith<ClassHierarchyIndexingException> { indexer.index(emptyList(), listOf("javax/net/SocketFactory")) }
    }

    @Test
    fun `class indexer loads platform invocation owners without dependency classpath`(@TempDir root: Path) {
        val sdk = sdk(root, "sdk", "java/util/List" to type("java/util/List", "java/util/Collection"),
            "java/util/Collection" to type("java/util/Collection"))
        val classes = root.resolve("classes").createDirectories()
        classes.resolve("Calls.class").writeBytes(ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Calls", null, "java/lang/Object", null)
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "size", "(Ljava/util/List;)I", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true)
                visitInsn(Opcodes.IRETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
            visitEnd()
        }.toByteArray())
        assertNull(ClassFileIndexer().indexWithObservations(listOf(classes)).hierarchy.directSupertypesOf("java/util/List"))
        val indexer = ClassFileIndexer(targetJdkHome = sdk)
        val indexed = indexer.indexWithObservations(listOf(classes))
        assertEquals(setOf("java/util/Collection"), indexed.hierarchy.directSupertypesOf("java/util/List"))
        assertNotNull(indexer.targetJdkFingerprint)
        assertTrue(indexed.statistics.hierarchyNanos > 0)
        assertEquals(setOf("class:Calls", "method:Calls#size(Ljava/util/List;)I"), indexed.graph.nodes.keys.map { it.value }.toSet())
    }

    @Test
    fun `identity is portable and changes with release module names and all archive bytes`(@TempDir root: Path) {
        val first = sdk(root, "first", "java/lang/Value" to type("java/lang/Value"))
        val second = sdk(root, "second", "java/lang/Value" to type("java/lang/Value"))
        fun id(home: Path) = TargetJdkHeaders.open(home).fingerprint
        val initial = id(first)
        assertEquals(initial, id(second))
        second.resolve("release").writeText("JAVA_VERSION=\"21\"\n")
        assertNotEquals(initial, id(second))
        second.resolve("release").writeText("JAVA_VERSION=\"17\"\n")
        module(second, "extra.jmod", "classes/module-info.class" to byteArrayOf(1))
        assertNotEquals(initial, id(second))
        Files.move(second.resolve("jmods/extra.jmod"), second.resolve("jmods/renamed.jmod"))
        val renamed = id(second)
        module(second, "renamed.jmod", "classes/module-info.class" to byteArrayOf(2))
        assertNotEquals(renamed, id(second))
    }

    @Test
    fun `same indexer and shared cache observe same size same mtime SDK replacement`(@TempDir root: Path) {
        val sdk = sdk(root, "sdk", "java/util/List" to type("java/util/List", "java/util/Old"),
            "java/util/Old" to type("java/util/Old"), "java/util/New" to type("java/util/New"))
        val indexer = ClassHierarchyIndexer(sdk, ClassIndexCache(root.resolve("cache"), "test-engine"))
        val first = indexer.index(emptyList(), listOf("java/util/List"))
        val fingerprint = indexer.targetJdkFingerprint
        val file = sdk.resolve("jmods/java.base.jmod")
        val timestamp = Files.getLastModifiedTime(file)
        val size = Files.size(file)
        module(sdk, "java.base.jmod", "classes/java/util/List.class" to type("java/util/List", "java/util/New"),
            "classes/java/util/Old.class" to type("java/util/Old"), "classes/java/util/New.class" to type("java/util/New"))
        Files.setLastModifiedTime(file, timestamp)
        assertEquals(size, Files.size(file))
        assertEquals(setOf("java/util/Old"), first.directSupertypesOf("java/util/List"))
        assertEquals(setOf("java/util/New"), indexer.index(emptyList(), listOf("java/util/List")).directSupertypesOf("java/util/List"))
        assertNotEquals(fingerprint, indexer.targetJdkFingerprint)
        val changed = indexer.targetJdkFingerprint
        assertEquals(setOf("java/util/New"), indexer.index(emptyList(), listOf("java/util/List")).directSupertypesOf("java/util/List"))
        assertEquals(changed, indexer.targetJdkFingerprint)
    }

    @Test
    fun `mutation during one capture fails instead of returning mixed SDK facts`(@TempDir root: Path) {
        val sdk = sdk(root, "sdk", "java/lang/Value" to type("java/lang/Value"))
        val source = TargetJdkHeaders.open(sdk)
        assertTrue(source.read("java/lang/Value").isNotEmpty())
        sdk.resolve("release").writeText("JAVA_VERSION=\"21\"\n")
        assertFailsWith<ClassHierarchyIndexingException> { source.verifyUnchanged() }
    }

    @Test
    fun `missing directories modules release files and nonregular modules fail`(@TempDir root: Path) {
        assertFailsWith<ClassHierarchyIndexingException> { TargetJdkHeaders.open(root) }
        val sdk = sdk(root, "sdk", "java/lang/Object" to type("java/lang/Object"))
        Files.delete(sdk.resolve("jmods/java.base.jmod"))
        assertFailsWith<ClassHierarchyIndexingException> { TargetJdkHeaders.open(sdk) }
        sdk.resolve("jmods/java.base.jmod").createDirectories()
        assertFailsWith<ClassHierarchyIndexingException> { TargetJdkHeaders.open(sdk) }
        Files.delete(sdk.resolve("jmods/java.base.jmod"))
        module(sdk, "java.base.jmod", "classes/java/lang/Object.class" to type("java/lang/Object"))
        Files.delete(sdk.resolve("release"))
        assertFailsWith<ClassHierarchyIndexingException> { TargetJdkHeaders.open(sdk) }
        sdk.resolve("release").writeText("JAVA_VERSION=unknown")
        assertFailsWith<ClassHierarchyIndexingException> { TargetJdkHeaders.open(sdk) }
    }

    @Test
    fun `symbolic module and jmods directory inputs are rejected`(@TempDir root: Path) {
        val sdk = sdk(root, "sdk", "java/lang/Object" to type("java/lang/Object"))
        val module = sdk.resolve("jmods/java.base.jmod")
        val moved = root.resolve("actual.jmod")
        Files.move(module, moved)
        Files.createSymbolicLink(module, moved)
        assertFailsWith<ClassHierarchyIndexingException> { TargetJdkHeaders.open(sdk) }
        Files.delete(module)
        Files.move(moved, module)
        Files.move(sdk.resolve("jmods"), root.resolve("modules"))
        Files.createSymbolicLink(sdk.resolve("jmods"), root.resolve("modules"))
        assertFailsWith<ClassHierarchyIndexingException> { TargetJdkHeaders.open(sdk) }
    }

    @Test
    fun `duplicate classes across modules and inside one archive fail`(@TempDir root: Path) {
        val sdk = sdk(root, "sdk", "java/lang/A" to type("java/lang/A"))
        module(sdk, "other.jmod", "classes/java/lang/A.class" to type("java/lang/A"))
        assertFailsWith<ClassHierarchyIndexingException> { TargetJdkHeaders.open(sdk) }
        Files.delete(sdk.resolve("jmods/other.jmod"))
        module(sdk, "java.base.jmod", "classes/java/lang/A.class" to type("java/lang/A"),
            "classes/java/lang/B.class" to type("java/lang/A"))
        val file = sdk.resolve("jmods/java.base.jmod")
        val bytes = Files.readAllBytes(file)
        val old = "classes/java/lang/B.class".toByteArray()
        for (i in 0..bytes.size - old.size) if (old.indices.all { bytes[i + it] == old[it] }) bytes[i + "classes/java/lang/".length] = 'A'.code.toByte()
        file.writeBytes(bytes)
        assertFailsWith<ClassHierarchyIndexingException> { TargetJdkHeaders.open(sdk) }
    }

    @Test
    fun `module descriptors may repeat across modules but not within one archive`(@TempDir root: Path) {
        val sdk = sdk(root, "sdk", "java/lang/A" to type("java/lang/A"), "module-info" to byteArrayOf(1))
        module(sdk, "other.jmod", "classes/module-info.class" to byteArrayOf(2))
        assertTrue(TargetJdkHeaders.open(sdk).contains("java/lang/A"))
    }

    @Test
    fun `unsafe archive names including resource paths are rejected without exposing them`(@TempDir root: Path) {
        val sdk = sdk(root, "sdk", "java/lang/A" to type("java/lang/A"))
        listOf("/absolute", "../secret", "classes/../A.class", "classes//A.class", "classes/./A.class", "C:/secret", "classes\\A.class", "classes/A\u0000.class", "classes/.class", "classes/java.lang.A.class").forEach { name ->
            module(sdk, "java.base.jmod", name to type("java/lang/A"))
            val error = assertFailsWith<ClassHierarchyIndexingException>(name) { TargetJdkHeaders.open(sdk) }
            assertFalse(error.message.orEmpty().contains(sdk.toString()))
            assertFalse(error.message.orEmpty().contains(name))
        }
    }

    @Test
    fun `wrong entry identity malformed class and malformed ZIP are errors`(@TempDir root: Path) {
        val sdk = sdk(root, "sdk", "java/lang/Expected" to type("java/lang/Actual"))
        assertFailsWith<ClassHierarchyIndexingException> { ClassHierarchyIndexer(sdk).index(emptyList(), listOf("java/lang/Expected")) }
        module(sdk, "java.base.jmod", "classes/java/lang/Expected.class" to byteArrayOf(1, 2, 3))
        assertFailsWith<ClassHierarchyIndexingException> { ClassHierarchyIndexer(sdk).index(emptyList(), listOf("java/lang/Expected")) }
        sdk.resolve("jmods/java.base.jmod").writeBytes(byteArrayOf(0x4a, 0x4d, 1, 0, 1, 2))
        assertFailsWith<ClassHierarchyIndexingException> { TargetJdkHeaders.open(sdk) }
        sdk.resolve("jmods/java.base.jmod").writeText("not a JMOD")
        assertFailsWith<ClassHierarchyIndexingException> { TargetJdkHeaders.open(sdk) }
    }

    @Test
    fun `CRC and forged small lengths do not allow unchecked class bytes`(@TempDir root: Path) {
        val sdk = sdk(root, "sdk", "java/lang/A" to type("java/lang/A"))
        val file = sdk.resolve("jmods/java.base.jmod")
        val original = Files.readAllBytes(file)
        val central = (0 until original.size - 4).first { offset ->
            original[offset] == 0x50.toByte() && original[offset + 1] == 0x4b.toByte() &&
                original[offset + 2] == 1.toByte() && original[offset + 3] == 2.toByte()
        }
        val badCrc = original.copyOf()
        java.nio.ByteBuffer.wrap(badCrc).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(central + 16, 0)
        file.writeBytes(badCrc)
        assertFailsWith<ClassHierarchyIndexingException> { TargetJdkHeaders.open(sdk).read("java/lang/A") }
        val falseLength = original.copyOf()
        java.nio.ByteBuffer.wrap(falseLength).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(central + 24, 1)
        file.writeBytes(falseLength)
        assertFailsWith<ClassHierarchyIndexingException> {
            TargetJdkHeaders.open(sdk, TargetJdkLimits(classBytes = 16)).read("java/lang/A")
        }
    }

    @Test
    fun `exact byte and inventory bounds permit the valid boundary`(@TempDir root: Path) {
        val bytes = type("java/lang/A")
        val sdk = sdk(root, "sdk", "java/lang/A" to bytes)
        val size = Files.size(sdk.resolve("jmods/java.base.jmod"))
        val limits = TargetJdkLimits(modules = 1, moduleBytes = size, totalModuleBytes = size, entries = 1,
            indexedNames = 1, classBytes = bytes.size, releaseBytes = Files.size(sdk.resolve("release")).toInt())
        assertTrue(TargetJdkHeaders.open(sdk, limits).read("java/lang/A").contentEquals(bytes))
    }

    @Test
    fun `all configured bounds reject inputs before unbounded copies`(@TempDir root: Path) {
        val sdk = sdk(root, "sdk", "java/lang/A" to type("java/lang/A"), "java/lang/B" to type("java/lang/B"))
        val limits = listOf(
            TargetJdkLimits(moduleBytes = 4), TargetJdkLimits(totalModuleBytes = 4),
            TargetJdkLimits(entries = 1), TargetJdkLimits(entryNameLength = 20),
            TargetJdkLimits(indexedNames = 1), TargetJdkLimits(releaseBytes = 1),
        )
        limits.forEach { bound -> assertFailsWith<ClassHierarchyIndexingException> { TargetJdkHeaders.open(sdk, bound) } }
        assertFailsWith<ClassHierarchyIndexingException> { TargetJdkHeaders.open(sdk, TargetJdkLimits(classBytes = 1)).read("java/lang/A") }
        module(sdk, "second.jmod", "classes/java/lang/C.class" to type("java/lang/C"))
        assertFailsWith<ClassHierarchyIndexingException> { TargetJdkHeaders.open(sdk, TargetJdkLimits(modules = 1)) }
    }

    @Test
    fun `cold dependency headers cannot shadow or replace mandatory SDK classes`(@TempDir root: Path) {
        assertSdkCannotBeShadowed(root, warm = false)
    }

    @Test
    fun `warm dependency headers cannot shadow or replace mandatory SDK classes`(@TempDir root: Path) {
        assertSdkCannotBeShadowed(root, warm = true)
    }

    private fun assertSdkCannotBeShadowed(root: Path, warm: Boolean) {
        val sdk = sdk(root, "sdk", "java/util/List" to type("java/util/List", "java/util/Collection"),
            "java/util/Collection" to type("java/util/Collection"), "javax/net/Existing" to type("javax/net/Existing"))
        val cache = ClassIndexCache(root.resolve("cache"), "test-engine")
        listOf("java/util/List", "java/lang/OnlyInNewerJdk", "javax/net/MissingOwnedClass").forEachIndexed { index, name ->
            val jar = root.resolve("dependency-$index.jar")
            JarOutputStream(Files.newOutputStream(jar)).use { out ->
                out.putNextEntry(JarEntry("$name.class"))
                out.write(type(name, "impostor/Parent"))
                out.closeEntry()
            }
            if (warm) {
                val fingerprint = TargetJdkHeaders.open(sdk).fingerprint
                val scoped = ClassIndexCache(cache.directory, ContentFingerprint.values(listOf("test-engine", fingerprint)))
                assertEquals(CacheWriteResult.Written, HierarchyIndexCache(scoped).write(sha256(Files.readAllBytes(jar)), listOf(
                    HierarchyFact(name, setOf("impostor/Parent"), emptyList(), false, emptySet()),
                )))
            }
            val indexer = ClassHierarchyIndexer(sdk, cache)
            assertFailsWith<ClassHierarchyIndexingException> { indexer.index(listOf(jar), listOf(name)) }
            assertNull(indexer.targetJdkFingerprint)
            assertEquals(if (warm) 1 else 0, indexer.statistics.hierarchyCacheHits)
        }
    }

    @Test
    fun `third party javax packages keep normal classpath ordering`(@TempDir root: Path) {
        val sdk = sdk(root, "sdk", "java/lang/Object" to type("java/lang/Object"))
        val jars = listOf("First", "Second").map { parent ->
            root.resolve("$parent.jar").also { jar ->
                JarOutputStream(Files.newOutputStream(jar)).use { out ->
                    out.putNextEntry(JarEntry("javax/inject/Inject.class"))
                    out.write(type("javax/inject/Inject", "vendor/$parent"))
                    out.closeEntry()
                }
            }
        }
        val cache = ClassIndexCache(root.resolve("cache"), "test-engine")
        repeat(2) {
            val indexed = ClassHierarchyIndexer(sdk, cache).index(jars, listOf("javax/inject/NotOnClasspath"))
            assertEquals(setOf("vendor/First"), indexed.directSupertypesOf("javax/inject/Inject"))
            assertNull(indexed.directSupertypesOf("javax/inject/NotOnClasspath"))
        }
    }

    @Test
    fun `target release controls multi release selection and SDK scoped warm headers`(@TempDir root: Path) {
        val seventeen = sdk(root, "seventeen", "java/lang/Object" to type("java/lang/Object"))
        val twentyOne = sdk(root, "twenty-one", "java/lang/Object" to type("java/lang/Object"))
        twentyOne.resolve("release").writeText("JAVA_VERSION=\"21\"\n")
        val jar = root.resolve("dependency.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            listOf(
                "demo/Versioned.class" to type("demo/Versioned", "demo/Base"),
                "META-INF/versions/17/demo/Versioned.class" to type("demo/Versioned", "demo/Seventeen"),
                "META-INF/versions/21/demo/Versioned.class" to type("demo/Versioned", "demo/TwentyOne"),
                "META-INF/versions/21/demo/Only.class" to type("demo/Only", "demo/TwentyOne"),
                "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n".toByteArray(),
            ).forEach { (name, bytes) -> out.putNextEntry(JarEntry(name)); out.write(bytes); out.closeEntry() }
        }
        val cache = ClassIndexCache(root.resolve("cache"), "test-engine")
        listOf(seventeen to "demo/Seventeen", twentyOne to "demo/TwentyOne").forEach { (sdk, parent) ->
            repeat(2) { run ->
                val indexer = ClassHierarchyIndexer(sdk, cache)
                val hierarchy = indexer.index(listOf(jar))
                assertEquals(setOf(parent), hierarchy.directSupertypesOf("demo/Versioned"))
                assertEquals(if (sdk == seventeen) null else setOf("demo/TwentyOne"), hierarchy.directSupertypesOf("demo/Only"))
                assertEquals(run, indexer.statistics.hierarchyCacheHits)
            }
        }
        val host = ClassHierarchyIndexer().index(listOf(jar))
        assertEquals(setOf(if (Runtime.version().feature() >= 21) "demo/TwentyOne" else "demo/Seventeen"), host.directSupertypesOf("demo/Versioned"))
    }

    private fun sdk(root: Path, name: String, vararg classes: Pair<String, ByteArray>): Path = root.resolve(name).apply {
        resolve("jmods").createDirectories()
        resolve("release").writeText("JAVA_VERSION=\"17\"\n")
        module(this, "java.base.jmod", *classes.map { (name, bytes) -> "classes/$name.class" to bytes }.toTypedArray())
    }

    private fun module(home: Path, name: String, vararg entries: Pair<String, ByteArray>) {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip -> entries.forEach { (name, data) ->
            val crc = java.util.zip.CRC32().apply { update(data) }
            zip.putNextEntry(ZipEntry(name).apply { time = 0; method = ZipEntry.STORED; size = data.size.toLong(); compressedSize = size; this.crc = crc.value })
            zip.write(data)
            zip.closeEntry()
        } }
        home.resolve("jmods/$name").writeBytes(byteArrayOf(0x4a, 0x4d, 1, 0) + bytes.toByteArray())
    }

    private fun type(name: String, parent: String = "java/lang/Object"): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, if (name == "java/lang/Object") null else parent, null)
        visitEnd()
    }.toByteArray()
}
