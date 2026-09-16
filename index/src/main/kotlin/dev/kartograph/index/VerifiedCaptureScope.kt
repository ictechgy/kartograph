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
    private val digestWorkers = IndexWorkPool()

    /** 현재 capture pass에서 파일을 새로 읽으며, action 단계의 오래된 관측 재사용을 막는다. */
    public fun capture(project: Path, path: Path, role: String, externalSlot: String): InputFingerprint =
        captureAll(project, listOf(CaptureInput(path, role, externalSlot))).single()

    /**
     * 여러 입력을 한 번의 capture pass 안에서 관측한다. spool 예산과 입력 기록은 입력 순서대로 호출 스레드에서 정하고,
     * 파일 digest만 제한된 worker에서 병렬로 계산한다. 결과 값과 순서는 [capture]를 차례로 부른 것과 같다.
     */
    public fun captureAll(project: Path, inputs: List<CaptureInput>): List<InputFingerprint> {
        check(phase == Phase.BEFORE_CAPTURE || phase == Phase.AFTER_CAPTURE) {
            "verified capture inputs can only be read during capture passes"
        }
        // 첫 JAR의 spool 결정은 캐시 준비를 기다리므로, 그 앞의 입력은 먼저 digest해 준비와 겹치게 한다.
        val firstJar = if (phase == Phase.BEFORE_CAPTURE) inputs.indexOfFirst(::isEligibleJar) else -1
        if (firstJar <= 0) return captureSegment(project, inputs)
        return captureSegment(project, inputs.subList(0, firstJar)) + captureSegment(project, inputs.subList(firstJar, inputs.size))
    }

    private fun captureSegment(project: Path, inputs: List<CaptureInput>): List<InputFingerprint> {
        val decisions = inputs.map(::decideSpool)
        val requests = inputs.zip(decisions).map { (input, decision) ->
            ObservationRequest(input.path, input.role, input.externalSlot, decision.retainedMaximum, spoolDirectory)
        }
        val observed = ContentFingerprint.captureObservedAll(project, requests, ::digestJobs)
        observed.forEachIndexed { index, captured ->
            val decision = decisions[index]
            // 예약은 크기 기준이므로 실제 spool 크기로 정산한다. 같은 묶음 안의 결정은 이미 끝나 영향을 받지 않는다.
            remainingSpoolBytes += decision.reservedBytes - (captured.spool?.byteSize ?: 0)
            if (phase == Phase.BEFORE_CAPTURE) {
                initialInputs += ObservedInput(
                    inputs[index].role,
                    captured.realPath,
                    captured.rawFileSha256,
                    captured.spool,
                    decision.captureSpool,
                )
            }
        }
        return observed.map { it.fingerprint }
    }

    /** spool 여부와 상한을 입력 순서대로 정하고, 예상 크기만큼 예산을 먼저 차감한다. */
    private fun decideSpool(input: CaptureInput): SpoolDecision {
        val captureSpool = isEligibleJar(input) && captureColdSpools() && isCacheableJarSize(input.path)
        val retainedMaximum = if (captureSpool) minOf(MAX_RETAINED_JAR_BYTES, remainingSpoolBytes) else 0
        if (captureSpool) eligibleJars++
        val reserved = if (retainedMaximum > 0) expectedSpoolBytes(input.path, retainedMaximum) else 0
        remainingSpoolBytes -= reserved
        return SpoolDecision(captureSpool, retainedMaximum, reserved)
    }

    private fun isEligibleJar(input: CaptureInput): Boolean = phase == Phase.BEFORE_CAPTURE && input.role == "classpath" &&
        input.path.fileName.toString().endsWith(".jar", ignoreCase = true) && Files.isRegularFile(input.path, NOFOLLOW_LINKS)

    /** 실제 spool과 같은 규칙(크기가 상한 이내일 때만)으로 예약량을 정한다. 크기를 못 읽으면 예약하지 않고 실제 관측에 맡긴다. */
    private fun expectedSpoolBytes(path: Path, maximum: Long): Long = try {
        Files.size(path).takeIf { it in 0..maximum } ?: 0
    } catch (_: IOException) {
        0
    } catch (_: SecurityException) {
        0
    } catch (_: UnsupportedOperationException) {
        0
    }

    /**
     * 큰 독립 파일(JAR)부터 배정해 마지막에 긴 작업 하나만 남는 꼬리를 줄인다. 결과는 요청 순서로 되돌린다.
     * 디렉터리 멤버는 크기를 재지 않는다. 수백 개 class 파일에 stat을 더하면 이득보다 비용이 컸다(cross-check 기록 참고).
     */
    private fun digestJobs(
        jobs: List<FileDigestJob>,
        digest: (FileDigestJob) -> Result<FileObservation>,
    ): List<Result<FileObservation>> {
        val order = if (jobs.none(FileDigestJob::standalone)) jobs.indices.toList()
            else jobs.indices.sortedByDescending { index -> if (jobs[index].standalone) sizeHint(jobs[index].path) else 0L }
        val mapped = digestWorkers.map(order.map(jobs::get), chunkSize = Int.MAX_VALUE, digest)
        val results = arrayOfNulls<Result<FileObservation>>(jobs.size)
        order.forEachIndexed { position, index -> results[index] = mapped[position] }
        return results.map(::requireNotNull)
    }

    private fun sizeHint(path: Path): Long = try {
        Files.size(path)
    } catch (_: IOException) {
        0
    } catch (_: SecurityException) {
        0
    } catch (_: UnsupportedOperationException) {
        0
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
        digestWorkers.close()
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

    /** 입력 하나의 spool 결정: 대상 여부, spool 상한, 미리 차감한 예산이다. */
    private class SpoolDecision(val captureSpool: Boolean, val retainedMaximum: Long, val reservedBytes: Long)

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

/** [VerifiedCaptureScope.captureAll]에 넘기는 입력 하나: 경로, provenance 역할, 외부 입력 slot 이름이다. */
public data class CaptureInput(val path: Path, val role: String, val externalSlot: String)

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
