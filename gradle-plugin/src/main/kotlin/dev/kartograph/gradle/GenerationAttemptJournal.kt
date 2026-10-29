package dev.kartograph.gradle

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.HexFormat

/** 현재 시도의 상태와 과거 성공 기록을 분리한다. 생성기 입력이나 task 실행 정책은 바꾸지 않는다. */
internal class GenerationAttemptJournal(private val directory: Path) {
    /** 성공 receipt에 최초 provider/action 문맥을 결박하며 과거 파일을 덮어쓰지 않는다. */
    fun bindOrigin(attempt: String, content: String, origin: String): Unit = locked {
        hashes(attempt)
        val current = requireNotNull(state()) { "generation current state is missing" }
        require(current.mode == "final" && current.attempt == attempt && receipt(current) == content) {
            "generation origin requires this successful receipt"
        }
        val bytes = origin.toByteArray(UTF_8)
        require(bytes.size <= MAX_RECEIPT_BYTES) { "generation origin exceeds byte limit" }
        val name = "$attempt.origin"
        val existing = read(name, MAX_RECEIPT_BYTES)
        if (existing == null) write(name, bytes)
        else require(existing.contentEquals(bytes)) { "generation origin history changed" }
    }

    fun origin(attempt: String, content: String): String? = locked {
        hashes(attempt)
        val current = state() ?: return@locked null
        if (current.mode != "final" || current.attempt != attempt || receipt(current) != content) return@locked null
        read("$attempt.origin", MAX_RECEIPT_BYTES)?.toString(UTF_8)
    }

    /** 실행 직전 pending을 먼저 쓴다. 이후 실패하거나 중단되면 같은 바이트의 과거 성공도 현재 근거가 아니다. */
    fun begin(attempt: String, inputs: String, before: String, cold: Boolean, supportedActions: Boolean): Unit = locked {
        beginLocked(attempt, inputs, before, cold, supportedActions)
    }
    private fun beginLocked(attempt: String, inputs: String, before: String, cold: Boolean, supportedActions: Boolean) {
        hashes(attempt, inputs, before)
        val prior = state()
        require(prior?.attempt != attempt && listOf("begun", "provisional", "provisional-state", "final").none {
            Files.exists(directory.resolve("$attempt.$it"), NOFOLLOW_LINKS)
        }) { "generation attempt identity was already used" }
        val unchanged = prior?.mode == "final" && prior.inputs == inputs && prior.capture == before && receipt(prior) != null
        // pointer 변경 전에 시도 ID를 영구 예약한다. 중단 후에도 과거 provisional의 ID를 재사용하지 않는다.
        write("$attempt.begun", attempt.toByteArray(UTF_8))
        write("current", State("pending", attempt, inputs, before, ZERO, supportedActions && (cold || unchanged)).encode())
    }

    /** 마지막 owned action은 provisional만 남긴다. 뒤의 action 실패를 성공으로 인증하지 않는다. */
    fun provisional(attempt: String, inputs: String, after: String, content: String, supportedActions: Boolean): Unit = locked {
        provisionalLocked(attempt, inputs, after, content, supportedActions)
    }
    private fun provisionalLocked(attempt: String, inputs: String, after: String, content: String, supportedActions: Boolean) {
        hashes(attempt, inputs, after)
        val current = state() ?: return
        if (current.mode != "pending" || current.attempt != attempt || !current.eligible ||
            current.inputs != inputs || !supportedActions) return
        val bytes = content.toByteArray(UTF_8)
        require(bytes.size <= MAX_RECEIPT_BYTES) { "generation receipt exceeds byte limit" }
        write("$attempt.provisional", bytes)
        write("$attempt.provisional-state", State("provisional", attempt, inputs, after, hash(bytes), true).encode())
    }

