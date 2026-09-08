package dev.kartograph.index

import dev.kartograph.core.ServiceProviderRegistration
import dev.kartograph.core.SourceLocation
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.jar.JarFile

/** 명시된 class/resource root에서 표준 ServiceLoader 등록만 읽는다. 코드를 실행하지 않는다. */
public object ServiceProviderScanner {
    /** 입력 순서와 파일·줄 근거를 보존하고 잘못된 registry는 부분 결과 대신 실패로 처리한다. */
    public fun scan(roots: Iterable<Path>): List<ServiceProviderRegistration> = try {
        roots.flatMapIndexed { index, root ->
            when {
                Files.isDirectory(root) -> scanDirectory(root, index + 1)
                Files.isRegularFile(root) && root.fileName.toString().endsWith(".jar", ignoreCase = true) -> scanJar(root, index + 1)
                else -> throw IOException("service input must be an existing directory or JAR")
            }
        }.distinct().sorted()
    } catch (error: IOException) {
        throw ClassIndexingException("service provider metadata cannot be read or is invalid; check META-INF/services inputs", error)
    }

    private fun scanDirectory(root: Path, ordinal: Int): List<ServiceProviderRegistration> {
        val realRoot = root.toRealPath()
        val directory = realRoot.resolve(PREFIX)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val realDirectory = directory.toRealPath()
        if (!realDirectory.startsWith(realRoot)) throw IOException("service metadata is outside its input root")
        return Files.list(realDirectory).use { files ->
            files.filter { binaryName(it.fileName.toString()) }.sorted().flatMap { file ->
                val realFile = file.toRealPath()
                if (!realFile.startsWith(realRoot)) throw IOException("service metadata is outside its input root")
                if (!Files.isRegularFile(realFile)) return@flatMap java.util.stream.Stream.empty()
                Files.newInputStream(realFile, LinkOption.NOFOLLOW_LINKS).use { input ->
                    parse(file.fileName.toString(), input, ordinal).stream()
                }
            }.toList()
        }
    }

    private fun scanJar(root: Path, ordinal: Int): List<ServiceProviderRegistration> = JarFile(root.toFile(), false).use { archive ->
        archive.entries().asSequence().filter { !it.isDirectory && it.name.startsWith(PREFIX) && binaryName(it.name.removePrefix(PREFIX)) }
            .sortedBy { it.name }.flatMap { entry ->
                archive.getInputStream(entry).use { input -> parse(entry.name.removePrefix(PREFIX), input, ordinal) }
            }.toList()
    }

    private fun parse(service: String, input: InputStream, ordinal: Int): List<ServiceProviderRegistration> {
        if (!binaryName(service)) throw IOException("invalid service name")
        val bytes = input.readNBytes(MAX_BYTES + 1)
        if (bytes.size > MAX_BYTES) throw IOException("service registry exceeds the input limit")
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return text.lineSequence().mapIndexedNotNull { index, raw ->
            val provider = raw.substringBefore('#').trim()
            if (provider.isEmpty()) return@mapIndexedNotNull null
            if (!binaryName(provider)) throw IOException("invalid provider name")
            ServiceProviderRegistration(service.replace('.', '/'), JvmNodeId.classId(provider.replace('.', '/')),
                SourceLocation("input-$ordinal/$PREFIX$service", index + 1))
        }.toList()
    }

    private fun binaryName(name: String): Boolean = name.split('.').all { part ->
        if (part.isEmpty()) return@all false
        val points = part.codePoints().toArray()
        Character.isJavaIdentifierStart(points.first()) && points.drop(1).all(Character::isJavaIdentifierPart)
    }

    private const val PREFIX = "META-INF/services/"
    private const val MAX_BYTES = 1_048_576
}
