package dev.kartograph.index

import dev.kartograph.core.AndroidResourceEvidence
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class AndroidResourceCaptureTest {
    @Test
    fun ordinaryUntrustedCallsDoNotTouchPaths(@TempDir directory: Path) {
        val result = AndroidResourceCapture.capture(directory.resolve("absent"), "app:debug", NAMESPACE, listOf("/outside"))
        unavailable(result, AndroidResourceCaptureUnavailable.UNTRUSTED_CONTEXT)
    }

    @Test
    fun emptyMissingNestedBinaryXmlAndNonBmpNamesMatchLegacyDigests(@TempDir directory: Path) {
        val project = supportedProject(directory)
        Files.createDirectories(project.resolve("empty"))
        write(project, "res/raw/nested/data.bin", byteArrayOf(0, -1, 8))
        write(project, "res/raw/\uD800\uDC00.bin", byteArrayOf(1))
        write(project, "res/raw/\uE000.bin", byteArrayOf(2))
        write(project, "res/layout/screen.xml", XML)
        write(project, "res/layout-land/screen.xml", "<a/>".toByteArray())
        write(project, "res/values/strings.xml", "<resources/>".toByteArray())
        Files.createDirectories(project.resolve("res/raw/empty"))
        val captured = evidence(capture(project, listOf("missing", "empty", "res")))
        assertEquals("unverified", captured.admission)
        assertEquals(3, captured.roots.size)
        for (root in captured.roots) {
            assertEquals(ContentFingerprint.hashInput(project.resolve(root.input.path), "android-resources"), root.input.sha256)
        }
        assertEquals(captured.roots.first { it.input.path == "empty" }.input.sha256,
            captured.roots.first { it.input.path == "missing" }.input.sha256)
        assertEquals(listOf("", "land"), captured.definitions.map { it.qualifier }.sorted())
        assertEquals("child", captured.includes.single().target!!.name)
        assertEquals(sha(XML), captured.definitions.first { it.qualifier.isEmpty() }.file.sha256)
        assertEquals(captured, evidence(capture(project, listOf("res", "empty", "missing"))))
    }

    @Test
    fun explicitlyCapturedEmptyRootsDifferFromUnavailability(@TempDir directory: Path) {
        val project = supportedProject(directory)
        val captured = evidence(capture(project, emptyList()))
        assertTrue(captured.roots.isEmpty())
        assertTrue(captured.definitions.isEmpty())
    }

    @Test
    fun missingToPresentEmptyIsDetectedDespiteEqualProducerFingerprints(@TempDir directory: Path) {
        val project = supportedProject(directory)
        val before = ContentFingerprint.hashInput(project.resolve("res"), "android-resources")
        val result = capture(project, hooks = CaptureHooks(afterFirstObservation = {
            Files.createDirectory(project.resolve("res"))
        }))
        assertEquals(before, ContentFingerprint.hashInput(project.resolve("res"), "android-resources"))
        unavailable(result, AndroidResourceCaptureUnavailable.INPUT_CHANGED)
    }

    @Test
    fun byteAndDirectoryMembershipChangesRejectTheWholeAttempt(@TempDir directory: Path) {
        val project = supportedProject(directory)
        write(project, "res/layout/screen.xml", XML)
        unavailable(capture(project, hooks = CaptureHooks(afterFirstObservation = {
            write(project, "res/layout/screen.xml", "<a/>".toByteArray())
        })), AndroidResourceCaptureUnavailable.INPUT_CHANGED)
        val before = ContentFingerprint.hashInput(project.resolve("res"), "android-resources")
        unavailable(capture(project, hooks = CaptureHooks(afterFirstObservation = {
            Files.createDirectory(project.resolve("res/new_empty_directory"))
        })), AndroidResourceCaptureUnavailable.INPUT_CHANGED)
        assertEquals(before, ContentFingerprint.hashInput(project.resolve("res"), "android-resources"))
        unavailable(capture(project, hooks = CaptureHooks(afterFirstObservation = {
            write(project, "res/new.bin", byteArrayOf(7))
        })), AndroidResourceCaptureUnavailable.INPUT_CHANGED)
    }

    @Test
    fun parserConsumesRetainedHashedBytesWithoutReopening(@TempDir directory: Path) {
        val project = supportedProject(directory)
        val original = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><a><!--한글--><include layout=\"@layout/child\"/></a>"
            .toByteArray(Charsets.UTF_16)
        write(project, "res/layout/screen.xml", original)
        val rootHash = ContentFingerprint.hashInput(project.resolve("res"), "android-resources")
        val result = evidence(capture(project, hooks = CaptureHooks(beforeParse = {
            write(project, "res/layout/screen.xml", "<broken".toByteArray())
        })))
        assertEquals(rootHash, result.roots.single().input.sha256)
        assertEquals(sha(original), result.definitions.single().file.sha256)
        assertEquals("child", result.includes.single().target!!.name)
        assertNotEquals(rootHash, ContentFingerprint.hashInput(project.resolve("res"), "android-resources"))
    }

    @Test
    fun projectAncestorProjectRootDeclaredRootAndMemberSymlinksAreRefused(@TempDir directory: Path) {
        val project = supportedProject(directory)
        Files.createDirectories(project.resolve("actual/project/res"))
        Files.createSymbolicLink(project.resolve("ancestor"), Path.of("actual"))
        unavailable(capture(project.resolve("ancestor/project")), AndroidResourceCaptureUnavailable.UNSAFE_ENTRY)
        Files.createSymbolicLink(project.resolve("project_link"), Path.of("actual/project"))
        unavailable(capture(project.resolve("project_link")), AndroidResourceCaptureUnavailable.UNSAFE_ENTRY)
        Files.createSymbolicLink(project.resolve("res"), Path.of("actual/project/res"))
        unavailable(capture(project), AndroidResourceCaptureUnavailable.UNSAFE_ENTRY)
        Files.delete(project.resolve("res"))
        Files.createDirectory(project.resolve("res"))
        write(project, "target.bin", byteArrayOf(1))
        Files.createSymbolicLink(project.resolve("res/file_link"), Path.of("../target.bin"))
        unavailable(capture(project), AndroidResourceCaptureUnavailable.UNSAFE_ENTRY)
        Files.delete(project.resolve("res/file_link"))
        Files.createSymbolicLink(project.resolve("res/directory_link"), Path.of("../actual/project/res"))
        unavailable(capture(project), AndroidResourceCaptureUnavailable.UNSAFE_ENTRY)
        Files.delete(project.resolve("res/directory_link"))
        Files.createSymbolicLink(project.resolve("res/dangling"), Path.of("missing"))
        unavailable(capture(project), AndroidResourceCaptureUnavailable.UNSAFE_ENTRY)
    }

    @Test
    fun declaredRootAncestorSymlinkCannotHideMissingDescendant(@TempDir directory: Path) {
        val project = supportedProject(directory)
        Files.createDirectories(project.resolve("actual"))
        Files.createSymbolicLink(project.resolve("src"), Path.of("actual"))
        unavailable(capture(project, listOf("src/main/res")), AndroidResourceCaptureUnavailable.UNSAFE_ENTRY)
    }

    @Test
    fun unsupportedZipProviderHasNoPathnameFallback(@TempDir directory: Path) {
        FileSystems.newFileSystem(directory.resolve("inputs.zip"), mapOf("create" to "true")).use { zip ->
            val project = zip.getPath("/")
            Files.createDirectory(project.resolve("res"))
            unavailable(capture(project), AndroidResourceCaptureUnavailable.UNSUPPORTED_PROVIDER)
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun linuxObservedUnixSocketIsRejectedWithoutBlockingRead(@TempDir directory: Path) {
        val project = supportedProject(directory)
        Files.createDirectory(project.resolve("res"))
        val socket = project.resolve("res/input.socket")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(socket))
            unavailable(capture(project), AndroidResourceCaptureUnavailable.UNSAFE_ENTRY)
        }
        Files.delete(socket)
    }

    @Test
    fun regularFileAtDeclaredRootOrAncestorIsUnavailable(@TempDir directory: Path) {
        val project = supportedProject(directory)
        write(project, "res", byteArrayOf(1))
        unavailable(capture(project), AndroidResourceCaptureUnavailable.UNSAFE_ENTRY)
        unavailable(capture(project, listOf("res/nested")), AndroidResourceCaptureUnavailable.UNSAFE_ENTRY)
    }

    @Test
    fun invalidExternalDuplicateAndInterleavedOverlappingRootsAreUnavailable(@TempDir directory: Path) {
        val project = supportedProject(directory)
        for (roots in listOf(listOf(""), listOf("."), listOf("../res"), listOf("a//b"), listOf("a/./b"),
            listOf("res", "res"), listOf("a", "a-b", "a/c"), listOf("C:\\res"))) {
            unavailable(capture(project, roots), AndroidResourceCaptureUnavailable.INVALID_REQUEST)
        }
        for (root in listOf("/outside", "external/slot", "external")) {
            unavailable(capture(project, listOf(root)), AndroidResourceCaptureUnavailable.EXTERNAL_ROOT)
        }
        unavailable(capture(Path.of("relative")), AndroidResourceCaptureUnavailable.INVALID_REQUEST)
        unavailable(capture(project.resolve("x/../res")), AndroidResourceCaptureUnavailable.INVALID_REQUEST)
        unavailable(AndroidResourceCapture.capture(project, "invalid scope", NAMESPACE, listOf("res"), TRUSTED),
            AndroidResourceCaptureUnavailable.INVALID_REQUEST)
        unavailable(AndroidResourceCapture.capture(project, "app:debug", "invalid namespace", listOf("res"), TRUSTED),
            AndroidResourceCaptureUnavailable.INVALID_REQUEST)
        unavailable(capture(project.resolve("missing")), AndroidResourceCaptureUnavailable.IO_FAILURE)
    }

    @Test
    fun entryBudgetCountsRootsDirectoriesAndBothObservationsAtBoundary(@TempDir directory: Path) {
        val project = supportedProject(directory)
        write(project, "res/raw/a.bin", byteArrayOf(1))
        evidence(capture(project, limits = AndroidResourceCaptureLimits(entries = 6)))
        unavailable(capture(project, limits = AndroidResourceCaptureLimits(entries = 5)),
            AndroidResourceCaptureUnavailable.LIMIT_EXCEEDED)
        evidence(capture(project, listOf("res"), AndroidResourceCaptureLimits(roots = 1)))
        unavailable(capture(project, listOf("res", "missing"), AndroidResourceCaptureLimits(roots = 1)),
            AndroidResourceCaptureUnavailable.LIMIT_EXCEEDED)
    }

    @Test
    fun depthBudgetAcceptsBoundaryAndRefusesOneMoreSegment(@TempDir directory: Path) {
        val project = supportedProject(directory)
        write(project, "res/a/b.bin", byteArrayOf(1))
        evidence(capture(project, limits = AndroidResourceCaptureLimits(depth = 2)))
        unavailable(capture(project, limits = AndroidResourceCaptureLimits(depth = 1)),
            AndroidResourceCaptureUnavailable.LIMIT_EXCEEDED)
    }

    @Test
    fun streamingBudgetCountsAllFilesAndBothObservations(@TempDir directory: Path) {
        val project = supportedProject(directory)
        write(project, "res/a.bin", byteArrayOf(1, 2, 3))
        write(project, "res/b.bin", byteArrayOf(4))
        evidence(capture(project, limits = AndroidResourceCaptureLimits(streamedBytes = 8)))
        unavailable(capture(project, limits = AndroidResourceCaptureLimits(streamedBytes = 7)),
            AndroidResourceCaptureUnavailable.LIMIT_EXCEEDED)
        unavailable(capture(project, limits = AndroidResourceCaptureLimits(streamedBytes = 1)),
            AndroidResourceCaptureUnavailable.LIMIT_EXCEEDED)
    }

    @Test
    fun retainedAggregateAndPerXmlBudgetsAcceptEqualityAndRejectOneBeyond(@TempDir directory: Path) {
        val project = supportedProject(directory)
        val xml = "<a/>".toByteArray()
        write(project, "res/layout/a.xml", xml)
        write(project, "res/layout/b.xml", xml)
        evidence(capture(project, limits = AndroidResourceCaptureLimits(retainedXmlBytes = 8, xmlBytes = 4)))
        unavailable(capture(project, limits = AndroidResourceCaptureLimits(retainedXmlBytes = 7)),
            AndroidResourceCaptureUnavailable.LIMIT_EXCEEDED)
        unavailable(capture(project, limits = AndroidResourceCaptureLimits(xmlBytes = 3)),
            AndroidResourceCaptureUnavailable.LIMIT_EXCEEDED)
    }

    @Test
    fun hardCapsArePublicPositiveAndLowerOnly() {
        val defaults = AndroidResourceCaptureLimits()
        assertEquals(512, defaults.roots)
        assertEquals(100_000, defaults.entries)
        assertEquals(128, defaults.depth)
        assertEquals(256L * 1024 * 1024, defaults.streamedBytes)
        assertEquals(64L * 1024 * 1024, defaults.retainedXmlBytes)
        assertEquals(AndroidLayoutXmlParser.MAX_XML_BYTES, defaults.xmlBytes)
        for (value in listOf(0, -1, 513)) assertFailsWith<IllegalArgumentException> { defaults.copy(roots = value) }
        for (value in listOf(0, -1, 100_001)) assertFailsWith<IllegalArgumentException> { defaults.copy(entries = value) }
        for (value in listOf(0, -1, 129)) assertFailsWith<IllegalArgumentException> { defaults.copy(depth = value) }
        for (value in listOf(0L, -1L, defaults.streamedBytes + 1)) {
            assertFailsWith<IllegalArgumentException> { defaults.copy(streamedBytes = value) }
        }
        for (value in listOf(0L, -1L, defaults.retainedXmlBytes + 1)) {
            assertFailsWith<IllegalArgumentException> { defaults.copy(retainedXmlBytes = value) }
        }
        for (value in listOf(0, -1, defaults.xmlBytes + 1)) {
            assertFailsWith<IllegalArgumentException> { defaults.copy(xmlBytes = value) }
        }
    }

    @Test
    fun parserFailureAndNoncanonicalLayoutNamesReturnNoPartialFacts(@TempDir directory: Path) {
        val project = supportedProject(directory)
        write(project, "res/layout/good.xml", XML)
        val invalid = listOf("<broken", "<!DOCTYPE a><a/>", "<a>".repeat(129) + "</a>".repeat(129))
        for (text in invalid) {
            write(project, "res/layout/bad.xml", text.toByteArray())
            unavailable(capture(project), AndroidResourceCaptureUnavailable.INVALID_CONTENT)
        }
        Files.delete(project.resolve("res/layout/bad.xml"))
        write(project, "res/layout/INVALID.xml", "<a/>".toByteArray())
        unavailable(capture(project), AndroidResourceCaptureUnavailable.INVALID_CONTENT)
        Files.delete(project.resolve("res/layout/INVALID.xml"))
        write(project, "res/layout-/bad.xml", "<a/>".toByteArray())
        unavailable(capture(project), AndroidResourceCaptureUnavailable.INVALID_CONTENT)
    }

    @Test
    fun globalIncludeOverflowRejectsIndependentlyValidXmlParses(@TempDir directory: Path) {
        val project = supportedProject(directory)
        val xml = ("<a>" + "<include/>".repeat(AndroidLayoutXmlParser.MAX_INCLUDES) + "</a>").toByteArray()
        for (index in 0..10) write(project, "res/layout/screen_$index.xml", xml)
        unavailable(capture(project), AndroidResourceCaptureUnavailable.LIMIT_EXCEEDED)
    }

    @Test
    fun interruptedCallerKeepsInterruption(@TempDir directory: Path) {
        val project = supportedProject(directory)
        write(project, "res/a.bin", byteArrayOf(1))
        try {
            Thread.currentThread().interrupt()
            unavailable(capture(project), AndroidResourceCaptureUnavailable.INTERRUPTED)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun noFileCapturesCannotSucceedOnAnInterruptedThread(@TempDir directory: Path) {
        val project = supportedProject(directory)
        Files.createDirectory(project.resolve("empty"))
        for (roots in listOf(emptyList(), listOf("missing"), listOf("empty"))) {
            try {
                Thread.currentThread().interrupt()
                unavailable(capture(project, roots), AndroidResourceCaptureUnavailable.INTERRUPTED)
                assertTrue(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Test
    fun phaseHookInterruptionsCannotSlipThroughEmptyCapture(@TempDir directory: Path) {
        val project = supportedProject(directory)
        val hooks = listOf(
            CaptureHooks(afterFirstObservation = { Thread.currentThread().interrupt() }),
            CaptureHooks(beforeParse = { Thread.currentThread().interrupt() }),
        )
        for (hook in hooks) {
            try {
                unavailable(capture(project, emptyList(), hooks = hook), AndroidResourceCaptureUnavailable.INTERRUPTED)
                assertTrue(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Test
    fun identicalLayoutNamesRemainBoundToNamespaceAndParentScope(@TempDir directory: Path) {
        val project = supportedProject(directory)
        write(project, "res/layout/screen.xml", XML)
        fun scoped(scope: String, namespace: String) = evidence(AndroidResourceCapture.capture(
            project, scope, namespace, listOf("res"), TRUSTED,
        ))
        val first = scoped("first:debug", "dev.first")
        val second = scoped("second:debug", "dev.second")
        assertEquals("screen", first.definitions.single().key.name)
        assertEquals("screen", second.definitions.single().key.name)
        assertNotEquals(first.definitions.single().key, second.definitions.single().key)
        assertNotEquals(first.definitions.single().id, second.definitions.single().id)
        assertEquals("dev.first", first.includes.single().target!!.namespace)
        assertEquals("dev.second", second.includes.single().target!!.namespace)
        val sameNamespaceOtherScope = scoped("other:debug", "dev.first")
        // 로컬 identity는 전역 module identity가 아니다. 같은 키·바이트라도 부모 scope를 버리지 않는다.
        assertEquals(first.definitions.single().id, sameNamespaceOtherScope.definitions.single().id)
        assertNotEquals(first.scope, sameNamespaceOtherScope.scope)
        assertNotEquals(first, sameNamespaceOtherScope)
    }

    private fun supportedProject(directory: Path): Path {
        val project = directory.toRealPath()
        val supported = Files.newDirectoryStream(project.root).use { it is SecureDirectoryStream<*> }
        if (System.getProperty("os.name") == "Linux") {
            assertTrue(supported, "Linux capture tests require SecureDirectoryStream")
        } else {
            assumeTrue(supported, "SecureDirectoryStream capture capability unavailable on this platform")
        }
        return project
    }

    private fun capture(
        project: Path,
        roots: List<String> = listOf("res"),
        limits: AndroidResourceCaptureLimits = AndroidResourceCaptureLimits(),
        hooks: CaptureHooks = CaptureHooks(),
    ): AndroidResourceCaptureResult = AndroidResourceCapture.captureObserved(
        project, "app:debug", NAMESPACE, roots, TRUSTED, limits, hooks,
    )

    private fun evidence(result: AndroidResourceCaptureResult): AndroidResourceEvidence =
        assertIs<AndroidResourceCaptureResult.Captured>(result).evidence

    private fun unavailable(result: AndroidResourceCaptureResult, reason: AndroidResourceCaptureUnavailable) {
        assertEquals(AndroidResourceCaptureResult.Unavailable(reason), result)
    }

    private fun write(project: Path, relative: String, bytes: ByteArray) {
        val path = project.resolve(relative)
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
    }

    private fun sha(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private companion object {
        const val NAMESPACE = "dev.example"
        val TRUSTED = AndroidResourceCaptureContext.TRUSTED_QUIESCENT_BUILD
        val XML = "<a><include layout=\"@layout/child\"/></a>".toByteArray()
    }
}
