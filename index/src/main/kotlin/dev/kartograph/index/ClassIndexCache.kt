package dev.kartograph.index

import dev.kartograph.core.*
import java.io.*
import java.nio.file.*
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.CRC32C
import kotlin.io.path.createDirectories
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.enums.enumEntries
import org.objectweb.asm.*
import org.objectweb.asm.tree.MethodNode

/** ClassFacts의 파싱 결과만 보관하는 선택적 로컬 캐시다. */
public class ClassIndexCache(public val directory: Path) {
    internal constructor(directory: Path, identity: String?) : this(directory) { identityOverride = identity; overrideSet = true }
    private var identityOverride: String? = null
    private var overrideSet = false
    internal val identity: String? by lazy { if (overrideSet) identityOverride else CacheIdentity.current() }
    internal val storage = IndexCacheStorage(directory)
    internal val namespace: String? by lazy { identity?.let { sha256(it).take(16) } }

    internal fun read(contentHash: String): CacheReadResult {
        val engine = identity ?: return CacheReadResult.Unavailable
        return when (val read = storage.read(entry(contentHash), MAX_ENTRY_BYTES)) {
            is CacheBytesResult.Hit -> try { CacheReadResult.Hit(ClassFactsCodec.decode(read.bytes, engine, contentHash)) }
                catch (_: CacheFormatException) { CacheReadResult.Invalid }
            CacheBytesResult.Miss -> CacheReadResult.Miss
            CacheBytesResult.Invalid -> CacheReadResult.Invalid
            CacheBytesResult.Unavailable -> CacheReadResult.Unavailable
        }
    }

    internal fun write(contentHash: String, facts: ClassFacts): CacheWriteResult {
        val engine = identity ?: return CacheWriteResult.Unavailable
        return try {
            val bytes = ClassFactsCodec.encode(facts, engine, contentHash)
            storage.write(entry(contentHash), bytes, MAX_ENTRY_BYTES)
        } catch (_: IOException) { CacheWriteResult.Failed }
        catch (_: SecurityException) { CacheWriteResult.Unavailable }
        catch (_: CacheFormatException) { CacheWriteResult.Failed }
    }

    private fun entry(hash: String): String = "$hash-${requireNotNull(namespace)}.cix"
    internal companion object { const val MAX_ENTRY_BYTES = 8 * 1024 * 1024 }
}

internal sealed interface CacheReadResult {
    data class Hit(val facts: ClassFacts) : CacheReadResult
    data object Miss : CacheReadResult
    data object Invalid : CacheReadResult
    data object Unavailable : CacheReadResult
}
internal enum class CacheWriteResult { Written, Failed, Unavailable }

/** 식별할 수 없는 구현 원천이 하나라도 있으면 캐시 재사용을 끈다. */
internal object CacheIdentity {
    private val types = listOf(
        ClassFileIndexer::class.java, FactsVisitor::class.java, ClassFacts::class.java,
        ClassRuntimeObservation::class.java, ClassFactsCodec::class.java,
        KotlinMetadataEnricher::class.java, RuntimeValueAnalyzer::class.java, JvmNodeId::class.java,
        CodeGraph::class.java, GraphNode::class.java, GraphEdge::class.java, ExternalCall::class.java,
        NodeKind::class.java, NodeAttribute::class.java, JvmModifier::class.java,
        EdgeKind::class.java, EdgeOrigin::class.java, InvocationKind::class.java, CallResolution::class.java,
        ClassReader::class.java, MethodNode::class.java,
        org.objectweb.asm.tree.analysis.Analyzer::class.java, KotlinClassMetadata::class.java, kotlin.Unit::class.java,
    )

    fun current(): String? = try {
        fromArtifacts(types.associate { type ->
            val location = type.protectionDomain?.codeSource?.location ?: return null
            type.name to Path.of(location.toURI())
        })
    } catch (_: java.net.URISyntaxException) { null }
    catch (_: SecurityException) { null }
    catch (_: IllegalArgumentException) { null }

    /** 같은 산출물을 한 번만 읽고, 디렉터리 개발 빌드의 별도 helper 구현도 식별에 포함한다. */
    internal fun fromArtifacts(artifacts: Map<String, Path>): String? = try {
        if (artifacts.isEmpty()) return null
        val hashes = linkedMapOf<Path, String>()
        for (root in artifacts.values.distinct()) {
            val digest = when {
                Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS) -> hashFile(root)
                Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) -> {
                    val files = Files.walk(root).use { stream ->
                        stream.filter { it.fileName.toString().endsWith(".class") }.sorted().toList()
                    }
                    if (files.isEmpty() || files.any { !Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }) return null
                    sha256(files.joinToString("|") { file ->
                        root.relativize(file).toString().replace('\\', '/') + "=" + hashFile(file)
                    })
                }
                else -> return null
            }
            hashes[root] = digest
        }
        val java = System.getProperty("java.specification.version") ?: return null
        sha256("cix-engine-4|$java|" + artifacts.toSortedMap().entries.joinToString("|") { (role, path) ->
            role + "=" + hashes.getValue(path)
        })
    } catch (_: IOException) { null }
    catch (_: SecurityException) { null }
    catch (_: IllegalArgumentException) { null }

    private fun hashFile(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}

