package dev.kartograph.index

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 독립 입력만 제한된 묶음으로 처리하며 다음 단계에 원래 입력 순서로 넘긴다. */
internal class IndexWorkPool : AutoCloseable {
    private val parallelism = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    private var executor: ExecutorService? = null

    fun <T, R> map(inputs: List<T>, action: (T) -> R): List<R> = map(inputs, 16, action)

    /** [chunkSize]는 한 번에 제출하는 묶음 크기다. 결과가 작은 작업은 큰 묶음으로 넘겨 긴 작업 뒤의 유휴를 줄인다. */
    fun <T, R> map(inputs: List<T>, chunkSize: Int, action: (T) -> R): List<R> {
        if (inputs.size < 2 || parallelism == 1) return inputs.map(action)
        // 모든 작업은 invokeAll로 동기 대기하므로 daemon으로 두어도 잃는 결과가 없고, close를 놓친 경로가 JVM 종료를 막지 않는다.
        val pool = executor ?: Executors.newFixedThreadPool(parallelism) { runnable ->
            Thread(runnable, "kartograph-index-worker").apply { isDaemon = true }
        }.also { executor = it }
        return try {
            inputs.chunked(chunkSize).flatMap { batch ->
                // 모든 작업이 끝난 뒤 반환하므로 phase 시간과 ASM 본문 접근이 겹치지 않는다.
                pool.invokeAll(batch.map { input -> Callable { action(input) } }).map { it.get() }
            }
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ClassIndexingException("indexing was interrupted", error)
        }
    }

    override fun close() {
        executor?.shutdownNow()
        executor = null
    }
}
