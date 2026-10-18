package dev.kartograph.index

import dev.kartograph.core.AndroidLayoutDefinition
import dev.kartograph.core.AndroidLayoutInclude
import dev.kartograph.core.AndroidResourceEvidence
import dev.kartograph.core.AndroidResourceKey
import dev.kartograph.core.AndroidResourceRoot
import dev.kartograph.core.InputFingerprint
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat

/** 호출자가 이미 확보한 신뢰·정지 상태를 명시한다. 이 값 자체가 파일 시스템을 잠그지는 않는다. */
public enum class AndroidResourceCaptureContext { UNTRUSTED_SCAN, TRUSTED_QUIESCENT_BUILD }

/** 선택 채널을 만들 수 없는 이유다. 경로나 원시 입력, 예외 메시지는 노출하지 않는다. */
public enum class AndroidResourceCaptureUnavailable {
    UNTRUSTED_CONTEXT, EXTERNAL_ROOT, INVALID_REQUEST, UNSUPPORTED_PROVIDER,
    UNSAFE_ENTRY, LIMIT_EXCEEDED, INPUT_CHANGED, IO_FAILURE, INTERRUPTED, INVALID_CONTENT,
}

/** 성공해도 producer admission은 unverified다. 실패는 검사한 빈 채널과 구분한다. */
public sealed interface AndroidResourceCaptureResult {
    public data class Captured(val evidence: AndroidResourceEvidence) : AndroidResourceCaptureResult
    public data class Unavailable(val reason: AndroidResourceCaptureUnavailable) : AndroidResourceCaptureResult
}

/** 상한 안에서 더 작은 작업 예산을 선택한다. 초과 시 잘린 결과를 반환하지 않는다. */
public data class AndroidResourceCaptureLimits(
    val roots: Int = 512,
    val entries: Int = 100_000,
    val depth: Int = 128,
    val streamedBytes: Long = 256L * 1024 * 1024,
    val retainedXmlBytes: Long = 64L * 1024 * 1024,
    val xmlBytes: Int = AndroidLayoutXmlParser.MAX_XML_BYTES,
) {
    init {
        require(roots in 1..512 && entries in 1..100_000 && depth in 1..128) { "invalid resource capture count limit" }
        require(streamedBytes in 1..256L * 1024 * 1024 && retainedXmlBytes in 1..64L * 1024 * 1024) {
            "invalid resource capture byte limit"
        }
        require(xmlBytes in 1..AndroidLayoutXmlParser.MAX_XML_BYTES) { "invalid resource capture XML limit" }
    }
}

/**
 * 정지한 신뢰 빌드 입력의 선택적 관측이다. 일반 scanner는 이 adapter를 호출하지 않는다.
 * 두 관측의 일치는 원자적 snapshot, 악의적 파일 시스템의 안전성 또는 실행 시간 상한을 증명하지 않는다.
 * 특히 정규 파일 확인 직후 FIFO로 바뀌면 Java의 open은 막힐 수 있으므로 기존 신뢰 빌드 경계가 필수다.
 */
public object AndroidResourceCapture {
    /** root는 project 기준 정규 상대 경로다. 외부 root와 provider completeness 추정은 지원하지 않는다. */
    public fun capture(
        project: Path,
        scope: String,
        namespace: String,
        declaredRoots: List<String>,
        context: AndroidResourceCaptureContext = AndroidResourceCaptureContext.UNTRUSTED_SCAN,
        limits: AndroidResourceCaptureLimits = AndroidResourceCaptureLimits(),
    ): AndroidResourceCaptureResult = captureObserved(project, scope, namespace, declaredRoots, context, limits, CaptureHooks())

