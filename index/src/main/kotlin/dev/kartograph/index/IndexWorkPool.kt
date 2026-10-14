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
            val failure = ClassIndexingException("indexing was interrupted", error)
            // archive 등의 입력 수명이 끝나기 전에 취소된 읽기 작업의 정리를 기다린다.
            try {
                close()
            } catch (cleanup: RuntimeException) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    /**
     * 취소된 worker가 정리를 마칠 때까지 최대 30초 기다린다. 정상 반환은 종료를 확인한 경우뿐이다.
     * 제한 시간이 지나면 예외를 던지며, 취소에 협조하지 않는 worker는 여전히 실행 중일 수 있다.
     */
    override fun close() {
        val pool = executor ?: return
        executor = null
        pool.shutdownNow()
        // 이미 설정된 interrupt도 기다리는 동안만 비운다. 재차 interrupt되어도 전체 예산을 늘리지 않는다.
        var interrupted = Thread.interrupted()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        try {
            while (!pool.isTerminated) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw ClassIndexingException("indexing workers did not stop within 30 seconds")
                try {
                    pool.awaitTermination(remaining, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
