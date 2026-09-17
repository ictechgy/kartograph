package dev.kartograph.index

import dev.kartograph.core.InputFingerprint
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentLinkedQueue
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
    // worker가 만든 spool의 기록. interrupt로 결과를 받지 못한 묶음의 spool은 close()가 worker 종료 뒤 이 기록으로 닫는다.
    private val spoolRegistry = ConcurrentLinkedQueue<OwnedJarSpool>()

    /** 현재 capture pass에서 파일을 새로 읽으며, action 단계의 오래된 관측 재사용을 막는다. */
    public fun capture(project: Path, path: Path, role: String, externalSlot: String): InputFingerprint =
        captureAll(project, listOf(CaptureInput(path, role, externalSlot))).single()

    /**
     * 여러 입력을 한 번의 capture pass 안에서 관측한다. spool 예산과 입력 기록은 입력 순서대로 호출 스레드에서 정하고,
     * 파일 digest만 제한된 worker에서 병렬로 계산한다. 반환 목록은 [inputs]와 1:1 같은 순서이며,
     * 값은 [capture]를 차례로 부른 것과 같다. capture pass는 호출 스레드 전용이다.
     */
    public fun captureAll(project: Path, inputs: List<CaptureInput>): List<InputFingerprint> {
        check(phase == Phase.BEFORE_CAPTURE || phase == Phase.AFTER_CAPTURE) {
            "verified capture inputs can only be read during capture passes"
        }
        // 입력당 속성은 한 번만 읽고(정규 파일 여부·크기), JAR 판정·예약·스케줄링 힌트에 모두 쓴다.
        val attributes = inputs.map { attributesOf(it.path) }
        // 첫 JAR의 spool 결정은 캐시 준비를 기다리므로, 그 앞의 입력은 먼저 digest해 준비와 겹치게 한다.
        val firstJar = if (phase == Phase.BEFORE_CAPTURE) inputs.indices.indexOfFirst { isEligibleJar(inputs[it], attributes[it]) } else -1
        if (firstJar <= 0) return captureSegment(project, inputs, attributes)
        return captureSegment(project, inputs.subList(0, firstJar), attributes.subList(0, firstJar)) +
            captureSegment(project, inputs.subList(firstJar, inputs.size), attributes.subList(firstJar, inputs.size))
    }

    private fun captureSegment(project: Path, inputs: List<CaptureInput>, attributes: List<BasicFileAttributes?>): List<InputFingerprint> {
        val decisions = inputs.indices.map { reserveSpoolBudget(inputs[it], attributes[it]) }
        val requests = inputs.zip(decisions).map { (input, decision) ->
            ObservationRequest(input.path, input.role, input.externalSlot, decision.retainedMaximum, spoolDirectory, decision.sizeHint)
        }
        val observed = ContentFingerprint.captureObservedAll(project, requests, spoolRegistry, ::digestJobs)
        // 성공한 묶음의 spool은 아래 기록으로 소유권이 넘어가므로 registry는 실패·interrupt 정리용으로만 남긴다.
        spoolRegistry.clear()
        recordSegment(inputs, decisions, observed)
        return observed.map { it.fingerprint }
    }

    /** 예약을 실제 spool 크기로 정산하고, before pass면 관측에 성공한 입력을 기록한다. */
    private fun recordSegment(inputs: List<CaptureInput>, decisions: List<SpoolDecision>, observed: List<CapturedContentFingerprint>) {
        observed.forEachIndexed { index, captured ->
            val decision = decisions[index]
            // 예약은 크기 기준이므로 실제 spool 크기로 정산한다. 같은 묶음 안의 결정은 이미 끝나 영향을 받지 않는다.
            remainingSpoolBytes += decision.reservedBytes - (captured.spool?.byteSize ?: 0)
            if (phase != Phase.BEFORE_CAPTURE) return@forEachIndexed
            // 순차 구현과 같이 관측에 성공한 JAR만 population 대상으로 센다.
            if (decision.captureSpool) eligibleJars++
            initialInputs += ObservedInput(inputs[index].role, captured.realPath, captured.rawFileSha256, captured.spool, decision.captureSpool)
        }
    }

    /**
     * spool 여부와 상한을 입력 순서대로 정하고, 관측한 크기만큼 예산을 먼저 차감한다.
     * 속성을 읽지 못한 JAR은 이 pass의 spool 대상에서 빠진다(fingerprint는 그대로 읽고, header는 직접 읽기로 처리).
     * 그래서 "크기를 모른 채 예약 0으로 spool"하는 경우가 없고, 같은 묶음의 뒤 JAR가 예산을 겹쳐 쓰지 못한다.
     */
    private fun reserveSpoolBudget(input: CaptureInput, attributes: BasicFileAttributes?): SpoolDecision {
        val size = attributes?.takeIf { it.isRegularFile }?.size() ?: 0
        val captureSpool = isEligibleJar(input, attributes) && captureColdSpools() && size <= maximumCacheableJarBytes
        val retainedMaximum = if (captureSpool) minOf(MAX_RETAINED_JAR_BYTES, remainingSpoolBytes) else 0
        val reserved = if (retainedMaximum > 0 && size in 0..retainedMaximum) size else 0
        remainingSpoolBytes -= reserved
        return SpoolDecision(captureSpool, retainedMaximum, reserved, sizeHint = size)
    }

    private fun isEligibleJar(input: CaptureInput, attributes: BasicFileAttributes?): Boolean =
        phase == Phase.BEFORE_CAPTURE && input.role == "classpath" &&
            input.path.fileName.toString().endsWith(".jar", ignoreCase = true) && attributes?.isRegularFile == true

    /** 권고용 속성 관측이다. 실패하면 null을 돌려주고 실제 fingerprint 읽기가 오류를 결정한다. */
    private fun attributesOf(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }

    /**
     * 큰 독립 파일(JAR)부터 배정해 마지막에 긴 작업 하나만 남는 꼬리를 줄인다. 결과는 요청 순서로 되돌린다.
     * 크기는 [reserveSpoolBudget]이 이미 관측한 힌트만 쓰고 여기서 파일 시스템을 읽지 않는다.
     * 디렉터리 멤버는 힌트 0이라 뒤로 간다(수백 개 class 파일에 stat을 더하면 이득보다 비용이 컸다, cross-check 기록 참고).
     */
    private fun digestJobs(
        jobs: List<FileDigestJob>,
        digest: (FileDigestJob) -> Result<FileObservation>,
    ): List<Result<FileObservation>> {
        val order = if (jobs.all { it.sizeHint == 0L }) jobs.indices.toList() else jobs.indices.sortedByDescending { jobs[it].sizeHint }
        val mapped = try {
            digestWorkers.map(order.map(jobs::get), chunkSize = null, digest)
        } catch (error: ClassIndexingException) {
            // pool은 호출 스레드 interrupt만 ClassIndexingException으로 보고한다. 그 경우에만 capture 문맥으로 다시 이름 붙인다.
            if (error.cause is InterruptedException) throw ClassIndexingException("fingerprint capture was interrupted", error.cause)
            throw error
        }
        val results = arrayOfNulls<Result<FileObservation>>(jobs.size)
        order.forEachIndexed { position, index -> results[index] = mapped[position] }
        return results.map(::requireNotNull)
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
        // worker 종료를 기다린 뒤 닫아야 뒤늦게 기록된 spool까지 정리된다.
        digestWorkers.close()
        spoolRegistry.forEach(OwnedJarSpool::close)
        spoolRegistry.clear()
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

    /** 입력 하나의 spool 결정: 대상 여부, spool 상한, 미리 차감한 예산, 스케줄링용 크기 힌트(모르면 0)다. */
    private class SpoolDecision(val captureSpool: Boolean, val retainedMaximum: Long, val reservedBytes: Long, val sizeHint: Long)

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
public class CaptureInput(public val path: Path, public val role: String, public val externalSlot: String)

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