    internal fun captureObserved(
        project: Path,
        scope: String,
        namespace: String,
        declaredRoots: List<String>,
        context: AndroidResourceCaptureContext,
        limits: AndroidResourceCaptureLimits,
        hooks: CaptureHooks,
    ): AndroidResourceCaptureResult {
        if (context != AndroidResourceCaptureContext.TRUSTED_QUIESCENT_BUILD) {
            return AndroidResourceCaptureResult.Unavailable(AndroidResourceCaptureUnavailable.UNTRUSTED_CONTEXT)
        }
        return try {
            checkInterrupted()
            val roots = validate(project, scope, namespace, declaredRoots, limits)
            val budget = Budget()
            val before = observe(project, roots, limits, budget, retain = true)
            checkInterrupted()
            hooks.afterFirstObservation()
            val after = observe(project, roots, limits, budget, retain = false)
            checkInterrupted()
            if (before.roots != after.roots) fail(AndroidResourceCaptureUnavailable.INPUT_CHANGED)
            hooks.beforeParse()
            checkInterrupted()
            val evidenceRoots = before.roots.map { root ->
                AndroidResourceRoot(InputFingerprint("android-resources", root.path, root.sha256))
            }
            val byPath = evidenceRoots.associateBy { it.input.path }
            val definitions = ArrayList<AndroidLayoutDefinition>()
            val includes = ArrayList<AndroidLayoutInclude>()
            for (layout in before.layouts) {
                checkInterrupted()
                val observed = AndroidLayoutXmlParser.parse(
                    AndroidResourceKey(namespace, "layout", layout.name), byPath.getValue(layout.root).id,
                    layout.root + "/" + layout.relativePath, layout.relativePath, layout.qualifier, layout.bytes,
                )
                if (observed.definition.file.sha256 != layout.sha256) fail(AndroidResourceCaptureUnavailable.INPUT_CHANGED)
                if (definitions.size >= AndroidResourceEvidence.MAX_DEFINITIONS ||
                    observed.includes.size > AndroidResourceEvidence.MAX_INCLUDES - includes.size) {
                    fail(AndroidResourceCaptureUnavailable.LIMIT_EXCEEDED)
                }
                definitions += observed.definition
                includes += observed.includes
            }
            val evidence = AndroidResourceEvidence(scope, namespace, evidenceRoots, definitions, includes)
            checkInterrupted()
            AndroidResourceCaptureResult.Captured(evidence)
        } catch (failure: CaptureFailure) {
            AndroidResourceCaptureResult.Unavailable(failure.reason)
        } catch (_: ClosedByInterruptException) {
            Thread.currentThread().interrupt()
            AndroidResourceCaptureResult.Unavailable(AndroidResourceCaptureUnavailable.INTERRUPTED)
        } catch (_: UnsupportedOperationException) {
            AndroidResourceCaptureResult.Unavailable(AndroidResourceCaptureUnavailable.UNSUPPORTED_PROVIDER)
        } catch (_: DirectoryIteratorException) {
            AndroidResourceCaptureResult.Unavailable(AndroidResourceCaptureUnavailable.IO_FAILURE)
        } catch (_: IOException) {
            AndroidResourceCaptureResult.Unavailable(AndroidResourceCaptureUnavailable.IO_FAILURE)
        } catch (_: SecurityException) {
            AndroidResourceCaptureResult.Unavailable(AndroidResourceCaptureUnavailable.IO_FAILURE)
        } catch (_: AndroidResourceScanningException) {
            AndroidResourceCaptureResult.Unavailable(AndroidResourceCaptureUnavailable.INVALID_CONTENT)
        } catch (_: IllegalArgumentException) {
            AndroidResourceCaptureResult.Unavailable(AndroidResourceCaptureUnavailable.INVALID_CONTENT)
        }
    }

    private fun validate(project: Path, scope: String, namespace: String, roots: List<String>, limits: AndroidResourceCaptureLimits): List<String> {
        if (!project.isAbsolute || project != project.normalize() || project.nameCount > 128) {
            fail(AndroidResourceCaptureUnavailable.INVALID_REQUEST)
        }
        if (roots.size > limits.roots) fail(AndroidResourceCaptureUnavailable.LIMIT_EXCEEDED)
        val copied = roots.toList()
        if (copied.any { it.startsWith("/") || it == "external" || it.startsWith("external/") }) {
            fail(AndroidResourceCaptureUnavailable.EXTERNAL_ROOT)
        }
        try {
            AndroidResourceEvidence(scope, namespace, emptyList(), emptyList(), emptyList())
            copied.forEach { root ->
                if (root.split('/').size > 128) fail(AndroidResourceCaptureUnavailable.LIMIT_EXCEEDED)
                AndroidResourceRoot(InputFingerprint("android-resources", root, ZERO_HASH))
                if (project.fileSystem.getPath(root).isAbsolute) fail(AndroidResourceCaptureUnavailable.EXTERNAL_ROOT)
            }
        } catch (_: IllegalArgumentException) {
            fail(AndroidResourceCaptureUnavailable.INVALID_REQUEST)
        }
        val sorted = copied.sorted()
        if (sorted.distinct().size != sorted.size || sorted.any { root ->
                root.indices.any { index -> root[index] == '/' && root.substring(0, index) in sorted }
            }) {
            fail(AndroidResourceCaptureUnavailable.INVALID_REQUEST)
        }
        return sorted
    }

