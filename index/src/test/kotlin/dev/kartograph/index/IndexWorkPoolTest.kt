package dev.kartograph.index

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class IndexWorkPoolTest {
    @Test
    fun `await preserves caller interruption even for an already completed future`() {
        try {
            IndexWorkPool().use { pool ->
                Thread.currentThread().interrupt()
                val error = assertFailsWith<ClassIndexingException> { pool.await(CompletableFuture.completedFuture(1)) }
                assertIs<InterruptedException>(error.cause)
                assertTrue(Thread.currentThread().isInterrupted)
            }
        } finally { Thread.interrupted() }
    }

    @Test
    fun `interrupted map waits for worker cleanup before returning and restores interrupt`() {
        assumeTrue(Runtime.getRuntime().availableProcessors() > 1)
        val workers = ConcurrentLinkedQueue<Thread>()
        val started = CountDownLatch(2)
        val cleaning = CountDownLatch(2)
        val cleanupFinished = CountDownLatch(2)
        val releaseCleanup = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val interrupted = AtomicBoolean()
        val resourceOpen = AtomicBoolean(true)
        val resourceClosedDuringCleanup = AtomicBoolean()
        val caller = thread(name = "uncached-index-test-caller") {
            val pool = IndexWorkPool()
            try {
                AutoCloseable { resourceOpen.set(false) }.use { pool.map(listOf(0, 1)) {
                    workers += Thread.currentThread()
                    started.countDown()
                    try {
                        CountDownLatch(1).await()
                    } finally {
                        cleaning.countDown()
                        // 취소 시각과 정리 완료 시각을 분리해 archive 수명이 끝나기 전에 기다리는지 검증한다.
                        var released = false
                        while (!released) {
                            try {
                                releaseCleanup.await()
                                released = true
                            } catch (_: InterruptedException) {
                                // shutdownNow의 재취소에도 정리는 끝나야 한다.
                            }
                        }
                        if (!resourceOpen.get()) resourceClosedDuringCleanup.set(true)
                        cleanupFinished.countDown()
                    }
                } }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                returned.countDown()
                pool.close()
                interrupted.set(Thread.currentThread().isInterrupted)
                closed.countDown()
            }
        }
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            caller.interrupt()
            assertTrue(cleaning.await(10, TimeUnit.SECONDS))
            assertFalse(returned.await(100, TimeUnit.MILLISECONDS), "map must keep its input resources alive during cleanup")
            assertFalse(closed.await(100, TimeUnit.MILLISECONDS), "close must await cleanup even with an interrupted caller")
        } finally {
            caller.interrupt()
            releaseCleanup.countDown()
            caller.join(10_000)
        }
        assertFalse(caller.isAlive)
        assertEquals(0L, cleanupFinished.count, "task cleanup completes before the caller returns")
        // Executor의 종료 신호와 마지막 Thread.run 반환 사이의 차이만 기다린다.
        workers.forEach { it.join(10_000) }
        val error = assertIs<ClassIndexingException>(failure.get())
        assertEquals("indexing was interrupted", error.message)
        assertIs<InterruptedException>(error.cause)
        assertTrue(interrupted.get())
        assertFalse(resourceOpen.get())
        assertFalse(resourceClosedDuringCleanup.get(), "input resource outlives canceled workers")
        assertEquals(2, workers.size)
        assertTrue(workers.none(Thread::isAlive), "no workers survive completed cleanup")
    }

    @Test
    fun `uncooperative cleanup times out despite repeated interrupts without hiding original failure`() {
        assumeTrue(Runtime.getRuntime().availableProcessors() > 1)
        val workers = ConcurrentLinkedQueue<Thread>()
        val started = CountDownLatch(2)
        val cleaning = CountDownLatch(2)
        val releaseCleanup = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val interrupted = AtomicBoolean()
        val caller = thread(name = "uncached-index-timeout-caller") {
            try {
                IndexWorkPool().use { pool ->
                    pool.map(listOf(0, 1)) {
                        workers += Thread.currentThread()
                        started.countDown()
                        try {
                            CountDownLatch(1).await()
                        } finally {
                            cleaning.countDown()
                            var released = false
                            while (!released) {
                                try {
                                    releaseCleanup.await()
                                    released = true
                                } catch (_: InterruptedException) {
                                    // 제한 시간을 실제로 지나도록 협조하지 않는 작업을 통제한다.
                                }
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted)
                finished.countDown()
            }
        }
        var interrupter: Thread? = null
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            val beforeInterrupt = System.nanoTime()
            caller.interrupt()
            assertTrue(cleaning.await(10, TimeUnit.SECONDS))
            interrupter = thread(name = "uncached-index-test-interrupter") {
                while (!finished.await(25, TimeUnit.MILLISECONDS)) caller.interrupt()
            }
            // 재차 interrupt될 때 30초를 다시 세면 이 제한 안에 돌아오지 않는다.
            assertTrue(finished.await(35, TimeUnit.SECONDS), "cleanup must retain its original deadline")
            val elapsed = System.nanoTime() - beforeInterrupt
            assertTrue(elapsed >= TimeUnit.SECONDS.toNanos(29), "exercise the real production timeout")
            val error = assertIs<ClassIndexingException>(failure.get())
            assertEquals("indexing was interrupted", error.message)
            assertIs<InterruptedException>(error.cause)
            val cleanup = assertIs<ClassIndexingException>(error.suppressed.single())
            assertEquals("indexing workers did not stop within 30 seconds", cleanup.message)
            assertTrue(interrupted.get())
            assertTrue(workers.all(Thread::isAlive), "timeout reports incomplete cleanup instead of claiming termination")
        } finally {
            caller.interrupt()
            releaseCleanup.countDown()
            caller.join(10_000)
            finished.countDown()
            interrupter?.join(10_000)
            workers.forEach { it.join(10_000) }
        }
        assertFalse(caller.isAlive)
        assertTrue(workers.none(Thread::isAlive))
    }
}
