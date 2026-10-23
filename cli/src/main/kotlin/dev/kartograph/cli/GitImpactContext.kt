package dev.kartograph.cli

import dev.kartograph.export.QuerySnapshot
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Git 변경 선택과 내용 검증을 묶되 revision별 그래프 탐색은 기존 분석기에 남긴다. */
internal object GitImpactContext {
    data class Change(val baseRevision: String, val headRevision: String, val workingTree: Boolean,
        val files: List<String>, val submodules: Boolean) {
        fun document(): Map<String, Any?> = mapOf("origin" to "git", "mode" to if (workingTree) "workingTree" else "commits",
            "baseRevision" to baseRevision, "headRevision" to headRevision, "files" to files,
            "selection" to "wholeFileDeclarations", "renamePolicy" to "oldAndNewPaths",
            "limits" to mapOf("changedPaths" to MAX_CHANGED_PATHS, "gitOutputBytesPerCommand" to MAX_GIT_BYTES,
                "gitSecondsPerCommand" to GIT_SECONDS, "gitConfigurationLookupIncluded" to false),
            "workingTreeIncludes" to if (workingTree) listOf("staged", "unstaged", "untrackedNonIgnored") else emptyList<String>())
    }

    fun inspect(project: Path, baseRef: String, headRef: String?, workingTree: Boolean): Change {
        require(!workingTree || headRef == null) { "working-tree cannot be combined with git-head" }
        val root = project.toAbsolutePath().normalize()
        require(Path.of(git(root, listOf("rev-parse", "--show-toplevel")).trim()).toAbsolutePath().normalize() == root) {
            "Git impact project must be the repository root"
        }
        fun commit(ref: String): String {
            require(ref.isNotBlank() && !ref.any(Char::isISOControl))
            return git(root, listOf("rev-parse", "--verify", "--end-of-options", "$ref^{commit}")).trim()
                .also { require(Regex("[0-9a-f]{40}|[0-9a-f]{64}").matches(it)) }
        }
        val base = commit(baseRef)
        val head = commit(headRef ?: "HEAD")
        // rename를 D/A로 유지해 과거 경로를 잃지 않는다. 서브모듈 안의 설정·명령은 실행하지 않는다.
        val diff = listOf("-c", "diff.autoRefreshIndex=false", "diff", "--name-only", "-z", "--no-renames",
            "--no-ext-diff", "--no-textconv", "--ignore-submodules=dirty", base) +
            (if (workingTree) emptyList() else listOf(head)) + "--"
        val untracked = if (workingTree) git(root,
            listOf("ls-files", "--others", "--exclude-standard", "--full-name", "-z", "--")).split('\u0000').filter(String::isNotEmpty)
            else emptyList()
        val selected = (git(root, diff).split('\u0000') + untracked).filter(String::isNotEmpty).distinct().sorted()
        require(selected.size <= MAX_CHANGED_PATHS && selected.all(ImpactCommand::portable)) { "Git changed paths exceed supported portable path bounds" }
        // HEAD에서 삭제한 파일을 비추적으로 복원하면 index 기반 diff는 D/A를 보고한다.
        // 기준 blob과 실제 바이트·모드가 같은 복원만 제외해 base→작업 트리의 빈 변경을 보존한다.
        val restored = if (untracked.isEmpty()) emptySet() else restoredBaseFiles(root, base, untracked.toSet())
        val files = selected.filterNot(restored::contains)
        val submodules = git(root, listOf("ls-files", "--stage", "-z", "--")).split('\u0000').any { it.startsWith("160000 ") }
        return Change(base, head, workingTree, files, submodules)
    }

