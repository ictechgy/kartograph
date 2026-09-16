package dev.kartograph.index

import java.nio.file.attribute.FileTime

internal data class ClassInput(val bytes: ByteArray, val modified: FileTime)
internal data class LoadedClassBatch(val facts: List<ClassFacts>, val statistics: IndexingStatistics)

/** 클래스별 작업만 제한된 묶음으로 실행하고, 각 단계와 입력 순서를 보존한다. */
internal class CachedClassBatchLoader(
    private val cache: ClassIndexCache,
    private val parse: (ByteArray) -> ClassFacts,
) : AutoCloseable {
    private val workers = IndexWorkPool()

    fun load(inputs: List<() -> ClassInput>): LoadedClassBatch {
        var readNanos = 0L
        var cacheReadNanos = 0L
        var parseNanos = 0L
        var writeNanos = 0L
        var hits = 0
        var misses = 0
        var invalid = 0
        var unavailable = 0
        var writeFailures = 0
        val result = ArrayList<ClassFacts>(inputs.size)
        // 구현 원천의 lazy 초기화를 worker와 경합시키지 않는다.
        var started = System.nanoTime()
        cache.identity
        cacheReadNanos += System.nanoTime() - started
        for (batch in inputs.chunked(16)) {
            started = System.nanoTime()
            val observed = workers.map(batch) { read ->
                val input = read()
                HashedClass(input, sha256(input.bytes))
            }
            readNanos += System.nanoTime() - started

            started = System.nanoTime()
            val cached = workers.map(observed) { cache.read(it.sha256) }
            cacheReadNanos += System.nanoTime() - started
            val missing = cached.indices.filter { cached[it] !is CacheReadResult.Hit }
            hits += cached.size - missing.size
            misses += missing.size
            invalid += cached.count { it == CacheReadResult.Invalid }
            unavailable += cached.count { it == CacheReadResult.Unavailable }
            if (missing.isEmpty()) {
                cached.forEachIndexed { index, value ->
                    val facts = (value as CacheReadResult.Hit).facts
                    result += facts.copy(runtime = facts.runtime.copy(modified = observed[index].input.modified))
                }
                continue
            }

            started = System.nanoTime()
            val parsed = workers.map(missing) { index ->
                parse(observed[index].input.bytes).let { facts ->
                    facts.copy(runtime = facts.runtime.copy(modified = observed[index].input.modified))
                }
            }
            parseNanos += System.nanoTime() - started

            started = System.nanoTime()
            val writes = workers.map(missing.indices.toList()) { index -> cache.write(observed[missing[index]].sha256, parsed[index]) }
            writeFailures += writes.count { it == CacheWriteResult.Failed }
            unavailable += writes.indices.count { index ->
                writes[index] == CacheWriteResult.Unavailable && cached[missing[index]] != CacheReadResult.Unavailable
            }
            writeNanos += System.nanoTime() - started

            var nextParsed = 0
            cached.forEachIndexed { index, value ->
                result += if (value is CacheReadResult.Hit) {
                    value.facts.copy(runtime = value.facts.runtime.copy(modified = observed[index].input.modified))
                } else parsed[nextParsed++]
            }
        }
        return LoadedClassBatch(result, IndexingStatistics(
            classFiles = inputs.size, cacheHits = hits, cacheMisses = misses, parsedClasses = misses,
            invalidEntries = invalid, writeFailures = writeFailures, unavailableEntries = unavailable,
            readNanos = readNanos, cacheReadNanos = cacheReadNanos, parseNanos = parseNanos,
            cacheWriteNanos = writeNanos,
        ))
    }

    override fun close() {
        workers.close()
    }

    private data class HashedClass(val input: ClassInput, val sha256: String)
}
