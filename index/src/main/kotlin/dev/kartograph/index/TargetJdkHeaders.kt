package dev.kartograph.index

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** 실행 JVM과 분리한 SDK의 위치만 발견하고, 요청된 class 바이트만 제한 안에서 읽는다. */
internal class TargetJdkHeaders private constructor(
    private val home: Path,
    private val limits: TargetJdkLimits,
    private val locations: Map<String, Path>,
    val fingerprint: String,
    val javaFeature: Int,
) {
    private val packages = locations.keys.mapTo(hashSetOf()) { it.substringBeforeLast('/', "") }

    fun contains(internalName: String): Boolean = internalName in locations

    // javax 전체를 플랫폼으로 간주하면 javax.inject 같은 제삼자 라이브러리를 잘못 거부한다.
    fun isPlatformClass(internalName: String): Boolean = contains(internalName) ||
        internalName.startsWith("java/") || internalName.startsWith("jdk/") ||
        internalName.substringBeforeLast('/', "") in packages

    fun read(internalName: String): ByteArray = indexingErrors {
        val module = locations[internalName]
            ?: fail("target JDK class hierarchy is unavailable; supply the matching complete JDK")
        checkRegular(module, limits.moduleBytes)
        ZipFile(module.toFile()).use { archive ->
            val entry = archive.getEntry("classes/$internalName.class")
                ?: fail("target JDK changed during indexing")
            if (entry.isDirectory || entry.size !in 0..limits.classBytes.toLong()) {
                fail("target JDK class exceeds the size limit")
            }
            val bytes = archive.getInputStream(entry).use { readBounded(it, limits.classBytes) }
            val crc = CRC32().also { it.update(bytes) }.value
            if (entry.size != bytes.size.toLong() || entry.crc != crc) fail("invalid target JDK class entry")
            bytes
        }
    }

    /** 같은 indexer를 재사용해도 내용을 재확인하고, 변경 중인 SDK에 부분 성공을 반환하지 않는다. */
    fun verifyUnchanged() {
        if (capture(home, limits).fingerprint != fingerprint) fail("target JDK changed during indexing")
    }

    internal companion object {
        fun open(home: Path, limits: TargetJdkLimits = TargetJdkLimits()): TargetJdkHeaders = indexingErrors {
            val captured = capture(home, limits)
            val locations = linkedMapOf<String, Path>()
            var totalEntries = 0
            captured.modules.forEach { module ->
                ZipFile(module.toFile()).use { archive ->
                    val entries = archive.entries()
                    var moduleInfoSeen = false
                    while (entries.hasMoreElements()) {
                        if (++totalEntries > limits.entries) fail("target JDK exceeds the archive entry limit")
                        val entry = entries.nextElement()
                        validatePath(entry, limits.entryNameLength)
                        if (entry.isDirectory || !entry.name.startsWith("classes/") || !entry.name.endsWith(".class")) continue
                        val name = entry.name.removePrefix("classes/").removeSuffix(".class")
                        if (name.isEmpty() || name.any { it == '.' || it == ';' || it == '[' }) fail("invalid target JDK class name")
                        // 각 module은 고유한 module-info를 갖지만, 같은 archive 내부의 중복은 손상된 입력이다.
                        if (name == "module-info") {
                            if (moduleInfoSeen) fail("duplicate target JDK class identity")
                            moduleInfoSeen = true
                            continue
                        }
                        if (name in locations) fail("duplicate target JDK class identity")
                        if (locations.size >= limits.indexedNames) fail("target JDK exceeds the class name limit")
                        locations[name] = module
                    }
                }
            }
            if (locations.isEmpty()) fail("target JDK contains no class headers")
            TargetJdkHeaders(home, limits, locations, captured.fingerprint, captured.javaFeature)
        }

        private fun capture(home: Path, limits: TargetJdkLimits): CapturedSdk = indexingErrors {
            val jmods = home.resolve("jmods")
            if (!Files.isDirectory(jmods, NOFOLLOW_LINKS)) fail("target JDK jmods directory is missing or invalid")
            val modules = mutableListOf<Path>()
            Files.newDirectoryStream(jmods).use { entries ->
                entries.forEach { path ->
                    if (modules.size >= limits.modules) fail("target JDK exceeds the module count limit")
                    val name = path.fileName.toString()
                    if (name.length > limits.entryNameLength || !name.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.]*\\.jmod"))) {
                        fail("invalid target JDK module name")
                    }
                    checkRegular(path, limits.moduleBytes)
                    modules.add(path)
                }
            }
            if (modules.isEmpty() || modules.none { it.fileName.toString() == "java.base.jmod" }) {
                fail("target JDK java.base module is missing")
            }
            modules.sortBy { it.fileName.toString() }
            val releaseFile = home.resolve("release")
            checkRegular(releaseFile, limits.releaseBytes.toLong())
            val release = Files.newInputStream(releaseFile, NOFOLLOW_LINKS).use { readBounded(it, limits.releaseBytes) }
            val properties = Properties().apply { release.inputStream().reader(Charsets.UTF_8).use(::load) }
            val version = properties.getProperty("JAVA_VERSION")?.removeSurrounding("\"")
            val feature = version?.substringBefore('.')?.substringBefore('-')?.toIntOrNull()
                ?.takeIf { it in 9..999 } ?: fail("target JDK release metadata is invalid")
            val identities = mutableListOf("target-jdk-headers-1", sha256(release))
            var totalBytes = 0L
            modules.forEach { module ->
                val size = checkRegular(module, limits.moduleBytes)
                if (size > limits.totalModuleBytes - totalBytes) fail("target JDK exceeds the total module size limit")
                totalBytes += size
                identities += module.fileName.toString()
                identities += hashModule(module, limits.moduleBytes, size)
            }
            CapturedSdk(modules, ContentFingerprint.values(identities), feature)
        }

        private fun hashModule(path: Path, maximum: Long, expectedSize: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                val magic = input.readNBytes(4)
                if (!magic.contentEquals(byteArrayOf(0x4a, 0x4d, 1, 0))) fail("invalid target JDK JMOD header")
                digest.update(magic)
                var total = 4L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maximum) fail("target JDK module exceeds the size limit")
                    digest.update(buffer, 0, count)
                }
                if (total != expectedSize) fail("target JDK changed during indexing")
            }
            return HexFormat.of().formatHex(digest.digest())
        }

        private fun checkRegular(path: Path, maximum: Long): Long {
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) fail("target JDK input is missing or not a regular file")
            return Files.size(path).also { if (it !in 0..maximum) fail("target JDK input exceeds the size limit") }
        }

        private fun validatePath(entry: ZipEntry, maximum: Int) {
            val name = entry.name
            if (name.length > maximum || name.isEmpty() || name.startsWith('/') ||
                name.any { it == '\\' || it == ':' || it.code < 32 || it.code == 127 }) {
                fail("invalid target JDK archive path")
            }
            val normalized = if (entry.isDirectory) name.dropLast(1) else name
            if (normalized.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
                fail("invalid target JDK archive path")
            }
        }

        private fun readBounded(input: InputStream, maximum: Int): ByteArray {
            val output = ByteArrayOutputStream(minOf(maximum, 8192))
            val buffer = ByteArray(minOf(maximum, 8192))
            var total = 0
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, maximum - total + 1))
                if (count < 0) return output.toByteArray()
                if (count > maximum - total) fail("target JDK input exceeds the size limit")
                output.write(buffer, 0, count)
                total += count
            }
        }

        private inline fun <T> indexingErrors(action: () -> T): T = try {
            action()
        } catch (error: ClassHierarchyIndexingException) {
            throw error
        } catch (error: java.nio.file.DirectoryIteratorException) {
            throw ClassHierarchyIndexingException("target JDK headers cannot be read", error)
        } catch (error: IOException) {
            throw ClassHierarchyIndexingException("target JDK headers cannot be read", error)
        } catch (error: SecurityException) {
            throw ClassHierarchyIndexingException("target JDK headers cannot be read", error)
        } catch (error: IllegalArgumentException) {
            throw ClassHierarchyIndexingException("invalid target JDK input", error)
        }

        private fun fail(message: String): Nothing = throw ClassHierarchyIndexingException(message)
        private data class CapturedSdk(val modules: List<Path>, val fingerprint: String, val javaFeature: Int)
    }
}

/** 입력의 열거·복사 전에 상한을 적용한다. 작은 상한은 경계 회귀 테스트에도 쓴다. */
internal data class TargetJdkLimits(
    val modules: Int = 128,
    val moduleBytes: Long = 256L * 1024 * 1024,
    val totalModuleBytes: Long = 1024L * 1024 * 1024,
    val entries: Int = 500_000,
    val entryNameLength: Int = 1024,
    val indexedNames: Int = 250_000,
    val classBytes: Int = 4 * 1024 * 1024,
    val releaseBytes: Int = 1024 * 1024,
) {
    init {
        require(modules > 0 && moduleBytes > 0 && totalModuleBytes > 0 && entries > 0 && entryNameLength > 0 &&
            indexedNames > 0 && classBytes in 1 until Int.MAX_VALUE && releaseBytes in 1 until Int.MAX_VALUE)
    }
}
