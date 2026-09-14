package dev.kartograph.index

import dev.kartograph.core.InputFingerprint
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** 같은 before/after fingerprint 경계 안에서만 관측 digest를 인덱싱에 전달한다. */
public class VerifiedCaptureScope private constructor(
    private val cache: ClassIndexCache?,
    maximumSpoolBytes: Long,
    private val maximumCacheableJarBytes: Long,
    private val spoolDirectory: Path?,
    prepareCache: (() -> Boolean)?,
) {
    private var phase: Phase = Phase.BEFORE_CAPTURE
    private val initialInputs = mutableListOf<ObservedInput>()
    private val cachePreparation = prepareCache?.let(::OwnedCachePreparation)
    private var coldPopulationDecision: Boolean? = null
    private var remainingSpoolBytes = minOf(maximumSpoolBytes, MAX_RETAINED_TOTAL_BYTES)
    private var eligibleJars = 0
    private var completedEligibleJars = 0
    private var populationFailed = false
    private val populationLock = Any()

    /** 현재 capture pass에서 파일을 새로 읽으며, action 단계의 오래된 관측 재사용을 막는다. */
    public fun capture(project: Path, path: Path, role: String, externalSlot: String): InputFingerprint {
        check(phase == Phase.BEFORE_CAPTURE || phase == Phase.AFTER_CAPTURE) {
            "verified capture inputs can only be read during capture passes"
        }
        val eligibleJar = phase == Phase.BEFORE_CAPTURE && role == "classpath" &&
            path.fileName.toString().endsWith(".jar", ignoreCase = true) && Files.isRegularFile(path, NOFOLLOW_LINKS)
        val captureSpool = eligibleJar && captureColdSpools() && isCacheableJarSize(path)
        val retainedMaximum = if (captureSpool) minOf(MAX_RETAINED_JAR_BYTES, remainingSpoolBytes) else 0
        val observed = ContentFingerprint.captureObserved(
            project,
            path,
            role,
            externalSlot,
            retainedMaximum,
            spoolDirectory,
        )
        if (phase == Phase.BEFORE_CAPTURE) {
            observed.spool?.let { remainingSpoolBytes -= it.byteSize }
            if (captureSpool) eligibleJars++
            initialInputs += ObservedInput(
                role,
                observed.realPath,
                observed.rawFileSha256,
                observed.spool,
                captureSpool,
            )
        }
        return observed.fingerprint
    }

    /** before에 같은 역할·순서로 완전히 fingerprint된 입력만 인덱싱한다. */
    public fun indexWithObservations(
        classRoots: Iterable<Path>,
        classpath: Iterable<Path>?,
        serviceResources: Iterable<Path>,
        generatedClassRoots: Iterable<Path>,
        cache: ClassIndexCache?,
    ): IndexedClasses {
        ensureAction()
        val roots = classRoots.toList()
        val dependencies = classpath?.toList()
        val resources = serviceResources.toList()
        val generated = generatedClassRoots.toList()
        check(cache === this.cache) { "verified capture cache does not match indexing cache" }
        verifyInputs("classes", roots)
        verifyInputs("classpath", dependencies.orEmpty())
        verifyInputs("service-resources", resources)
        verifyInputs("generated-classes", generated)
        val capturedDependencies = initialInputs.filter { it.role == "classpath" }
        val pending = linkedMapOf<Path, ArrayDeque<ObservedInput>>()
        dependencies.orEmpty().zip(capturedDependencies).forEach { (path, input) ->
            pending.getOrPut(path.toAbsolutePath().normalize()) { ArrayDeque() }.addLast(input)
        }
        val lookup = ObservedJarDigestLookup { jar ->
            ensureAction()
            val input = pending[jar.toAbsolutePath().normalize()]?.removeFirstOrNull()
                ?: return@ObservedJarDigestLookup null
            val digest = input.rawFileSha256 ?: return@ObservedJarDigestLookup null
            ObservedJarInput(digest, input.takeSpool()) { ready ->
                if (input.populationEligible) {
                    synchronized(populationLock) {
                        completedEligibleJars++
                        if (!ready) populationFailed = true
                    }
                }
            }
        }
        return ClassFileIndexer(cache).indexWithObservations(
            roots,
            dependencies,
            resources,
            generated,
            lookup,
        )
    }

    private fun verifyInputs(role: String, paths: List<Path>) {
        val expected = initialInputs.filter { it.role == role }.map { it.realPath }
        val actual = paths.map { path -> resolveCurrent(path, role) }
        check(expected == actual) { "indexed $role inputs do not match verified capture order" }
    }

    private fun resolveCurrent(path: Path, role: String): Path = try {
        path.toRealPath()
    } catch (_: IOException) {
        throw IllegalStateException("indexed $role input changed after initial capture")
    } catch (_: SecurityException) {
        throw IllegalStateException("indexed $role input changed after initial capture")
    }

    private fun ensureAction() {
        check(phase == Phase.ACTION) { "verified capture scope is not active" }
    }

    internal fun beginAction() {
        check(phase == Phase.BEFORE_CAPTURE)
        cachePreparation?.await()
        phase = Phase.ACTION
    }

    internal fun beginAfterCapture() {
        ensureAction()
        phase = Phase.AFTER_CAPTURE
    }

    internal fun markPopulatedIfComplete() {
        check(phase == Phase.AFTER_CAPTURE)
        val populationComplete = synchronized(populationLock) {
            eligibleJars > 0 && completedEligibleJars == eligibleJars && !populationFailed
        }
        if (coldPopulationDecision == true && populationComplete) {
            cache?.let { HierarchyIndexCache(it).markPopulated() }
        }
    }

    internal fun close(primaryFailure: Throwable? = null) {
        phase = Phase.CLOSED
        initialInputs.forEach(ObservedInput::releaseSpool)
        initialInputs.clear()
        try {
            cachePreparation?.await()
        } catch (preparationFailure: RuntimeException) {
            if (primaryFailure == null) throw preparationFailure
            if (preparationFailure !== primaryFailure) primaryFailure.addSuppressed(preparationFailure)
        }
    }

    @Synchronized
    private fun captureColdSpools(): Boolean {
        coldPopulationDecision?.let { return it }
        val enabled = cachePreparation?.await() == true && cache?.let { !HierarchyIndexCache(it).isPopulated() } == true
        coldPopulationDecision = enabled
        return enabled
    }

    private fun isCacheableJarSize(path: Path): Boolean = try {
        Files.size(path) <= maximumCacheableJarBytes
    } catch (_: IOException) {
        // 권고용 크기 관측 실패는 실제 fingerprint/JAR 읽기와 population 재시도에 맡긴다.
        true
    } catch (_: SecurityException) {
        true
    } catch (_: UnsupportedOperationException) {
        true
    }

    private class ObservedInput(
        val role: String,
        val realPath: Path,
        val rawFileSha256: String?,
        private var spool: OwnedJarSpool?,
        val populationEligible: Boolean,
    ) {
        fun takeSpool(): OwnedJarSpool? = spool.also { spool = null }
        fun releaseSpool() {
            spool?.close()
            spool = null
        }
    }
    private enum class Phase { BEFORE_CAPTURE, ACTION, AFTER_CAPTURE, CLOSED }

    internal companion object {
        private const val MAX_RETAINED_JAR_BYTES = 128L * 1024 * 1024
        private const val MAX_RETAINED_TOTAL_BYTES = 256L * 1024 * 1024

        fun open(
            cache: ClassIndexCache?,
            maximumSpoolBytes: Long = MAX_RETAINED_TOTAL_BYTES,
            maximumCacheableJarBytes: Long = ClassHierarchyIndexer.defaultSnapshotMaximumBytes().toLong(),
            spoolDirectory: Path? = null,
            prepareCache: (() -> Boolean)? = cache?.let { configured ->
                { configured.identity != null && configured.namespace != null }
            },
        ): VerifiedCaptureScope = VerifiedCaptureScope(
            cache,
            maximumSpoolBytes.coerceAtLeast(0),
            maximumCacheableJarBytes.coerceAtLeast(0),
            spoolDirectory,
            prepareCache.takeIf { cache != null },
        )
    }

    private class OwnedCachePreparation(action: () -> Boolean) {
        private val task = FutureTask(action)

        init {
            Thread(task, "kartograph-cache-identity").apply {
                isDaemon = true
                start()
            }
        }

        fun await(): Boolean {
            var interrupted = false
            var preparationFailed = false
            var result: Boolean? = null
            while (result == null && !preparationFailed) {
                try {
                    result = task.get()
                } catch (_: InterruptedException) {
                    interrupted = true
                } catch (_: ExecutionException) {
                    preparationFailed = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
            if (preparationFailed) throw IllegalStateException("index cache preparation failed")
            if (interrupted) {
                throw IllegalStateException("index cache preparation was interrupted")
            }
            return requireNotNull(result)
        }
    }
}

internal fun interface ObservedJarDigestLookup {
    fun take(jar: Path): ObservedJarInput?
}

internal class ObservedJarInput(
    val sha256: String,
    private var spool: OwnedJarSpool?,
    private val completion: (Boolean) -> Unit,
) {
    private var completed = false

    @Synchronized
    fun takeSpool(): OwnedJarSpool? = spool.also { spool = null }

    @Synchronized
    fun complete(cacheReady: Boolean) {
        if (completed) return
        completed = true
        spool?.close()
        spool = null
        completion(cacheReady)
    }
}