    /** 의존 task가 끝난 뒤 매번 호출한다. 캐시/skip은 새 생성 성공과 구분하며 상태가 없으면 미확정이다. */
    fun finish(result: GenerationTaskResult, attempt: String?, inputs: String, after: String, supportedActions: Boolean): String? = locked {
        finishLocked(result, attempt, inputs, after, supportedActions)
    }
    private fun finishLocked(result: GenerationTaskResult, attempt: String?, inputs: String, after: String, supportedActions: Boolean): String? {
        hashes(inputs, after)
        if (!supportedActions) return null
        val current = state() ?: return null
        if (result == GenerationTaskResult.UP_TO_DATE || result == GenerationTaskResult.FROM_CACHE) {
            if (attempt != null || current.mode != "final" || current.inputs != inputs || current.capture != after) return null
            return receipt(current)
        }
        // 같은 현재 invocation의 completion과 소비 전 검증은 각각 실행한다. 새 invocation에는 이 nonce가 없다.
        if (result == GenerationTaskResult.EXECUTED && attempt != null && current.mode == "final" &&
            current.attempt == attempt && current.inputs == inputs && current.capture == after) return receipt(current)
        if (result != GenerationTaskResult.EXECUTED || attempt == null || current.mode != "pending" ||
            current.attempt != attempt || !current.eligible || current.inputs != inputs) return null
        val provisional = read("$attempt.provisional-state", MAX_STATE_BYTES)?.let(State::decode) ?: return null
        if (provisional.mode != "provisional" || provisional.attempt != attempt || provisional.inputs != inputs ||
            provisional.capture != after || !provisional.eligible) return null
        val bytes = read("$attempt.provisional", MAX_RECEIPT_BYTES) ?: return null
        if (hash(bytes) != provisional.receipt) return null
        // 성공 기록을 먼저 보존한다. pointer 쓰기 전에 중단되면 pending이 남아 소비를 거부한다.
        write("$attempt.final", bytes)
        write("current", provisional.copy(mode = "final").encode())
        return bytes.toString(UTF_8)
    }

    private fun state(): State? = read("current", MAX_STATE_BYTES)?.let(State::decode)
    private fun receipt(state: State): String? = read("${state.attempt}.final", MAX_RECEIPT_BYTES)
        ?.takeIf { hash(it) == state.receipt }?.toString(UTF_8)

    private fun read(name: String, maximum: Int): ByteArray? {
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return null
        requireDirectory()
        val file = directory.resolve(name)
        if (!Files.exists(file, NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= maximum) { "invalid generation state file" }
        return Files.newInputStream(file, NOFOLLOW_LINKS).use { stream -> stream.readNBytes(maximum + 1).also {
            require(it.size <= maximum) { "generation state exceeds byte limit" }
        } }
    }

    private fun write(name: String, content: ByteArray) {
        Files.createDirectories(directory)
        requireDirectory()
        val target = directory.resolve(name)
        require(!Files.isSymbolicLink(target)) { "generation state must not be a link" }
        val temporary = Files.createTempFile(directory, ".generation-", ".tmp")
        try {
            Files.write(temporary, content)
            // 원자적 치환을 지원하지 않는 파일시스템에서는 미확정으로 실패한다. 비원자 fallback은 없다.
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun requireDirectory() {
        require(Files.isDirectory(directory, NOFOLLOW_LINKS) &&
            directory.toAbsolutePath().normalize() == directory.toRealPath()) { "generation state directory must not traverse links" }
    }

    /** 다른 build의 pending을 오래된 completion이 덮지 않게 상태 전이 전체를 단일 파일 잠금으로 묶는다. */
    private fun <T> locked(action: () -> T): T {
        Files.createDirectories(directory)
        requireDirectory()
        return FileChannel.open(directory.resolve("lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            val lock = requireNotNull(channel.tryLock()) { "generation state is in use by another capture" }
            lock.use { action() }
        }
    }

    private data class State(val mode: String, val attempt: String, val inputs: String, val capture: String,
        val receipt: String, val eligible: Boolean) {
        fun encode(): ByteArray = listOf(FORMAT, mode, attempt, inputs, capture, receipt, eligible.toString()).joinToString("\n", postfix = "\n").toByteArray(UTF_8)
        companion object {
            fun decode(bytes: ByteArray): State {
                val rows = bytes.toString(UTF_8).split('\n')
                require(rows.size == 8 && rows[0] == FORMAT && rows[7].isEmpty() &&
                    rows[1] in setOf("pending", "provisional", "final") && rows[6] in setOf("true", "false")) { "invalid generation attempt state" }
                hashes(rows[2], rows[3], rows[4], rows[5])
                return State(rows[1], rows[2], rows[3], rows[4], rows[5], rows[6].toBoolean())
            }
        }
    }

    companion object {
        private const val FORMAT = "kartograph-generation-attempt-v1"
        private const val MAX_STATE_BYTES = 512
        private const val MAX_RECEIPT_BYTES = 16 * 1024 * 1024
        private val ZERO = "0".repeat(64)
        private fun hashes(vararg values: String) { require(values.all { Regex("[0-9a-f]{64}").matches(it) }) { "invalid generation state hash" } }
        private fun hash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}

/** TaskState에서 관찰한 현재 실행 결과이며, 파일 존재나 과거 receipt로 추측하지 않는다. */
internal enum class GenerationTaskResult { EXECUTED, UP_TO_DATE, FROM_CACHE, SKIPPED, FAILED, UNKNOWN }