    private fun restoredBaseFiles(root: Path, base: String, untracked: Set<String>): Set<String> =
        git(root, listOf("ls-tree", "-rz", "--full-tree", base)).split('\u0000').mapNotNull { entry ->
            val split = entry.indexOf('\t')
            if (split < 0) return@mapNotNull null
            val name = entry.substring(split + 1)
            if (name !in untracked) return@mapNotNull null
            val metadata = entry.substring(0, split).split(' ')
            if (metadata.size != 3 || metadata[1] != "blob" || metadata[0] !in setOf("100644", "100755")) return@mapNotNull null
            val file = root.resolve(name)
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || generateSequence(file.parent) { it.parent }
                    .takeWhile { it != root }.any(Files::isSymbolicLink)) return@mapNotNull null
            val executable = if (file.fileSystem.supportedFileAttributeViews().contains("posix"))
                OWNER_EXECUTE in Files.getPosixFilePermissions(file, NOFOLLOW_LINKS) else {
                // Windows에서 복원된 실행 비트의 동일성을 증명하지 못하면 후보를 남긴다.
                if (metadata[0] == "100755") return@mapNotNull null
                false
            }
            if (executable != (metadata[0] == "100755")) return@mapNotNull null
            val hash = git(root, listOf("hash-object", "--no-filters", "--", name)).trim()
            name.takeIf { hash == metadata[2] }
        }.toSet()

    fun validate(change: Change, current: QuerySnapshot, base: QuerySnapshot?) {
        require(base != null && current.scope != null && base.scope != null) { "Git impact requires scoped base/current snapshots" }
        SavedSnapshotOperations.compatible(current, base)
        require(base.revision == change.baseRevision && (current.revision == change.headRevision || change.workingTree && current.revision == null)) {
            "Snapshot revision labels do not match the selected Git commits; rebuild and recapture both inputs"
        }
    }

    fun freshness(current: QuerySnapshot, base: QuerySnapshot, project: Path, baseProject: Path?,
        external: Map<String, Path>, baseExternal: Map<String, Path>, workingTree: Boolean = false,
        expectedScope: String? = null): Map<String, Any?> {
        fun check(snapshot: QuerySnapshot, checkout: Path?, bindings: Map<String, Path>, role: String, committed: Boolean): Map<String, Any?> {
            if (checkout == null) return mapOf("status" to "unverified", "reasons" to listOf("$role-checkout-not-configured"))
            val result = SavedSnapshotOperations.freshness(snapshot, checkout, expectedScope ?: snapshot.scope, bindings)
            // 캡처 당시 dirty 내용을 같은 commit 라벨로 붙여도 committed 비교의 fresh 증거로 승격하지 않는다.
            val revisionReason = if (!committed) null else try {
                val relative = inspect(checkout, requireNotNull(snapshot.revision), null, true)
                if (relative.files.isEmpty()) null else "checkout-differs-from-selected-commit"
            } catch (_: Exception) { "checkout-revision-unverified" }
            val status = when {
                result.status == "stale" || revisionReason == "checkout-differs-from-selected-commit" -> "stale"
                revisionReason != null -> "unverified"
                else -> result.status
            }
            return mapOf("status" to status, "reasons" to (result.reasons + listOfNotNull(revisionReason)).distinct().sorted())
        }
        return mapOf("current" to check(current, project, external, "current", !workingTree),
            "base" to check(base, baseProject, baseExternal, "base", true))
    }

    fun document(graphImpact: Any?, change: Change, identities: Map<String, Any?>,
        freshness: Map<String, Any?>): Map<String, Any?> = mapOf(
        "format" to "kartograph-change-impact", "version" to 1, "assessment" to "reviewRequired",
        "change" to change.document(), "snapshots" to identities, "freshness" to freshness, "graphImpact" to graphImpact,
        "limitations" to listOfNotNull(
            "file-selection: all captured declarations in changed paths are candidates; unchanged declarations in those files may be included",
            "capture: build each revision with the same tool and scope; Git labels alone do not prove source or compiler freshness",
            "restored-files: byte-identical untracked restorations are omitted only when their Git file mode can be verified; unknown modes remain candidates",
            "observation: Git selection and input hashing are sequential, not an atomic filesystem snapshot; keep builds and edits quiescent",
            "static-candidates: results do not predict test failures, authorize test skipping or prove an empty change safe",
            if (change.submodules) "submodules: uncommitted changes inside submodules were not inspected" else null))

    /** 출력과 실행 시간을 제한하고 원시 Git 오류·로컬 경로는 밖으로 내보내지 않는다. */
    private fun git(project: Path, arguments: List<String>): String {
        val process = GitCommand.start(project, arguments)
        val output = CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(MAX_GIT_BYTES + 1) } }
        try {
            require(process.waitFor(GIT_SECONDS, TimeUnit.SECONDS)) { "Git impact selection timed out" }
            require(process.exitValue() == 0) { "Git impact input unavailable; verify local history (no objects are fetched)" }
            val bytes = output.get(1, TimeUnit.SECONDS)
            require(bytes.size <= MAX_GIT_BYTES) { "Git impact selection exceeds 4 MiB" }
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } finally {
            if (process.isAlive) process.destroyForcibly()
            output.cancel(true)
        }
    }

    private const val MAX_GIT_BYTES = 4 * 1024 * 1024
    private const val MAX_CHANGED_PATHS = 20_000
    private const val GIT_SECONDS = 30L
}
