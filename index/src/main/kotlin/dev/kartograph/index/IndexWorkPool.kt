package dev.kartograph.index

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 독립 입력만 제한된 묶음으로 처리하며 다음 단계에 원래 입력 순서로 넘긴다. */
internal class IndexWorkPool : AutoCloseable {
    private val parallelism = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    private var executor: ExecutorService? = null

    fun <T, R> map(inputs: List<T>, action: (T) -> R): List<R> = map(inputs, 16, action)

    /**
     * [chunkSize]는 동시에 미완료로 둘 작업 수의 상한이다. 묶음마다 모든 작업이 끝나기를 기다리므로 in-flight 결과(예: 파싱한
     * class 바이트)를 제한한다. null이면 묶지 않고 한 번에 제출한다 — 결과가 작은 작업은 그래야 긴 작업 뒤의 유휴가 생기지 않는다.
     */
    fun <T, R> map(inputs: List<T>, chunkSize: Int?, action: (T) -> R): List<R> {
        if (inputs.size < 2 || parallelism == 1) return inputs.map(action)
        // 모든 작업은 invokeAll로 동기 대기하므로 daemon으로 두어도 잃는 결과가 없고, close를 놓친 경로가 JVM 종료를 막지 않는다.
        val pool = executor ?: Executors.newFixedThreadPool(parallelism) { runnable ->
            Thread(runnable, "kartograph-index-worker").apply { isDaemon = true }
        }.also { executor = it }
        return try {
            (if (chunkSize == null) listOf(inputs) else inputs.chunked(chunkSize)).flatMap { batch ->
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

    /** 취소된 worker가 자기 정리를 마칠 때까지 기다린다. 그래야 호출자가 close 직후 잔여물 없음을 믿을 수 있다. */
    override fun close() {
        val pool = executor ?: return
        executor = null
        pool.shutdownNow()
        try {
            pool.awaitTermination(30, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
