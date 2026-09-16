package dev.kartograph.index

import dev.kartograph.core.ClassHierarchy
import dev.kartograph.core.HierarchyMethod
import dev.kartograph.core.Visibility
import java.io.IOException
import java.io.InputStream
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Type
import org.objectweb.asm.Opcodes

/** directory와 JAR dependency에서 method body 없이 class 상속 header만 읽는다. */
public class ClassHierarchyIndexer(private val cache: ClassIndexCache? = null) {
    private var maximumSnapshotBytes: Int = safeSnapshotMaximum(MAX_JAR_SNAPSHOT_BYTES)
    private var observedJarDigests: ObservedJarDigestLookup? = null

    internal constructor(cache: ClassIndexCache?, maximumSnapshotBytes: Int) : this(cache) {
        require(maximumSnapshotBytes > 0)
        this.maximumSnapshotBytes = safeSnapshotMaximum(maximumSnapshotBytes)
    }

    internal constructor(cache: ClassIndexCache?, observedJarDigests: ObservedJarDigestLookup) : this(cache) {
        this.observedJarDigests = observedJarDigests
    }

    internal var statistics: HierarchyIndexingStatistics = HierarchyIndexingStatistics()
        private set

    /** 입력 순서를 보존하고 중복 class는 첫 번째 hierarchy를 사용한다. */
    public fun index(
        classpathEntries: Iterable<Path>,
        referencedSupertypes: Iterable<String> = emptyList(),
    ): ClassHierarchy {
        val runStatistics = MutableHierarchyIndexingStatistics()
        val supertypesByClass = linkedMapOf<String, Set<String>>()
        val methodsByClass = linkedMapOf<String, List<HierarchyMethod>>()
        val annotationTypes = linkedMapOf<String, Set<String>>()
        fun merge(indexed: IndexedHierarchyEntry) {
            runStatistics.add(indexed.statistics)
            indexed.facts.forEach { fact ->
                if (supertypesByClass.putIfAbsent(fact.internalName, fact.supertypes) == null) {
                    methodsByClass[fact.internalName] = fact.methods
                    if (fact.isAnnotation) annotationTypes[fact.internalName] = fact.annotations
                }
            }
        }
        return try {
            if (cache == null) {
                classpathEntries.forEach { entry ->
                    merge(indexEntry(PreparedHierarchyEntry(entry, observedJarDigests?.take(entry))))
                }
            } else {
                val prepared = classpathEntries.map { entry ->
                    // 동일 path의 서로 다른 관측값도 worker 시작 순서가 아니라 classpath 순서로 고정한다.
                    PreparedHierarchyEntry(entry, observedJarDigests?.take(entry))
                }
                try {
                    IndexWorkPool().use { pool ->
                        // 완료 facts를 전체 classpath만큼 중복 보관하지 않고 worker batch마다 순서대로 병합한다.
                        prepared.chunked(MAX_PARALLEL_HEADER_BATCH).forEach { batch ->
                            pool.map(batch, ::indexEntry).forEach(::merge)
                        }
                    }
                } finally {
                    // 실패로 제출되지 않은 뒤쪽 batch의 owned spool도 즉시 해제한다.
                    prepared.forEach { it.observedJar?.complete(false) }
                }
            }
            expandJdkHierarchy(supertypesByClass, methodsByClass, referencedSupertypes)
            ClassHierarchy(supertypesByClass, methodsByClass, annotationTypes)
        } finally {
            statistics = runStatistics.freeze()
        }
    }

    private fun indexEntry(prepared: PreparedHierarchyEntry): IndexedHierarchyEntry {
        val entryStatistics = MutableHierarchyIndexingStatistics()
        val facts = when {
            prepared.path.isDirectory() -> try {
                readDirectory(prepared.path)
            } finally {
                prepared.observedJar?.complete(false)
            }
            prepared.path.isRegularFile() && prepared.path.fileName.toString().endsWith(".jar", ignoreCase = true) ->
                readJar(prepared.path, entryStatistics, prepared.observedJar)
            else -> {
                prepared.observedJar?.complete(false)
                throw ClassHierarchyIndexingException("classpath entry must be a class directory or JAR")
            }
        }
        return IndexedHierarchyEntry(facts, entryStatistics.freeze())
    }

