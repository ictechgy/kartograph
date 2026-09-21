package dev.kartograph.gradle

import dev.kartograph.export.ProcessorOutputCodec
import dev.kartograph.index.ProcessorOutputVerifier
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

/** task 실행 시에만 선택 설정과 receipt를 읽는다. compiler 성공 증거를 대체하지 않는다. */
internal class ProcessorOutputInputs(private val project: Path, paths: List<Path>) {
    private var capturedCharacters = 0L
    private val selectedPaths = ProcessorOutputVerifier.configurationPaths(paths)
    private val entries = selectedPaths.map { path ->
        val text = read(path, 1024 * 1024)
        val config = ProcessorOutputCodec.configuration(text)
        ProcessorOutputVerifier.trackedFiles(project, config)
        val receiptText = read(project.resolve(config.receipt), 32 * 1024 * 1024)
        capturedCharacters += text.length.toLong() + receiptText.length
        require(capturedCharacters <= 64L * 1024 * 1024) { "processor evidence exceeds total limit" }
        Entry(path, text, config, receiptText, ProcessorOutputCodec.receipt(receiptText))
    }
    val files: List<Pair<String, Path>> = selectedPaths.map { "processorConfig" to it } + entries.flatMap { entry ->
        ProcessorOutputVerifier.trackedFiles(project, entry.config, entry.receipt).map { "processorEvidence" to it } +
            dev.kartograph.index.ProcessorCompilerInputVerifier.trackedFiles(project, entry.config)
    }
    fun verify(scope: String) = entries.map { entry ->
        require(read(entry.path, 1024 * 1024) == entry.text && read(project.resolve(entry.config.receipt), 32 * 1024 * 1024) == entry.receiptText) { "processor configuration or receipt changed during capture" }
        ProcessorOutputVerifier.verify(project, scope, entry.config, entry.receipt)
    }
    private fun read(path: Path, maximum: Int): String = try {
        require(Files.isRegularFile(path) && Files.size(path) <= maximum) { "processor evidence is unavailable or too large" }
        val bytes = Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(maximum + 1) }
        require(bytes.size <= maximum) { "processor evidence is too large" }
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.io.IOException) { throw IllegalArgumentException("processor evidence cannot be read; supply accessible UTF-8 inputs") }
    private data class Entry(val path: Path, val text: String, val config: dev.kartograph.core.ProcessorOutputConfiguration,
        val receiptText: String, val receipt: dev.kartograph.core.ProcessorOutputReceipt)
}