    private fun observe(project: Path, roots: List<String>, limits: AndroidResourceCaptureLimits, budget: Budget, retain: Boolean): Observation {
        checkInterrupted()
        val rootStates = ArrayList<RootState>()
        val layouts = ArrayList<LayoutBytes>()
        fun countEntry(depth: Int) {
            checkInterrupted()
            if (depth > limits.depth || budget.entries >= limits.entries) fail(AndroidResourceCaptureUnavailable.LIMIT_EXCEEDED)
            budget.entries++
        }
        // 절대 project를 먼저 열지 않는다. 파일 시스템 root에서 모든 segment를 descriptor 상대 no-follow로 연다.
        Files.newDirectoryStream(project.root).use { opened ->
            val anchor = opened as? SecureDirectoryStream<Path>
                ?: fail(AndroidResourceCaptureUnavailable.UNSUPPORTED_PROVIDER)
            descend(anchor, project.toList(), 0, missingAllowed = false) { projectDirectory ->
                for (root in roots) {
                    val members = ArrayList<Member>()
                    val present = descend(projectDirectory, project.fileSystem.getPath(root).toList(), 0, missingAllowed = true) { directory ->
                        countEntry(0)
                        fun walk(current: SecureDirectoryStream<Path>, prefix: String, depth: Int) {
                            for (entry in current) {
                                val leaf = entry.fileName
                                val name = leaf.toString()
                                if (leaf.isAbsolute || leaf.nameCount != 1 || name in setOf("", ".", "..") ||
                                    name.any { it == '/' || it == '\\' || it == ':' || it.code < 32 } ||
                                    currentPath(project, name) != leaf) fail(AndroidResourceCaptureUnavailable.UNSAFE_ENTRY)
                                val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                                if (root.length + 1 + relative.length > 4096) fail(AndroidResourceCaptureUnavailable.LIMIT_EXCEEDED)
                                countEntry(depth + 1)
                                val attributes = attributes(current, leaf)
                                when {
                                    attributes.isSymbolicLink -> fail(AndroidResourceCaptureUnavailable.UNSAFE_ENTRY)
                                    attributes.isDirectory -> {
                                        members += Member(relative, true, null)
                                        current.newDirectoryStream(leaf, NOFOLLOW_LINKS).use { nested -> walk(nested, relative, depth + 1) }
                                    }
                                    attributes.isRegularFile -> {
                                        val layout = layoutPath(relative)
                                        val file = readFile(current, leaf, attributes, layout != null, retain, limits, budget)
                                        members += Member(relative, false, file.sha256)
                                        if (layout != null && retain) {
                                            layouts += LayoutBytes(root, relative, layout.first, layout.second,
                                                requireNotNull(file.bytes), file.sha256)
                                        }
                                    }
                                    else -> fail(AndroidResourceCaptureUnavailable.UNSAFE_ENTRY)
                                }
                            }
                        }
                        walk(directory, "", 0)
                    }
                    val sorted = members.sortedBy { it.name }
                    val digest = ContentFingerprint.values(buildList {
                        add("directory")
                        for (member in sorted) if (!member.directory) {
                            add(member.name)
                            add(requireNotNull(member.sha256))
                        }
                    })
                    rootStates += RootState(root, present, sorted, digest)
                }
            }
        }
        return Observation(rootStates, layouts)
    }

    private fun currentPath(project: Path, name: String): Path = project.fileSystem.getPath(name)

    /** 전체 경로 대신 한 segment씩 검사하고 열며, 관측된 부재만 허용한다. */
    private fun descend(
        parent: SecureDirectoryStream<Path>,
        segments: List<Path>,
        index: Int,
        missingAllowed: Boolean,
        action: (SecureDirectoryStream<Path>) -> Unit,
    ): Boolean {
        checkInterrupted()
        if (index == segments.size) {
            action(parent)
            return true
        }
        val name = segments[index]
        val attributes = try {
            attributes(parent, name)
        } catch (failure: NoSuchFileException) {
            if (missingAllowed) return false
            throw failure
        }
        if (attributes.isSymbolicLink || !attributes.isDirectory) fail(AndroidResourceCaptureUnavailable.UNSAFE_ENTRY)
        return parent.newDirectoryStream(name, NOFOLLOW_LINKS).use { child ->
            descend(child, segments, index + 1, missingAllowed, action)
        }
    }

