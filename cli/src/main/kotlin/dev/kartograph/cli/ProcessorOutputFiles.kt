package dev.kartograph.cli

import dev.kartograph.core.ProcessorOutputs
import dev.kartograph.export.ProcessorOutputCodec
import dev.kartograph.index.ProcessorOutputVerifier
import java.nio.file.Path

/** CLI는 로컬 JSON만 읽고 processor runner 명령을 실행하지 않는다. */
internal class ProcessorOutputFiles(private val project: Path, paths: List<Path>) {
    private var capturedCharacters = 0L
    private val selectedPaths = ProcessorOutputVerifier.configurationPaths(paths)
    private val entries = selectedPaths.map { path ->
        val text = SnapshotFiles.readText(path.toString(), 1024 * 1024)
        val config = ProcessorOutputCodec.configuration(text)
        ProcessorOutputVerifier.trackedFiles(project, config)
        val receiptText = SnapshotFiles.readText(project.resolve(config.receipt).toString(), 32 * 1024 * 1024)
        capturedCharacters += text.length.toLong() + receiptText.length
        require(capturedCharacters <= 64L * 1024 * 1024) { "processor evidence exceeds total limit" }
        Entry(path, text, config, receiptText, ProcessorOutputCodec.receipt(receiptText))
    }
    val trackedFiles: List<Pair<String, Path>>
    init {
        trackedFiles = selectedPaths.map { "processorConfig" to it } + entries.flatMap { entry ->
            ProcessorOutputVerifier.trackedFiles(project, entry.config, entry.receipt).map { "processorEvidence" to it }
        }
    }
    fun verify(scope: String?): List<ProcessorOutputs> = entries.map { entry ->
        require(SnapshotFiles.readText(entry.path.toString(), 1024 * 1024) == entry.text &&
            SnapshotFiles.readText(project.resolve(entry.config.receipt).toString(), 32 * 1024 * 1024) == entry.receiptText) { "processor configuration or receipt changed during capture" }
        ProcessorOutputVerifier.verify(project, requireNotNull(scope), entry.config, entry.receipt)
    }
    private data class Entry(val path: Path, val text: String, val config: dev.kartograph.core.ProcessorOutputConfiguration,
        val receiptText: String, val receipt: dev.kartograph.core.ProcessorOutputReceipt)
}