    private fun expandJdkHierarchy(
        supertypesByClass: MutableMap<String, Set<String>>,
        methodsByClass: MutableMap<String, List<HierarchyMethod>>,
        referencedSupertypes: Iterable<String>,
    ) {
        val queue = ArrayDeque(
            (supertypesByClass.values.flatten() + referencedSupertypes)
                .filter { internalName -> internalName.isJdkClass() }
                .sorted(),
        )
        while (queue.isNotEmpty()) {
            val internalName = queue.removeFirst()
            if (internalName in supertypesByClass) continue
            val resource = ClassLoader.getSystemResourceAsStream("$internalName.class")
                ?: if (internalName.startsWith("java/") || internalName.startsWith("jdk/")) {
                    throw ClassHierarchyIndexingException(
                        "JDK class hierarchy is unavailable for ${internalName.takeIf { it.matches(Regex("[A-Za-z0-9_$/]+")) }?.replace('/', '.') ?: "an invalid type name"}; run kartograph with a compatible JDK or supply its classpath",
                    )
                } else {
                    continue
                }
            val fact = try {
                resource.use(::readJdkClass)
            } catch (error: IOException) {
                throw ClassHierarchyIndexingException("JDK class hierarchy cannot be read", error)
            }
            supertypesByClass[fact.internalName] = fact.supertypes
            methodsByClass[fact.internalName] = fact.methods
            fact.supertypes.filter { name -> name.isJdkClass() }.sorted().forEach(queue::addLast)
        }
    }

    private fun String.isJdkClass(): Boolean = JDK_PACKAGE_PREFIXES.any(::startsWith)