internal fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
internal fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))
internal class CacheFormatException(cause: Throwable? = null) : IOException("invalid cache entry", cause)

/** 제한된 ClassFacts 포맷. 선택된 고유 메서드 본문은 ASM classfile 한 벌로 보관한다. */
internal object ClassFactsCodec {
    private const val MAGIC = 0x4b584332
    private const val VERSION = 4
    private const val MAX_METHOD_BYTES = 4 * 1024 * 1024
    private const val MAX_LIST_ITEMS = 1_000_000
    private const val MAX_STRING_BYTES = 65_535

    fun encode(facts: ClassFacts, identity: String, hash: String): ByteArray = formatErrors {
        val payload = BoundedOutput(ClassIndexCache.MAX_ENTRY_BYTES).also { out ->
            FactOutput(out).use { data -> data.writeFacts(facts) }
        }.toByteArray()
        if (payload.size > ClassIndexCache.MAX_ENTRY_BYTES) throw CacheFormatException()
        BoundedOutput(ClassIndexCache.MAX_ENTRY_BYTES).also { out -> DataOutputStream(out).use { data ->
            data.writeInt(MAGIC); data.writeInt(VERSION); data.writeString(identity); data.writeString(hash)
            data.writeInt(payload.size); data.write(payload); data.writeInt(checksum(payload))
        } }.toByteArray()
    }

    fun decode(bytes: ByteArray, identity: String, hash: String): ClassFacts = formatErrors {
        if (bytes.size !in 1..ClassIndexCache.MAX_ENTRY_BYTES) throw CacheFormatException()
        val input = DataInputStream(CacheInput(bytes))
        if (input.readInt() != MAGIC || input.readInt() != VERSION || input.readString() != identity || input.readString() != hash) throw CacheFormatException()
        val payload = ByteArray(input.readCount(ClassIndexCache.MAX_ENTRY_BYTES)); input.readFully(payload)
        if (input.readInt() != checksum(payload) || input.available() != 0) throw CacheFormatException()
        FactInput(CacheInput(payload)).use { data ->
            data.readFacts().also { if (data.available() != 0) throw CacheFormatException() }
        }
    }

    private fun DataOutputStream.writeFacts(f: ClassFacts) {
        writeString(f.internalName); writeNullable(f.enclosingClass); writeObservation(f.runtime)
        writeList(f.nodes) { writeNode(it) }; writeList(f.edges) { writeEdge(it) }; writeList(f.calls) { writeCall(it) }
        writeList(f.fieldWriteMethods) { writeString(it.value) }
        writeList(f.constantStringFields.entries) { writeString(it.key.value); writeString(it.value) }
        writeBodies(f)
    }

    private fun DataInputStream.readFacts(): ClassFacts {
        val name = readString(); val enclosing = readNullable(); val runtime = readObservation()
        val nodes = readList { readNode() }; val edges = readList { readEdge() }; val calls = readList { readCall() }
        val writes = readSet { NodeId(readString()) }; val constants = readMap { NodeId(readString()) to readString() }
        val bodies = readBodies(name)
        return ClassFacts(name, nodes, edges, enclosing, runtime, calls, bodies.runtime, bodies.returns, writes, bodies.fields, constants)
    }