    private fun attributes(directory: SecureDirectoryStream<Path>, name: Path): BasicFileAttributes =
        (directory.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS)
            ?: fail(AndroidResourceCaptureUnavailable.UNSUPPORTED_PROVIDER)).readAttributes()

    /** layout 바로 아래의 XML만 정의로 읽는다. 그 밖의 파일도 root 지문에는 빠짐없이 포함한다. */
    private fun layoutPath(relative: String): Pair<String, String>? {
        val parts = relative.split('/')
        if (parts.size != 2 || !parts[1].endsWith(".xml")) return null
        val directory = parts[0]
        if (directory != "layout" && !directory.startsWith("layout-")) return null
        val qualifier = if (directory == "layout") "" else directory.removePrefix("layout-")
        if (directory != "layout" && qualifier.isEmpty()) fail(AndroidResourceCaptureUnavailable.INVALID_CONTENT)
        val name = parts[1].removeSuffix(".xml")
        if (!Regex("[a-z_][a-z0-9_]{0,127}").matches(name) || qualifier.length > 256 ||
            !Regex("[A-Za-z0-9+_-]*").matches(qualifier)) fail(AndroidResourceCaptureUnavailable.INVALID_CONTENT)
        return qualifier to name
    }

    private fun readFile(
        directory: SecureDirectoryStream<Path>,
        name: Path,
        attributes: BasicFileAttributes,
        xml: Boolean,
        retain: Boolean,
        limits: AndroidResourceCaptureLimits,
        budget: Budget,
    ): FileBytes {
        val maximum = if (xml) limits.xmlBytes.toLong() else Long.MAX_VALUE
        val available = minOf(maximum, limits.streamedBytes - budget.streamed,
            if (xml && retain) limits.retainedXmlBytes - budget.retained else Long.MAX_VALUE)
        if (attributes.size() < 0 || attributes.size() > available) fail(AndroidResourceCaptureUnavailable.LIMIT_EXCEEDED)
        val bytes = if (xml && retain) ByteArrayOutputStream() else null
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        directory.newByteChannel(name, setOf(READ, NOFOLLOW_LINKS)).use { input ->
            val buffer = ByteBuffer.allocate(65536)
            while (true) {
                checkInterrupted()
                val remaining = minOf(maximum - read, limits.streamedBytes - budget.streamed,
                    if (bytes != null) limits.retainedXmlBytes - budget.retained else Long.MAX_VALUE)
                if (remaining == 0L) {
                    // 경계에서는 추가 바이트를 읽지 않고 같은 열린 descriptor의 끝 위치를 확인한다.
                    if (input.position() != input.size()) fail(AndroidResourceCaptureUnavailable.LIMIT_EXCEEDED)
                    break
                }
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) fail(AndroidResourceCaptureUnavailable.IO_FAILURE)
                budget.streamed += count
                read += count
                digest.update(buffer.array(), 0, count)
                if (bytes != null) {
                    budget.retained += count
                    bytes.write(buffer.array(), 0, count)
                }
            }
        }
        return FileBytes(HexFormat.of().formatHex(digest.digest()), bytes?.toByteArray())
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) fail(AndroidResourceCaptureUnavailable.INTERRUPTED)
    }

    private fun fail(reason: AndroidResourceCaptureUnavailable): Nothing = throw CaptureFailure(reason)
    private class CaptureFailure(val reason: AndroidResourceCaptureUnavailable) : RuntimeException()
    private class Budget { var entries = 0; var streamed = 0L; var retained = 0L }
    private data class Member(val name: String, val directory: Boolean, val sha256: String?)
    private data class RootState(val path: String, val present: Boolean, val members: List<Member>, val sha256: String)
    private data class FileBytes(val sha256: String, val bytes: ByteArray?)
    private data class LayoutBytes(val root: String, val relativePath: String, val qualifier: String, val name: String, val bytes: ByteArray, val sha256: String)
    private data class Observation(val roots: List<RootState>, val layouts: List<LayoutBytes>)
    private const val ZERO_HASH = "0000000000000000000000000000000000000000000000000000000000000000"
}

/** 테스트에서 두 관측 사이 또는 byte parser 직전의 변경을 재현한다. 공개 adapter는 hook을 받지 않는다. */
internal class CaptureHooks(val afterFirstObservation: () -> Unit = {}, val beforeParse: () -> Unit = {})
