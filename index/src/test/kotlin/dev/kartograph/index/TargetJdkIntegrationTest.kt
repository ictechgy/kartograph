package dev.kartograph.index

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class TargetJdkIntegrationTest {
    @Test
    fun `same javac17 outputs and shared cold warm cache follow the selected real SDK`(@TempDir root: Path) {
        val seventeenValue = System.getenv("KARTOGRAPH_JDK17_HOME")
        val twentyOneValue = System.getenv("KARTOGRAPH_JDK21_HOME")
        assumeTrue(!seventeenValue.isNullOrBlank() && !twentyOneValue.isNullOrBlank(), "real JDK 17 and 21 homes are required")
        val seventeen = Path.of(requireNotNull(seventeenValue)).toRealPath()
        val twentyOne = Path.of(requireNotNull(twentyOneValue)).toRealPath()
        assertEquals(17, TargetJdkHeaders.open(seventeen).javaFeature)
        assertEquals(21, TargetJdkHeaders.open(twentyOne).javaFeature)
        val source = root.resolve("UsesList.java").apply { writeText("""
            public abstract class UsesList implements java.util.List<String> {
                public static int size(java.util.List<?> values) { return values.size(); }
            }
        """.trimIndent()) }
        val classes = root.resolve("classes").createDirectories()
        val compiler = ProcessBuilder(seventeen.resolve("bin/javac").toString(), "-d", classes.toString(), source.toString())
            .redirectErrorStream(true).start()
        val output = compiler.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, compiler.waitFor(), output)
        val compiledIdentity = ContentFingerprint.hash(classes)
        val cache = ClassIndexCache(root.resolve("cache"), "explicit-sdk-integration")
        val fingerprints = mutableMapOf<Path, String>()
        // SDK 17 -> SDK 21 -> SDK 17 재사용으로 경로·mtime와 host runtime에 의존하는 캐시 혼입을 잡는다.
        listOf(seventeen, twentyOne, seventeen).forEachIndexed { sdkRun, sdk ->
            repeat(2) { run ->
                val indexer = ClassFileIndexer(targetJdkHome = sdk, cache = cache)
                val indexed = indexer.indexWithObservations(listOf(classes), emptyList())
                val parent = if (sdk == seventeen) "java/util/Collection" else "java/util/SequencedCollection"
                assertEquals(setOf(parent), indexed.hierarchy.directSupertypesOf("java/util/List"))
                assertEquals(if (sdk == seventeen) null else setOf("java/util/Collection"), indexed.hierarchy.directSupertypesOf("java/util/SequencedCollection"))
                assertNotNull(indexed.hierarchy.declaredMethodsOf("java/util/List"))
                assertTrue(indexed.statistics.hierarchyNanos > 0)
                assertEquals(compiledIdentity, ContentFingerprint.hash(classes))
                assertEquals(if (sdkRun == 0 && run == 0) 0 else 1, indexed.statistics.cacheHits)
                val fingerprint = assertNotNull(indexer.targetJdkFingerprint)
                fingerprints.putIfAbsent(sdk, fingerprint)?.let { assertEquals(it, fingerprint) }
                assertEquals(1, indexed.graph.nodes.values.count { it.id.value == "class:UsesList" })
                assertNull(indexed.graph.nodes[JvmNodeId.classId("java/util/List")])
            }
        }
        assertNotEquals(fingerprints[seventeen], fingerprints[twentyOne])
        val legacy = ClassHierarchyIndexer().index(emptyList(), listOf("java/util/List"))
        assertEquals(setOf(if (Runtime.version().feature() >= 21) "java/util/SequencedCollection" else "java/util/Collection"),
            legacy.directSupertypesOf("java/util/List"))
        // 실행 JVM의 feature도 관측하고 --release나 target SDK와 혼동하지 않는다.
        System.getenv("KARTOGRAPH_EXPECT_HOST_FEATURE")?.let { expected -> assertEquals(expected.toInt(), Runtime.version().feature()) }
    }
}
