package dev.kartograph.index

import dev.kartograph.core.HierarchyMethod
import dev.kartograph.core.Visibility
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.UTFDataFormatException
import java.util.zip.CRC32C

/** dependency JAR header 캐시의 한 실행 관측값이다. */
internal data class HierarchyIndexingStatistics(
    val hierarchyJars: Int = 0,
    val hierarchyCacheHits: Int = 0,
    val hierarchyParsedJars: Int = 0,
    val hierarchyInvalidEntries: Int = 0,
    val hierarchyWriteFailures: Int = 0,
    val hierarchyUnavailableEntries: Int = 0,
)

/** 공유 저장소 위에 JAR 단위 hierarchy fact 포맷을 제공한다. */
internal class HierarchyIndexCache(private val cache: ClassIndexCache) {
    fun read(jarHash: String): HierarchyCacheReadResult {
        val engine = cache.identity ?: return HierarchyCacheReadResult.Unavailable
        val namespace = cache.namespace ?: return HierarchyCacheReadResult.Unavailable
        return when (val stored = cache.storage.read(entry(jarHash, namespace), MAX_ENTRY_BYTES)) {
            is CacheBytesResult.Hit -> try {
                HierarchyCacheReadResult.Hit(HierarchyFactCodec.decode(stored.bytes, engine, jarHash))
            } catch (_: CacheFormatException) {
                HierarchyCacheReadResult.Invalid
            }
            CacheBytesResult.Miss -> HierarchyCacheReadResult.Miss
            CacheBytesResult.Invalid -> HierarchyCacheReadResult.Invalid
            CacheBytesResult.Unavailable -> HierarchyCacheReadResult.Unavailable
        }
    }

    fun write(jarHash: String, facts: List<HierarchyFact>): CacheWriteResult {
        val engine = cache.identity ?: return CacheWriteResult.Unavailable
        val namespace = cache.namespace ?: return CacheWriteResult.Unavailable
        return try {
            val bytes = HierarchyFactCodec.encode(facts, engine, jarHash)
            cache.storage.write(entry(jarHash, namespace), bytes, MAX_ENTRY_BYTES)
        } catch (_: CacheFormatException) {
            CacheWriteResult.Failed
        } catch (_: IOException) {
            CacheWriteResult.Failed
        } catch (_: SecurityException) {
            CacheWriteResult.Unavailable
        }
    }

    fun isPopulated(): Boolean {
        val engine = cache.identity ?: return false
        val namespace = cache.namespace ?: return false
        val expected = populatedMarker(engine)
        return when (val stored = cache.storage.read(populatedEntry(namespace), MAX_POPULATED_MARKER_BYTES)) {
            is CacheBytesResult.Hit -> stored.bytes.contentEquals(expected)
            CacheBytesResult.Miss, CacheBytesResult.Invalid, CacheBytesResult.Unavailable -> false
        }
    }

    fun markPopulated(): CacheWriteResult {
        val engine = cache.identity ?: return CacheWriteResult.Unavailable
        val namespace = cache.namespace ?: return CacheWriteResult.Unavailable
        return cache.storage.write(
            populatedEntry(namespace),
            populatedMarker(engine),
            MAX_POPULATED_MARKER_BYTES,
        )
    }

    private fun entry(jarHash: String, namespace: String): String {
        if (!jarHash.matches(SHA256)) throw CacheFormatException()
        return "$jarHash-$namespace.hix"
    }

    private fun populatedEntry(namespace: String): String = "hierarchy-$namespace.ready"

    private fun populatedMarker(engine: String): ByteArray =
        sha256("hix-populated-3|$engine").toByteArray(Charsets.US_ASCII)

    internal companion object {
        const val MAX_ENTRY_BYTES: Int = 64 * 1024 * 1024
        private const val MAX_POPULATED_MARKER_BYTES: Int = 128
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal sealed interface HierarchyCacheReadResult {
    data class Hit(val facts: List<HierarchyFact>) : HierarchyCacheReadResult
    data object Miss : HierarchyCacheReadResult
    data object Invalid : HierarchyCacheReadResult
    data object Unavailable : HierarchyCacheReadResult
}

/** bounded tagged binary hierarchy fact 포맷이다. */
private object HierarchyFactCodec {
    private const val MAGIC = 0x4b484958
    private const val VERSION = 3
    private const val MAX_FACTS = 500_000
    private const val MAX_RELATIONS = 100_000
    private const val MAX_METHODS = 100_000
    private const val MAX_STRING_BYTES = 1 * 1024 * 1024
    private const val MAX_IDENTITY_BYTES = 4 * 1024

    fun encode(facts: List<HierarchyFact>, identity: String, jarHash: String): ByteArray = formatErrors {
        if (facts.size > MAX_FACTS || identity.length > MAX_IDENTITY_BYTES) {
            throw CacheFormatException()
        }
        val payload = BoundedOutput(HierarchyIndexCache.MAX_ENTRY_BYTES).also { output ->
            DataOutputStream(output).use { data -> data.writeFacts(facts) }
        }.toByteArray()
        BoundedOutput(HierarchyIndexCache.MAX_ENTRY_BYTES).also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                data.writeString(identity, MAX_IDENTITY_BYTES)
                data.writeString(jarHash, 64)
                data.writeInt(payload.size)
                data.write(payload)
                data.writeInt(checksum(payload))
            }
        }.toByteArray()
    }

