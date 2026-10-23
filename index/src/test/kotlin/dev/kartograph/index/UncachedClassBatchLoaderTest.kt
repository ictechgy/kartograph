package dev.kartograph.index

import java.io.IOException
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue

class UncachedClassBatchLoaderTest {
    private fun input(id: Int, size: Int = 1) = ClassInput(ByteArray(size) { id.toByte() }, FileTime.fromMillis(id.toLong()))
    private fun facts(bytes: ByteArray) = ClassFacts(bytes.first().toString(), emptyList(), emptyList())

    @Test
    fun `ordered facts timestamps and counters match sequential reads across batches`() {
        val values = (1..39).map(::input)
        val expected = values.map { facts(it.bytes).let { f -> f.copy(runtime = f.runtime.copy(modified = it.modified)) } }
        UncachedClassBatchLoader(::facts).use { loader ->
            val before = System.nanoTime()
            val actual = loader.load(values.map { { it } })
            val elapsed = System.nanoTime() - before
            assertEquals(expected, actual.facts)
            assertEquals(IndexingStatistics(classFiles = 39, parsedClasses = 39),
                actual.statistics.copy(readNanos = 0, parseNanos = 0))
            assertTrue(actual.statistics.readNanos > 0)
            assertTrue(actual.statistics.parseNanos > 0)
            assertTrue(actual.statistics.readNanos + actual.statistics.parseNanos <= elapsed,
                "phase times are nonoverlapping wall intervals, not summed worker CPU")
            assertEquals(expected.take(2), loader.load(values.take(2).map { { it } }).facts)
            assertEquals(LoadedClassBatch(emptyList(), IndexingStatistics()), loader.load(emptyList()))
        }
    }

    @Test
    fun `first parse failure wins even when later failure completes first`() {
        assumeTrue(Runtime.getRuntime().availableProcessors() > 1)
        val second = CountDownLatch(1)
        val firstFailure = IllegalArgumentException("first input")
        val laterFailure = IllegalStateException("second input")
        UncachedClassBatchLoader({ bytes ->
            if (bytes[0] == 1.toByte()) {
                check(second.await(10, TimeUnit.SECONDS))
                throw firstFailure
            }
            second.countDown()
            throw laterFailure
        }).use { loader ->
            assertSame(firstFailure, assertFails { loader.load(listOf({ input(1) }, { input(2) })) })
        }
    }

    @Test
    fun `pending earlier parse failures precede later read failure without returning partial facts`() {
        val readFailure = IOException("later read")
        val parseFailure = IllegalArgumentException("earlier parse")
        UncachedClassBatchLoader({ throw parseFailure }).use { loader ->
            assertSame(parseFailure, assertFails { loader.load(listOf({ input(1) }, { throw readFailure })) })
        }
        UncachedClassBatchLoader(::facts).use { loader ->
            assertSame(readFailure, assertFails { loader.load(listOf({ input(1) }, { throw readFailure })) })
            assertEquals(listOf("2"), loader.load(listOf { input(2) }).facts.map { it.internalName })
        }
    }

    @Test
    fun `actual byte admission flushes before next read and accepts oversize class alone`() {
        val parsed = ConcurrentLinkedQueue<Int>()
        val parse: (ByteArray) -> ClassFacts = { bytes -> parsed.add(bytes[0].toInt()); facts(bytes) }
        UncachedClassBatchLoader(parse, maximumBatchBytes = 5, maximumBatchSize = 3).use { loader ->
            val reads = listOf(
                { input(1, 3) },
                { input(2, 3) }, // 앞 묶음을 비우게 하는 lookahead 한 개.
                { assertEquals(listOf(1), parsed.toList()); input(3, 9) },
                { assertEquals(listOf(1, 2, 3), parsed.toList()); input(4, 1) },
                { input(5, 1) },
                { input(6, 1) },
                { assertEquals(setOf(1, 2, 3, 4, 5, 6), parsed.toSet()); input(7, 1) },
            )
            assertEquals((1..7).map(Int::toString), loader.load(reads).facts.map { it.internalName })
        }
        assertFailsWith<IllegalArgumentException> { UncachedClassBatchLoader(::facts, maximumBatchBytes = 0) }
        assertFailsWith<IllegalArgumentException> { UncachedClassBatchLoader(::facts, maximumBatchSize = 0) }
    }

    @Test
    fun `interruption keeps input resource open through worker cleanup and restores flag`() {
        assumeTrue(Runtime.getRuntime().availableProcessors() > 1)
        val started = CountDownLatch(2)
        val cleaning = CountDownLatch(2)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val open = AtomicBoolean(true)
        val prematureClose = AtomicBoolean()
        val interrupted = AtomicBoolean()
        val failure = AtomicReference<Throwable>()
        val workers = ConcurrentLinkedQueue<Thread>()
        val caller = thread {
            try {
                UncachedClassBatchLoader({ bytes ->
                    workers.add(Thread.currentThread())
                    started.countDown()
                    try { CountDownLatch(1).await() } finally {
                        cleaning.countDown()
                        var released = false
                        while (!released) try { release.await(); released = true } catch (_: InterruptedException) {
                            // 취소를 무시하는 짧은 정리 작업도 resource보다 먼저 끝나는지 검사한다.
                        }
                        if (!open.get()) prematureClose.set(true)
                    }
                    facts(bytes)
                }).use { loader ->
                    AutoCloseable { open.set(false) }.use { loader.load(listOf({ input(1) }, { input(2) })) }
                }
            } catch (error: Throwable) { failure.set(error) } finally {
                interrupted.set(Thread.currentThread().isInterrupted)
                returned.countDown()
            }
        }
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            caller.interrupt()
            assertTrue(cleaning.await(10, TimeUnit.SECONDS))
            assertFalse(returned.await(100, TimeUnit.MILLISECONDS))
        } finally { release.countDown(); caller.join(10_000) }
        assertFalse(caller.isAlive)
        workers.forEach { it.join(10_000) }
        assertTrue(workers.none(Thread::isAlive))
        assertIs<InterruptedException>(assertIs<ClassIndexingException>(failure.get()).cause)
        assertTrue(interrupted.get())
        assertFalse(open.get())
        assertFalse(prematureClose.get())
    }
}
