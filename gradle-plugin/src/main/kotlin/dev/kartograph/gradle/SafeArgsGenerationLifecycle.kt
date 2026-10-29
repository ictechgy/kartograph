package dev.kartograph.gradle

import dev.kartograph.index.ContentFingerprint
import dev.kartograph.core.*
import dev.kartograph.export.GenerationReceiptCodec
import dev.kartograph.export.GenerationOriginContextCodec
import org.gradle.api.Action
import org.gradle.api.Task
import java.nio.file.Path
import java.util.UUID

/** 현재 build 안에서만 쓰는 hook 소유권이다. configuration-cache로 복원된 객체를 같은 소유권으로 추정하지 않는다. */
internal class SafeArgsGenerationLifecycle(
    private val project: Path,
    private val generator: Task,
    private val journalDirectory: Path,
    private val variant: String,
    private val renderReceipt: (String, SafeArgsTaskBytes.Inputs, SafeArgsTaskBytes.Outputs, List<SafeArgsTaskBytes.Source>) -> String,
) {
    private val journal = GenerationAttemptJournal(journalDirectory)
    private val capture = SafeArgsTaskBytes(project, generator)
    private val original = generator.actions.singleOrNull()?.takeIf { it.javaClass.name == SafeArgsTaskBytes.DEFAULT_ACTION }
    private var beginSlot: Action<in Task>? = null
    private var endSlot: Action<in Task>? = null
    private var attempt: String? = null
    private var unavailable: String? = null
    private var invocation: String? = null

    data class Result(val receipt: String?, val reason: String?, val historicalReuse: Boolean,
        val files: List<Pair<String, Path>> = emptyList(), val originContext: GenerationOriginContext? = null,
        val currentContext: GenerationOriginContext? = null, val invocation: String? = null,
        val currentAttempt: String? = null, val originBasis: String? = null, val currentGenerationStatus: String? = null)

    fun install() {
        require(beginSlot == null && endSlot == null) { "generation hooks already installed" }
        generator.doFirst(Begin())
        beginSlot = generator.actions.first()
        generator.doLast(End())
        endSlot = generator.actions.last()
    }

    /** completion task의 dependsOn이 성공한 뒤 호출하고, 소비 직전에도 같은 live 상태/바이트를 다시 확인한다. */
    fun complete(): Result {
        val state = generator.state
        val result = when {
            !state.executed -> GenerationTaskResult.UNKNOWN
            state.failure != null -> GenerationTaskResult.FAILED
            state.skipMessage == "FROM-CACHE" -> GenerationTaskResult.FROM_CACHE
            state.skipMessage == "UP-TO-DATE" && state.upToDate -> GenerationTaskResult.UP_TO_DATE
            state.skipped -> GenerationTaskResult.SKIPPED
            else -> GenerationTaskResult.EXECUTED
        }
        unavailable?.let { return Result(null, it, false) }
        // 현재 성공과 실제 UP-TO-DATE의 과거 출처를 분리하여 기록한다.
        if ((result != GenerationTaskResult.EXECUTED || attempt == null) &&
            (result != GenerationTaskResult.UP_TO_DATE || attempt != null))
            return Result(null, "generation-current-execution-unverified", false)
        return try {
            val input = capture.inputs()
            val output = capture.outputs(input)
            val text = journal.finish(result, attempt, input.digest, output.digest, supportedActions())
                ?: return Result(null, "generation-attempt-unverified", false)
            val receipt = GenerationReceiptCodec.parse(text)
            val observed = GenerationReceiptCodec.parse(renderReceipt(receipt.attempt, input, output, capture.sources(input, output)))
            require(GenerationDigests.inventory(receipt) == GenerationDigests.inventory(observed)) { "generation inventory changed" }
            val context = GenerationOriginContext(GenerationDigests.receipt(receipt), receipt.scope, receipt.task, variant,
                input.outputRoot, input.mappingPath, listOf(
                    GenerationProviderBinding("navigationFiles", input.files.filter { it.role == "navigation" }.map { it.path }),
                    GenerationProviderBinding("outputDir", listOf(input.outputRoot)),
                    GenerationProviderBinding("incrementalFolder", listOf(input.mappingPath.substringBeforeLast('/')))),
                GenerationOriginContext.ACTION_POLICY, input.files.filter { it.role == "generatorActionArtifact" })
            if (result == GenerationTaskResult.EXECUTED)
                journal.bindOrigin(receipt.attempt, text, GenerationOriginContextCodec.render(context))
            val originText = journal.origin(receipt.attempt, text)
                ?: return Result(null, "generation-origin-context-unavailable", false)
            val origin = GenerationOriginContextCodec.parse(originText)
            require(GenerationDigests.context(origin) == GenerationDigests.context(context)) { "generation origin context changed" }
            val invocationId = invocation ?: newNonce().also { invocation = it }
            val warm = result == GenerationTaskResult.UP_TO_DATE
            Result(text, null, warm, input.files.filter { it.role != "generatorOptions" }.map {
                    it.role to input.paths.getValue(it.path)
                } + listOf("generationOutputs" to project.resolve(input.outputRoot), "generationMapping" to project.resolve(input.mappingPath),
                    "generationOrigin" to journalDirectory.resolve("${receipt.attempt}.origin")), origin, context, invocationId,
                if (warm) null else receipt.attempt, if (warm) "historical-byte-equivalence" else "current-cold-success",
                if (warm) "UP_TO_DATE" else "SUCCEEDED")
        } catch (_: java.io.IOException) { Result(null, "generation-input-unavailable", false) }
        catch (_: ReflectiveOperationException) { Result(null, "generation-provider-unavailable", false) }
        catch (_: RuntimeException) { Result(null, "generation-input-unsupported-or-changed", false) }
    }

    /** 기본 action 하나와 이 실행에서 직접 등록한 slot만 허용한다. wrapper 이름은 자기 hook 증명이 아니다. */
    private fun supportedActions(): Boolean {
        val actions = generator.actions
        return original != null && actions.size == 3 && actions[0] === beginSlot && actions[1] === original && actions[2] === endSlot
    }

    private inner class Begin : Action<Task> {
        override fun execute(task: Task) {
            val nonce = newNonce()
            invocation = newNonce()
            attempt = nonce
            unavailable = null
            try {
                val input = capture.inputs()
                val journalPath = journalDirectory.toAbsolutePath().normalize()
                val outputPath = project.resolve(input.outputRoot).toAbsolutePath().normalize()
                require(!journalPath.startsWith(outputPath) && !outputPath.startsWith(journalPath) &&
                    !project.resolve(input.mappingPath).toAbsolutePath().normalize().startsWith(journalPath)) {
                    "generation state overlaps generator outputs"
                }
                val before = capture.outputs(input)
                journal.begin(nonce, input.digest, before.digest, before.cold, supportedActions() && before.cold)
                if (!before.cold) unavailable = "generation-first-cold-observation-required"
            } catch (_: java.io.IOException) { unavailable = "generation-input-unavailable" }
            catch (_: ReflectiveOperationException) { unavailable = "generation-provider-unavailable" }
            catch (_: RuntimeException) { unavailable = "generation-input-unsupported" }
        }
    }

    private fun newNonce(): String = ContentFingerprint.values(listOf(UUID.randomUUID().toString()))

    private inner class End : Action<Task> {
        override fun execute(task: Task) {
            if (unavailable != null) return
            val nonce = attempt ?: return
            try {
                val input = capture.inputs()
                val after = capture.outputs(input)
                val sources = capture.sources(input, after)
                journal.provisional(nonce, input.digest, after.digest, renderReceipt(nonce, input, after, sources), supportedActions())
            } catch (_: java.io.IOException) { unavailable = "generation-output-unavailable" }
            catch (_: ReflectiveOperationException) { unavailable = "generation-provider-unavailable" }
            catch (_: RuntimeException) { unavailable = "generation-output-unsupported-or-changed" }
        }
    }
}