    fun decode(bytes: ByteArray, identity: String, jarHash: String): List<HierarchyFact> = formatErrors {
        if (bytes.size !in 1..HierarchyIndexCache.MAX_ENTRY_BYTES) throw CacheFormatException()
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw CacheFormatException()
            if (input.readString(MAX_IDENTITY_BYTES) != identity || input.readString(64) != jarHash) {
                throw CacheFormatException()
            }
            val payload = ByteArray(input.readCount(HierarchyIndexCache.MAX_ENTRY_BYTES))
            input.readFully(payload)
            if (input.readInt() != checksum(payload) || input.available() != 0) throw CacheFormatException()
            DataInputStream(ByteArrayInputStream(payload)).use { factsInput ->
                factsInput.readFacts().also { if (factsInput.available() != 0) throw CacheFormatException() }
            }
        }
    }

    private fun DataOutputStream.writeFacts(facts: List<HierarchyFact>) {
        writeCount(facts.size, MAX_FACTS)
        facts.forEach { fact ->
            writeString(fact.internalName)
            writeStrings(fact.supertypes, MAX_RELATIONS)
            writeCount(fact.methods.size, MAX_METHODS)
            fact.methods.forEach { method ->
                writeString(method.name)
                writeString(method.descriptor)
                writeInt(method.visibility.ordinal)
                writeBoolean(method.isStatic)
                writeBoolean(method.isFinal)
            }
            writeBoolean(fact.isAnnotation)
            writeStrings(fact.annotations, MAX_RELATIONS)
        }
    }

    private fun DataInputStream.readFacts(): List<HierarchyFact> = List(readCount(MAX_FACTS)) {
        val internalName = readString()
        if (internalName.isEmpty()) throw CacheFormatException()
        val supertypes = readStrings(MAX_RELATIONS)
        val methods = List(readCount(MAX_METHODS)) {
            val name = readString()
            val descriptor = readString()
            val visibility = Visibility.entries.getOrNull(readInt()) ?: throw CacheFormatException()
            if (name.isEmpty() || descriptor.isEmpty()) throw CacheFormatException()
            HierarchyMethod(name, descriptor, visibility, readBoolean(), readBoolean())
        }
        val isAnnotation = readBoolean()
        val annotations = readStrings(MAX_RELATIONS)
        if (!isAnnotation && annotations.isNotEmpty()) throw CacheFormatException()
        HierarchyFact(internalName, supertypes, methods, isAnnotation, annotations)
    }

    private fun DataOutputStream.writeStrings(values: Collection<String>, maximum: Int) {
        writeCount(values.size, maximum)
        values.forEach { value -> writeString(value) }
    }

    private fun DataInputStream.readStrings(maximum: Int): Set<String> {
        val values = List(readCount(maximum)) { readString().also { if (it.isEmpty()) throw CacheFormatException() } }
        return values.toCollection(linkedSetOf()).also {
            if (it.size != values.size) throw CacheFormatException()
        }
    }

    private fun DataOutputStream.writeCount(value: Int, maximum: Int) {
        if (value !in 0..maximum) throw CacheFormatException()
        writeInt(value)
    }

    private fun DataInputStream.readCount(maximum: Int): Int = readInt().also {
        if (it !in 0..maximum) throw CacheFormatException()
    }

    private fun DataOutputStream.writeString(value: String, maximum: Int = MAX_STRING_BYTES) {
        if (value.length > maximum) throw CacheFormatException()
        // JVM classfile 문자열의 고립된 surrogate도 그대로 보존한다.
        writeUTF(value)
    }

    private fun DataInputStream.readString(maximum: Int = MAX_STRING_BYTES): String {
        return readUTF().also { if (it.length > maximum) throw CacheFormatException() }
    }

    private fun checksum(bytes: ByteArray): Int = CRC32C().also { it.update(bytes) }.value.toInt()

    private inline fun <T> formatErrors(block: () -> T): T = try {
        block()
    } catch (error: CacheFormatException) {
        throw error
    } catch (error: EOFException) {
        throw CacheFormatException(error)
    } catch (error: UTFDataFormatException) {
        throw CacheFormatException(error)
    } catch (error: IllegalArgumentException) {
        throw CacheFormatException(error)
    } catch (error: IndexOutOfBoundsException) {
        throw CacheFormatException(error)
    }

    private class BoundedOutput(private val maximum: Int) : ByteArrayOutputStream() {
        override fun write(value: Int) {
            if (count >= maximum) throw CacheFormatException()
            super.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length > maximum - count) throw CacheFormatException()
            super.write(bytes, offset, length)
        }
    }
}