    private fun DataOutputStream.writeBodies(f: ClassFacts) {
        fun key(m: MethodNode) = m.name + m.desc
        val distinct = linkedMapOf<String, MethodNode>()
        (f.runtimeMethods + f.returnMethods + f.fieldMethods).forEach { method ->
            val old = distinct.putIfAbsent(key(method), method)
            if (old != null && old !== method && !singleMethod(old).contentEquals(singleMethod(method))) throw CacheFormatException()
        }
        writeList(distinct.keys) { writeString(it) }
        writeList(f.runtimeMethods) { writeString(key(it)) }; writeList(f.returnMethods) { writeString(key(it)) }; writeList(f.fieldMethods) { writeString(key(it)) }
        if (distinct.isEmpty()) {
            writeInt(0)
            return
        }
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_FINAL, "dev/kartograph/cache/Reduced", null, "java/lang/Object", null)
        distinct.values.forEach { method -> method.accept(writer.visitMethod(method.access, method.name, method.desc, method.signature, method.exceptions?.toTypedArray())) }
        writer.visitEnd()
        val bytes = writer.toByteArray(); if (bytes.size !in 1..MAX_METHOD_BYTES) throw CacheFormatException()
        writeInt(bytes.size); write(bytes)
    }

    private fun DataInputStream.readBodies(owner: String): Bodies {
        if (owner.isBlank()) throw CacheFormatException()
        val declared = readList { readString() }; if (declared.distinct().size != declared.size) throw CacheFormatException()
        val runtime = readList { readString() }; val returns = readList { readString() }; val fields = readList { readString() }
        val bytes = ByteArray(readCount(MAX_METHOD_BYTES))
        if (declared.isEmpty()) {
            if (runtime.isNotEmpty() || returns.isNotEmpty() || fields.isNotEmpty() || bytes.isNotEmpty()) throw CacheFormatException()
            return Bodies(emptyList(), emptyList(), emptyList())
        }
        if (bytes.isEmpty()) throw CacheFormatException()
        readFully(bytes)
        val decoded = linkedMapOf<String, MethodNode>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodNode {
                val key = name + descriptor
                if (key !in declared || key in decoded) throw CacheFormatException()
                return MethodNode(Opcodes.ASM9, access, name, descriptor, signature, exceptions).also { decoded[key] = it }
            }
        }, ClassReader.SKIP_FRAMES)
        if (decoded.keys.toList() != declared) throw CacheFormatException()
        fun select(keys: List<String>) = keys.map { decoded[it] ?: throw CacheFormatException() }
        return Bodies(select(runtime), select(returns), select(fields))
    }

    private fun singleMethod(method: MethodNode): ByteArray {
        val writer = ClassWriter(0); writer.visit(Opcodes.V17, Opcodes.ACC_FINAL, "C", null, "java/lang/Object", null)
        method.accept(writer.visitMethod(method.access, method.name, method.desc, method.signature, method.exceptions?.toTypedArray())); writer.visitEnd()
        return writer.toByteArray()
    }

    private fun DataOutputStream.writeObservation(v: ClassRuntimeObservation) {
        writeNullable(v.sourceFile); writeLong(v.modified.toMillis()); writeInt(v.nativeMethods); writeInt(v.reflectionCalls)
        writeInt(v.dynamicRegistrations); writeInt(v.classLoadingCalls); writeInt(v.reflectiveConstructions); writeInt(v.outsideRuntimeTargets)
        writeInt(v.valueAnalysisLimits); writeInt(v.serviceLoadingCalls); writeInt(v.reflectiveMethods); writeInt(v.reflectiveFields)
        writeInt(v.reflectiveMemberMisses); writeLong(v.modifiedPrecisionMillis)
    }
    private fun DataInputStream.readObservation() = ClassRuntimeObservation(readNullable(), FileTime.fromMillis(readLong()), readInt(), readInt(), readInt(), readInt(), readInt(), readInt(), readInt(), readInt(), readInt(), readInt(), readInt(), readLong())

    private fun DataOutputStream.writeNode(n: GraphNode) {
        writeString(n.id.value); writeString(n.name); writeEnum(n.kind); writeNullable(n.moduleName); writeNullable(n.jvmSignature); writeLocation(n.location)
        writeEnum(n.visibility); writeEnum(n.jvmVisibility); writeList(n.jvmModifiers) { writeEnum(it) }; writeList(n.attributes) { writeEnum(it) }
        writeList(n.annotations) { writeString(it) }; writeList(n.supertypes) { writeString(it) }; writeNullable(n.extensionReceiverType); writeBoolean(n.synthesized)
    }
    private fun DataInputStream.readNode() = GraphNode(NodeId(readString()), readString(), readEnum<NodeKind>(), readNullable(), readNullable(), readLocation(), readEnum<Visibility>(), readEnum<Visibility>(), readList { readEnum<JvmModifier>() }.toSet(), readList { readEnum<NodeAttribute>() }.toSet(), readList { readString() }.toSet(), readList { readString() }.toSet(), readNullable(), readBoolean())
    private fun DataOutputStream.writeEdge(e: GraphEdge) { writeString(e.source.value); writeString(e.target.value); writeEnum(e.kind); writeInt(e.weight); writeEnum(e.origin) }
    private fun DataInputStream.readEdge() = GraphEdge(NodeId(readString()), NodeId(readString()), readEnum<EdgeKind>(), readInt(), readEnum<EdgeOrigin>())
    private fun DataOutputStream.writeCall(c: ExternalCall) { writeString(c.caller.value); writeString(c.owner); writeString(c.name); writeString(c.descriptor); writeEnum(c.kind); writeLocation(c.location); writeInt(c.ordinal); writeList(c.resolvedTargets) { writeString(it.value) }; writeEnum(c.resolution); writeNullable(c.model) }
    private fun DataInputStream.readCall() = ExternalCall(NodeId(readString()), readString(), readString(), readString(), readEnum<InvocationKind>(), readLocation(), readInt(), readList { NodeId(readString()) }, readEnum<CallResolution>(), readNullable())

    private fun DataOutputStream.writeLocation(v: SourceLocation?) { writeBoolean(v != null); if (v != null) { writeString(v.path); writeBoolean(v.line != null); v.line?.let(::writeInt); writeBoolean(v.column != null); v.column?.let(::writeInt) } }
    private fun DataInputStream.readLocation() = if (!readBoolean()) null else SourceLocation(readString(), if (readBoolean()) readInt() else null, if (readBoolean()) readInt() else null)
    private fun DataOutputStream.writeNullable(v: String?) { writeBoolean(v != null); if (v != null) writeString(v) }
    private fun DataInputStream.readNullable() = if (readBoolean()) readString() else null
    private fun DataOutputStream.writeString(value: String) {
        if (value.length > MAX_STRING_BYTES) throw CacheFormatException()
        if (this is FactOutput) {
            strings[value]?.let { writeInt(it); return }
            if (strings.size >= MAX_LIST_ITEMS) throw CacheFormatException()
            writeInt(-1)
            strings[value] = strings.size
        }
        // JVM 문자열의 고립된 surrogate도 보존하는 bounded modified UTF-8이다.
        writeUTF(value)
    }

    private fun DataInputStream.readString(): String {
        if (this !is FactInput) return readUTF()
        val index = readInt()
        if (index >= 0) return strings.getOrNull(index) ?: throw CacheFormatException()
        if (index != -1 || strings.size >= MAX_LIST_ITEMS) throw CacheFormatException()
        return readUTF().also(strings::add)
    }
    private fun <T> DataOutputStream.writeList(v: Collection<T>, writer: DataOutputStream.(T) -> Unit) { if (v.size > MAX_LIST_ITEMS) throw CacheFormatException(); writeInt(v.size); v.forEach { writer(it) } }
    private fun <T> DataInputStream.readList(reader: DataInputStream.() -> T) = List(readCount(MAX_LIST_ITEMS)) { reader() }
    private fun <T> DataInputStream.readSet(reader: DataInputStream.() -> T): Set<T> {
        val values = readList(reader); return values.toSet().also { if (it.size != values.size) throw CacheFormatException() }
    }
    private fun <K, V> DataInputStream.readMap(reader: DataInputStream.() -> Pair<K, V>): Map<K, V> {
        val values = readList(reader); return values.toMap().also { if (it.size != values.size) throw CacheFormatException() }
    }
    private inline fun <reified T : Enum<T>> DataOutputStream.writeEnum(v: T) = writeInt(v.ordinal)
    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T = enumEntries<T>().getOrNull(readInt()) ?: throw CacheFormatException()
    private fun DataInputStream.readCount(max: Int) = readInt().also { if (it !in 0..max) throw CacheFormatException() }
    private inline fun <T> formatErrors(block: () -> T): T = try { block() } catch (e: CacheFormatException) { throw e } catch (e: EOFException) { throw CacheFormatException(e) } catch (e: UTFDataFormatException) { throw CacheFormatException(e) } catch (e: IllegalArgumentException) { throw CacheFormatException(e) } catch (e: IndexOutOfBoundsException) { throw CacheFormatException(e) }
    private data class Bodies(val runtime: List<MethodNode>, val returns: List<MethodNode>, val fields: List<MethodNode>)

    private class FactOutput(output: OutputStream) : DataOutputStream(output) {
        val strings = HashMap<String, Int>()
    }

    private class FactInput(input: InputStream) : DataInputStream(input) {
        val strings = ArrayList<String>()
    }

    private fun checksum(bytes: ByteArray): Int = CRC32C().also { it.update(bytes) }.value.toInt()

    // 각 codec 호출이 소유하며 다른 스레드에 넘기지 않는 입력이다.
    private class CacheInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        override fun read(): Int = if (pos < count) buf[pos++].toInt() and 0xff else -1
    }

    private class BoundedOutput(private val maximum: Int) : ByteArrayOutputStream() {
        override fun write(value: Int) {
            reserve(1)
            buf[count++] = value.toByte()
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.size)
            reserve(length)
            bytes.copyInto(buf, count, offset, offset + length)
            count += length
        }

        private fun reserve(length: Int) {
            if (length > maximum - count) throw CacheFormatException()
            val required = count + length
            if (required > buf.size) buf = buf.copyOf(maxOf(required, minOf(maximum, buf.size * 2)))
        }
    }
}