    private fun readDirectory(root: Path): List<HierarchyFact> = try {
        Files.walk(root).use { paths ->
            paths.filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".class") }
                .filter { path -> !root.relativize(path).startsWith(MULTI_RELEASE_PREFIX) }
                .sorted()
                .map { path -> Files.newInputStream(path).use(::readClasspathClass) }
                .toList()
        }
    } catch (error: IOException) {
        throw ClassHierarchyIndexingException("classpath directory cannot be read", error)
    } catch (error: UncheckedIOException) {
        throw ClassHierarchyIndexingException("classpath directory cannot be read", error)
    } catch (error: SecurityException) {
        throw ClassHierarchyIndexingException("classpath directory cannot be read", error)
    }

    private fun readJar(
        jar: Path,
        statistics: MutableHierarchyIndexingStatistics,
        observedJar: ObservedJarInput?,
    ): List<HierarchyFact> {
        statistics.hierarchyJars++
        var completionReported = false
        fun complete(cacheReady: Boolean) {
            if (!completionReported) {
                observedJar?.complete(cacheReady)
                completionReported = true
            }
        }
        try {
            val configuredCache = cache
            if (configuredCache == null) {
                statistics.hierarchyParsedJars++
                return readJarFile(jar).also { complete(false) }
            }
            if (configuredCache.identity == null || configuredCache.namespace == null) {
                statistics.hierarchyUnavailableEntries++
                statistics.hierarchyParsedJars++
                return readJarFile(jar).also { complete(false) }
            }
            val hierarchyCache = HierarchyIndexCache(configuredCache)
            fun storeFacts(jarHash: String, facts: List<HierarchyFact>): List<HierarchyFact> {
                when (hierarchyCache.write(jarHash, facts)) {
                    CacheWriteResult.Written -> complete(true)
                    CacheWriteResult.Failed -> {
                        statistics.hierarchyWriteFailures++
                        complete(false)
                    }
                    CacheWriteResult.Unavailable -> {
                        statistics.hierarchyUnavailableEntries++
                        complete(false)
                    }
                }
                return facts
            }
            val observedJarHash = observedJar?.sha256
            if (observedJarHash != null) {
                readCachedFacts(hierarchyCache, observedJarHash, statistics)?.let { facts ->
                    complete(true)
                    return facts
                }
            }
            val capturedSpool = observedJar?.takeSpool()
            if (capturedSpool != null) {
                val jarHash = requireNotNull(observedJarHash)
                statistics.hierarchyParsedJars++
                val facts = capturedSpool.consume(::readJarFile)
                return storeFacts(jarHash, facts)
            }
            val snapshot = readBoundedSnapshot(jar)
            if (snapshot == null) {
                statistics.hierarchyUnavailableEntries++
                statistics.hierarchyParsedJars++
                return readJarFile(jar).also { complete(false) }
            }
            val jarHash = sha256(snapshot)
            if (observedJarHash != null && jarHash != observedJarHash) {
                throw ClassHierarchyIndexingException("classpath input changed during verified capture")
            }
            if (observedJarHash == null) {
                readCachedFacts(hierarchyCache, jarHash, statistics)?.let { facts ->
                    complete(true)
                    return facts
                }
            }
            statistics.hierarchyParsedJars++
            val facts = readSnapshotJar(snapshot)
            if (facts == null) {
                statistics.hierarchyUnavailableEntries++
                return readJarFile(jar).also { complete(false) }
            }
            return storeFacts(jarHash, facts)
        } finally {
            if (!completionReported) complete(false)
        }
    }

    private fun readCachedFacts(
        cache: HierarchyIndexCache,
        jarHash: String,
        statistics: MutableHierarchyIndexingStatistics,
    ): List<HierarchyFact>? = when (val cached = cache.read(jarHash)) {
        is HierarchyCacheReadResult.Hit -> cached.facts.also { statistics.hierarchyCacheHits++ }
        HierarchyCacheReadResult.Miss -> null
        HierarchyCacheReadResult.Invalid -> {
            statistics.hierarchyInvalidEntries++
            null
        }
        HierarchyCacheReadResult.Unavailable -> {
            statistics.hierarchyUnavailableEntries++
            null
        }
    }

    private fun readJarFile(jar: Path): List<HierarchyFact> = try {
        JarFile(jar.toFile(), false).use { archive ->
            val multiRelease = archive.manifest?.mainAttributes?.getValue("Multi-Release")
                ?.equals("true", ignoreCase = true) == true
            archive.entries().asSequence()
                .mapNotNull { entry -> entry.toClassCandidate(multiRelease) }
                .groupBy(ClassCandidate::logicalName)
                .toSortedMap()
                .values
                .map { candidates -> candidates.maxBy(ClassCandidate::version).entry }
                .map { entry -> archive.getInputStream(entry).use(::readClasspathClass) }
                .toList()
        }
    } catch (error: IOException) {
        throw ClassHierarchyIndexingException("classpath JAR cannot be read", error)
    }

    private fun readBoundedSnapshot(jar: Path): ByteArray? = try {
        val observedSize = Files.size(jar)
        if (observedSize !in 0..maximumSnapshotBytes.toLong()) return null
        val snapshot = ByteArray(observedSize.toInt())
        Files.newInputStream(jar).use { input ->
            var offset = 0
            while (offset < snapshot.size) {
                val count = input.read(snapshot, offset, snapshot.size - offset)
                if (count < 0) return snapshot.copyOf(offset)
                if (count == 0) continue
                offset += count
            }
            // 크기 관측 뒤 커진 파일은 live fallback으로 보내고 이 snapshot으로 캐시하지 않는다.
            if (input.read() >= 0) return null
        }
        snapshot
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    /** JarFile의 manifest 탐색과 multi-release/entry 의미를 그대로 쓰기 위해 snapshot을 임시 JAR로 연다. */
    private fun readSnapshotJar(snapshot: ByteArray): List<HierarchyFact>? {
        val temporary = try {
            Files.createTempFile("kartograph-hierarchy-", ".jar")
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        } catch (_: UnsupportedOperationException) {
            return null
        }
        try {
            try {
                Files.write(temporary, snapshot)
            } catch (_: IOException) {
                return null
            } catch (_: SecurityException) {
                return null
            } catch (_: UnsupportedOperationException) {
                return null
            }
            return readJarFile(temporary)
        } finally {
            try {
                Files.deleteIfExists(temporary)
            } catch (_: IOException) {
                temporary.toFile().deleteOnExit()
            } catch (_: SecurityException) {
                temporary.toFile().deleteOnExit()
            }
        }
    }

    private fun readClasspathClass(input: InputStream): HierarchyFact =
        readClass(input, "invalid class file in classpath")

    private fun readJdkClass(input: InputStream): HierarchyFact =
        readClass(input, "running JDK class hierarchy cannot be parsed; use a supported JDK")

    private fun readClass(input: InputStream, failureMessage: String): HierarchyFact {
        try {
            val visitor = HierarchyVisitor()
            val flags = ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES
            ClassReader(input).accept(visitor, flags)
            return visitor.fact()
        } catch (error: RuntimeException) {
            throw ClassHierarchyIndexingException(failureMessage, error)
        }
    }

    private fun JarEntry.toClassCandidate(multiRelease: Boolean): ClassCandidate? {
        if (isDirectory || !name.endsWith(".class")) return null
        if (!name.startsWith(MULTI_RELEASE_PREFIX)) return ClassCandidate(name, 0, this)
        if (!multiRelease) return null
        val versionAndName = name.removePrefix(MULTI_RELEASE_PREFIX)
        val version = versionAndName.substringBefore('/').toIntOrNull() ?: return null
        val logicalName = versionAndName.substringAfter('/', missingDelimiterValue = "")
        return logicalName.takeIf(String::isNotEmpty)
            ?.takeIf { version in 9..Runtime.version().feature() }
            ?.let { ClassCandidate(it, version, this) }
    }

    internal companion object {
        private const val MULTI_RELEASE_PREFIX = "META-INF/versions/"
        private const val MAX_PARALLEL_HEADER_BATCH = 16
        private const val MAX_JAR_SNAPSHOT_BYTES = 256 * 1024 * 1024
        private val JDK_PACKAGE_PREFIXES = listOf("java/", "javax/", "jdk/", "com/sun/", "sun/")

        fun defaultSnapshotMaximumBytes(): Int = safeSnapshotMaximum(MAX_JAR_SNAPSHOT_BYTES)

        private fun safeSnapshotMaximum(configured: Int): Int = minOf(
            configured.toLong(),
            Runtime.getRuntime().maxMemory() / 8,
        ).coerceAtLeast(1).toInt()
    }
}

