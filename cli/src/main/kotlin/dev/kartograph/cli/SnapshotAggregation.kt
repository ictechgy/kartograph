package dev.kartograph.cli

import dev.kartograph.core.BuildWitness
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.SnapshotProvenance
import dev.kartograph.export.QuerySnapshot
import java.nio.file.Path

/**
 * 모듈별 snapshot 하나를 aggregate 기준 project 아래의 구성원으로 옮긴 값이다.
 *
 * @property directory 구성원 snapshot을 캡처한 모듈 project 디렉터리(절대 경로)
 * @property prefix aggregate project 기준 모듈 상대 경로다. aggregate project 자체면 빈 문자열이다
 * @property slotPrefix 구성원의 `external/` 슬롯을 다른 구성원과 구분하는 접두사다
 * @property snapshot 읽은 구성원 snapshot
 * @property bindings 구성원 capture가 만든 로컬 외부 입력 연결이다
 */
internal data class AggregateMember(
    val directory: Path,
    val prefix: String,
    val slotPrefix: String,
    val snapshot: QuerySnapshot,
    val bindings: Map<String, Path>,
) {
    /** 구성원 문서의 경로 식별자를 aggregate project 기준으로 바꾼다. `external/` 슬롯은 구성원별 접두사를 붙인다. */
    fun rebase(path: String): String = when {
        path.startsWith("external/") -> "external/$slotPrefix/${path.removePrefix("external/")}"
        prefix.isEmpty() -> path
        else -> "$prefix/$path"
    }

    /** 구성원 문서의 입력 식별자를 이 기계의 파일로 찾는다. 연결되지 않은 외부 슬롯은 `null`이다. */
    fun locate(path: String): Path? =
        if (path.startsWith("external/")) bindings[path] else directory.resolve(path).normalize()
}

/**
 * 여러 모듈 snapshot을 한 snapshot으로 합칠 때의 순수 데이터 변환이다.
 *
 * 그래프는 [SnapshotMergeCommand]가 구성원 class root를 함께 다시 인덱싱해 만든다. 모듈 snapshot에는 다른 모듈을
 * 가리키는 참조·상속 간선이 없으므로 그래프끼리 합치면 모듈 경계의 영향이 사라지기 때문이다. 여기서는 신선도 근거
 * (provenance)·로컬 연결·보존 근거의 경로를 aggregate project 기준으로 옮긴다. JVM USR은 모듈과 무관하므로
 * 정점 식별자는 그대로 유지된다.
 */
internal object SnapshotAggregation {
    /** 구성원 provenance를 경로만 옮겨 합친다. 해시와 witness 내용은 바꾸지 않으므로 다시 검증할 수 있다. */
    fun provenance(members: List<AggregateMember>): SnapshotProvenance = SnapshotProvenance(
        inputs = members.flatMap { member -> requireNotNull(member.snapshot.provenance).inputs.map { member.rebase(it) } },
        witnesses = members.flatMap { member -> requireNotNull(member.snapshot.provenance).witnesses.map { member.rebase(it) } },
        memberScopes = members.map { requireNotNull(it.snapshot.scope) },
    )

    /** 합친 provenance의 `external/` 슬롯을 구성원 로컬 연결에서 가져온다. 값은 절대 경로이므로 공개하지 않는다. */
    fun bindings(members: List<AggregateMember>): Map<String, Path> = members.flatMap { member ->
        member.bindings.map { (slot, path) -> member.rebase(slot) to path }
    }.toMap().toSortedMap()

    /** 구성원 보존 근거의 파일 위치를 aggregate project 기준으로 옮긴다. 외부 keep 파일 표기는 그대로 둔다. */
    fun retention(members: List<AggregateMember>): List<RetentionEvidence> = members.flatMap { member ->
        member.snapshot.retention.map { evidence ->
            val location = evidence.location ?: return@map evidence
            if (location.path.startsWith("external/")) evidence
            else evidence.copy(location = location.copy(path = member.rebase(location.path)))
        }
    }.distinct()

    /**
     * 다시 계산한 한계와 겹치지 않는 구성원 한계에 구성원 scope를 붙여 보존한다.
     *
     * @param recomputed 합친 인덱스로 다시 계산한 한계다. 같은 key(`:` 앞)의 구성원 값은 합친 값으로 대체된다
     */
    fun limitations(members: List<AggregateMember>, recomputed: List<String>): List<String> {
        val recomputedKeys = recomputed.map { it.substringBefore(':') }.toSet()
        return recomputed + members.flatMap { member ->
            member.snapshot.limitations.filter { it.substringBefore(':') !in recomputedKeys }
                .map { "${member.snapshot.scope} $it" }
        } + RETENTION_LIMITATION
    }

    private fun AggregateMember.rebase(input: InputFingerprint): InputFingerprint = input.copy(path = rebase(input.path))

    private fun AggregateMember.rebase(witness: BuildWitness): BuildWitness =
        witness.copy(inputs = witness.inputs.map { rebase(it) }, outputs = witness.outputs.map { rebase(it) })

    /** 모듈별로 계산한 보존 근거를 합쳤다는 사실을 결과에 남긴다. 모듈 경계를 넘는 keep rule은 다시 평가하지 않는다. */
    const val RETENTION_LIMITATION: String =
        "aggregate-retention: retention evidence combines each member capture; keep rules and manifests are not re-evaluated across modules"
}
