package dev.kartograph.export

import java.util.AbstractMap.SimpleImmutableEntry

/** 새 wrapper의 필드는 JSON 예산 검사가 다음 원소를 요청할 때만 계산한다. */
internal fun sourceJsonObject(vararg fields: Pair<String, () -> Any?>): Map<String, Any?> {
    val ordered = fields.sortedBy { it.first }
    return object : AbstractMap<String, Any?>() {
        override val entries: Set<Map.Entry<String, Any?>> = object : AbstractSet<Map.Entry<String, Any?>>() {
            override val size: Int get() = ordered.size
            override fun iterator(): Iterator<Map.Entry<String, Any?>> {
                val source = ordered.iterator()
                return object : Iterator<Map.Entry<String, Any?>> {
                    override fun hasNext(): Boolean = source.hasNext()
                    override fun next(): Map.Entry<String, Any?> {
                        val (key, value) = source.next()
                        return SimpleImmutableEntry(key, value())
                    }
                }
            }
        }
    }
}

/** typed 원소를 전부 map으로 복제하지 않고 현재 원소의 descriptor만 만든다. */
internal fun <T> sourceJsonItems(values: Iterable<T>, transform: (T) -> Any?): Iterable<Any?> = Iterable {
    val source = values.iterator()
    object : Iterator<Any?> {
        override fun hasNext(): Boolean = source.hasNext()
        override fun next(): Any? = transform(source.next())
    }
}

/** 순서 있는 두 참조 목록을 원래 목록 복사 없이 이어 쓴다. */
internal fun sourceJsonConcat(first: Iterable<Any?>, second: Iterable<Any?>): Iterable<Any?> = Iterable {
    val left = first.iterator()
    val right = second.iterator()
    object : Iterator<Any?> {
        override fun hasNext(): Boolean = left.hasNext() || right.hasNext()
        override fun next(): Any? = if (left.hasNext()) left.next() else right.next()
    }
}