private data class PreparedHierarchyEntry(
    val path: Path,
    val observedJar: ObservedJarInput?,
)

private data class IndexedHierarchyEntry(
    val facts: List<HierarchyFact>,
    val statistics: HierarchyIndexingStatistics,
)

private class MutableHierarchyIndexingStatistics {
    var hierarchyJars: Int = 0
    var hierarchyCacheHits: Int = 0
    var hierarchyParsedJars: Int = 0
    var hierarchyInvalidEntries: Int = 0
    var hierarchyWriteFailures: Int = 0
    var hierarchyUnavailableEntries: Int = 0

    fun add(other: HierarchyIndexingStatistics) {
        hierarchyJars += other.hierarchyJars
        hierarchyCacheHits += other.hierarchyCacheHits
        hierarchyParsedJars += other.hierarchyParsedJars
        hierarchyInvalidEntries += other.hierarchyInvalidEntries
        hierarchyWriteFailures += other.hierarchyWriteFailures
        hierarchyUnavailableEntries += other.hierarchyUnavailableEntries
    }

    fun freeze(): HierarchyIndexingStatistics = HierarchyIndexingStatistics(
        hierarchyJars,
        hierarchyCacheHits,
        hierarchyParsedJars,
        hierarchyInvalidEntries,
        hierarchyWriteFailures,
        hierarchyUnavailableEntries,
    )
}

private data class ClassCandidate(val logicalName: String, val version: Int, val entry: JarEntry)

private class HierarchyVisitor : ClassVisitor(Opcodes.ASM9) {
    private lateinit var internalName: String
    private var supertypes: Set<String> = emptySet()
    private val methods = mutableListOf<HierarchyMethod>()
    private var isAnnotation = false
    private val annotations = mutableSetOf<String>()

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>,
    ) {
        internalName = name
        isAnnotation = access and Opcodes.ACC_ANNOTATION != 0
        supertypes = buildSet {
            if (superName != null && superName != "java/lang/Object") add(superName)
            addAll(interfaces)
        }
    }

    override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): org.objectweb.asm.MethodVisitor? {
        val visibility = when {
            access and Opcodes.ACC_PUBLIC != 0 -> Visibility.PUBLIC
            access and Opcodes.ACC_PROTECTED != 0 -> Visibility.PROTECTED
            access and Opcodes.ACC_PRIVATE != 0 -> Visibility.PRIVATE
            else -> Visibility.PACKAGE_PRIVATE
        }
        methods += HierarchyMethod(name, descriptor, visibility, access and Opcodes.ACC_STATIC != 0, access and Opcodes.ACC_FINAL != 0)
        return null
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
        if (isAnnotation) annotations += Type.getType(descriptor).internalName
        return null
    }

    fun fact(): HierarchyFact = HierarchyFact(internalName, supertypes, methods, isAnnotation, annotations)
}

internal data class HierarchyFact(
    val internalName: String,
    val supertypes: Set<String>,
    val methods: List<HierarchyMethod>,
    val isAnnotation: Boolean,
    val annotations: Set<String>,
)

/** dependency classpath를 완전하게 읽지 못해 부분 hierarchy를 버릴 때 사용한다. */
public class ClassHierarchyIndexingException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
