package dev.kartograph.index

import java.util.concurrent.Callable
import java.util.concurrent.Future

/** 캐시 없이 순서대로 읽되 byte/count가 제한된 작업 창에서 class를 파싱한다. */
internal class UncachedClassBatchLoader(
    private val parse: (ByteArray) -> ClassFacts,
    private val maximumBatchBytes: Long = 4L * 1024 * 1024,
    private val maximumBatchSize: Int = 16,
) : AutoCloseable {
    private val workers = IndexWorkPool()

    init { require(maximumBatchBytes > 0 && maximumBatchSize > 0) }

    private data class Pending(val index: Int, val bytes: Int)
    private data class Failure(val index: Int, val error: Throwable)

    fun load(inputs: List<() -> ClassInput>): LoadedClassBatch {
        if (inputs.isEmpty()) return LoadedClassBatch(emptyList(), IndexingStatistics())
        val started = System.nanoTime()
        val result = arrayOfNulls<ClassFacts>(inputs.size)
        val completions = workers.completions<ClassFacts>()
        val pending = linkedMapOf<Future<ClassFacts>, Pending>()
        var pendingBytes = 0L
        var readNanos = 0L
        var failure: Failure? = null
        fun fail(error: Throwable): Nothing {
            // 원래 입력 순서의 실패를 선택한 뒤 뒤 작업을 취소·정리한다. 정리 미완료도 원인에 남긴다.
            try { workers.close() } catch (cleanup: RuntimeException) { error.addSuppressed(cleanup) }
            throw error
        }
        fun collect(future: Future<ClassFacts>) {
            val slot = requireNotNull(pending.remove(future))
            pendingBytes -= slot.bytes
            try { result[slot.index] = workers.await(future) } catch (error: Throwable) {
                if (Thread.currentThread().isInterrupted) throw error
                if (failure == null || slot.index < failure!!.index) failure = Failure(slot.index, error)
            }
        }
        fun propagateFailure(): Nothing {
            val firstKnown = requireNotNull(failure)
            // 완료 순서로 첫 실패를 정하지 않는다. 앞 입력의 성공/실패부터 확인하고 더 뒤 작업은 취소한다.
            val earlier = pending.entries.filter { it.value.index < firstKnown.index }.map { it.key to it.value.index }
            for ((future, index) in earlier) {
                if (index >= failure!!.index) break
                collect(future)
            }
            fail(requireNotNull(failure).error)
        }
        fun collectOne() {
            collect(workers.take(completions))
            if (failure != null) propagateFailure()
        }
        fun collectReady() {
            while (true) collect(completions.poll() ?: break)
            if (failure != null) propagateFailure()
        }
        try {
            for (index in inputs.indices) {
                collectReady()
                // count 또는 단독 oversize 입력이 가득 차면 lookahead도 읽기 전에 빈자리를 만든다.
                while (pending.size >= maximumBatchSize || pendingBytes >= maximumBatchBytes) collectOne()
                val readStarted = System.nanoTime()
                val input = try { inputs[index]() } catch (error: Exception) {
                    failure = Failure(index, error)
                    propagateFailure()
                }
                readNanos += System.nanoTime() - readStarted
                // 크기를 알기 위한 lookahead 하나 외의 미완료 byte는 같은 예산 이하다. 큰 class는 단독 허용한다.
                while (pending.isNotEmpty() && input.bytes.size > maximumBatchBytes - pendingBytes) collectOne()
                val future = completions.submit(Callable {
                    parse(input.bytes).let { facts -> facts.copy(runtime = facts.runtime.copy(modified = input.modified)) }
                })
                pending[future] = Pending(index, input.bytes.size)
                pendingBytes += input.bytes.size
            }
            while (pending.isNotEmpty()) collectOne()
        } catch (error: Throwable) { fail(error) }
        // read와 겹친 worker CPU를 더하지 않는다. 나머지 caller wall(제출·입장/완료 대기)이 parse 구간이다.
        val parseNanos = System.nanoTime() - started - readNanos
        return LoadedClassBatch(result.map { requireNotNull(it) }, IndexingStatistics(
            classFiles = inputs.size, parsedClasses = inputs.size,
            readNanos = readNanos, parseNanos = parseNanos,
        ))
    }

    override fun close() { workers.close() }
}
