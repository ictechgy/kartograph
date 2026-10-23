package dev.kartograph.index

/** 캐시를 만들지 않고 순서대로 읽은 class를 제한된 묶음으로 파싱한다. */
internal class UncachedClassBatchLoader(
    private val parse: (ByteArray) -> ClassFacts,
    private val maximumBatchBytes: Long = 4L * 1024 * 1024,
    private val maximumBatchSize: Int = 16,
) : AutoCloseable {
    private val workers = IndexWorkPool()

    init {
        require(maximumBatchBytes > 0 && maximumBatchSize > 0)
    }

    fun load(inputs: List<() -> ClassInput>): LoadedClassBatch {
        val result = ArrayList<ClassFacts>(inputs.size)
        val pending = ArrayList<ClassInput>()
        var pendingBytes = 0L
        var readNanos = 0L
        var parseNanos = 0L
        fun flush() {
            if (pending.isEmpty()) return
            val started = System.nanoTime()
            // 정상 map은 모든 작업을 기다린 뒤 입력 순서의 첫 실패를 전달한다. 취소 정리는 pool 계약을 따른다.
            result += workers.map(pending, null) { input ->
                parse(input.bytes).let { facts ->
                    facts.copy(runtime = facts.runtime.copy(modified = input.modified))
                }
            }
            parseNanos += System.nanoTime() - started
            pending.clear()
            pendingBytes = 0
        }
        for (read in inputs) {
            val started = System.nanoTime()
            val input = try {
                read()
            } catch (error: Exception) {
                // 뒤 입력의 읽기 실패보다 앞 입력의 파싱 실패가 먼저다. 부분 결과는 반환하지 않는다.
                flush()
                throw error
            }
            readNanos += System.nanoTime() - started
            // 실제 바이트 크기로 입장을 결정한다. lookahead 한 개 외에 대기 바이트는 예산 이하다.
            // 기존에 허용하던 큰 class는 거부하지 않고 혼자 처리한다. 전체 facts/heap 제한은 아니다.
            if (input.bytes.size > maximumBatchBytes - pendingBytes) flush()
            pending += input
            pendingBytes += input.bytes.size
            if (pending.size >= maximumBatchSize || pendingBytes >= maximumBatchBytes) flush()
        }
        flush()
        return LoadedClassBatch(result, IndexingStatistics(
            classFiles = inputs.size, parsedClasses = inputs.size,
            readNanos = readNanos, parseNanos = parseNanos,
        ))
    }

    override fun close() {
        workers.close()
    }
}
