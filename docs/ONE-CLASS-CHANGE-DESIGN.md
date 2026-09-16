# 한 클래스 변경 캡처 속도 — 구조적 접근 설계 노트 (2026-09-16)

`snapshot --index-cache`에서 class 하나를 바꾼 뒤의 캡처 시간이 전체 캡처의 85% 이하(15% 단축)로 **측정 조건에 관계없이** 나오게 하는 설계다.
15%는 사용자가 요청한 수치가 아닌 내부 목표이며, 0.9.0 최종 0.8586·2026-09-16 1차 0.8538/0.8596 미달 기록은 그대로 둔다.
이 문서는 설계 합의용으로 쓰였고, 단계 A는 합의 후 구현·측정했다(§9).

## 1. 실측: 지배 단계

기존 공식 러너 출력(`build/reports/benchmark-20260916/`, `--timings` stderr)을 재실행 없이 집계했다.
집계 스크립트와 결과: `build/reports/one-class-design-20260916/phase-breakdown-run2-idle.txt`(2차·유휴 실행, 7회 중앙값).
`other`는 벽시계 − (captureHash + indexTotal + snapshotRender), 즉 JVM 기동·클래스 로딩·keep/manifest 스캔·retention·출력 쓰기다.

| 단계 (ms, 중앙값) | self full | self changed | self 비중 | nia full | nia changed | nia 비중 |
|---|---:|---:|---:|---:|---:|---:|
| captureHash (fingerprint, before+after 2패스) | 92 | 88 | 12.9% | 1367 | **1363** | **61.6%** |
| index: read+cacheRead+parse+cacheWrite | 188 | 88 | 12.8% | 186 | 113 | 5.1% |
| index: assembly | 73 | 69 | 10.1% | 94 | 81 | 3.7% |
| index: hierarchy (dependency JAR header) | 4 | 4 | 0.5% | 467 | 128 | 5.8% |
| index: runtime (값 전파) | 60 | 54 | 7.8% | 34 | 32 | 1.4% |
| index: dispatch | 68 | 58 | 8.5% | 44 | 55 | 2.5% |
| snapshotRender | 129 | 129 | 18.8% | 128 | 133 | 6.0% |
| other | 183 | 164 | 23.9% | 270 | 254 | 11.5% |
| **wall** | **807** | **686** | | **2616** | **2211** | |

두 코호트의 병목이 다르다.

- **nia**: fingerprint가 changed 벽시계의 62%다. 227개 JAR 208.7 MB를 before/after 두 번, 단일 스레드 SHA-256으로 읽는다(`FreshnessCommand.capture` → `VerifiedCaptureScope.capture` 순차 호출). 전역 분석(assembly+runtime+dispatch)은 8%에 불과하다. **HANDOFF의 "global analysis가 지배적이면 구조적 접근" 가설은 nia에서 기각된다.**
- **self**: 지배 단계가 없다. 전역 분석 27%, 기타 24%, 렌더 19%, fingerprint 13%. 변경 시 줄어드는 것은 parse뿐(169→35)이고, 그 35 ms도 class 1개가 아니라 파서 클래스 로딩·JIT 예열이다(nia도 1개 파싱에 30 ms).

## 2. 비율의 구조

`ratio = (F + Vc) / (F + Vf)`. F = 두 모드에 공통인 고정비, Vf/Vc = full/changed의 가변비.

| 코호트 | F | Vf | Vc | 현재 ratio | 전역 분석을 전부 없애도 | F를 150 ms 줄이면 |
|---|---:|---:|---:|---:|---:|---:|
| self | ≈ 400 (hash 88 + render 129 + other 164 + 잔여) | 403 | 290 | 0.850 | 0.63 (비현실) | 0.816 |
| nia | ≈ 1760 | 858 | 453 | 0.845 | 0.73 (비현실) | 0.836 |

F는 wall − indexTotal로 계산했다(self 807−403, nia 2616−858). "전역 분석을 전부 없애도"는 changed의 assembly+hierarchy+runtime+dispatch를 0으로 둔 가상 하한이다.

self는 표본이 작아(0.8 s) 고정비가 비율을 지배한다. 전역 분석 재사용(후보 1)은 self에서 최대 180 ms를 건드리지만, nia에서는 fingerprint 한 항목(1.36 s)이 그보다 7배 크다.

## 3. 후보 평가

| 후보 | 근거 | 판단 |
|---|---|---|
| 1. 변경 없는 class의 분석 결과 재사용 | `RuntimeValueAnalyzer.enrich`는 class별로 돌지만 다른 class의 `returns`·`FieldWriteIndex`·전역 `typeSupers`·hierarchy를 소비하는 interprocedural 전파다. `projectOverrideEdges`·`ExternalDispatchIndexer`도 전역이다. 재사용하려면 분석 중 소비한 타 class 요약을 기록하는 의존 추적과 무효화 경계 증명이 필요하다. 이득 상한은 self 180 ms, nia 170 ms. | **보류.** 단계 A·B 측정 뒤에도 self가 0.82 밴드에 남을 때만 착수. |
| 2. fingerprint 비용 절감 | nia 62%. spike(`ShaSpike.java`): 같은 227 JAR을 단일 스레드 596 ms, 4 worker 289 ms, 8 worker 264 ms. 하한은 87.5 MB JAR 한 개(≈250 ms). 이 JDK(17.0.20 aarch64)는 SHA intrinsic이 없어(`UseSHA=false`, 켜면 JVM 기동 거부) 단일 스레드 350 MB/s가 상한이다. | **채택 (단계 A).** 다이제스트 값·before/after 계약·내용 해시 정책 불변. |
| 3. 목표 재정의 | 벽시계 비율은 고정비에 묶여 있어 self에서 조건 민감하다. 그러나 단계 A만으로 nia는 큰 여유가 생기고 self도 하한이 내려간다. | **채택 안 함.** 15% 지표는 그대로 두고, 러너에 단계 계약(§6)을 *보조 진단*으로만 추가한다. |

추가로 확인한 고정비 지렛대(제품 코드 밖, `build/reports/one-class-design-20260916/spikes.md`):
CLI 시작 스크립트의 JVM 옵션. self full 5회 중앙값: 기본 799 ms, `-XX:TieredStopAtLevel=1` 695 ms, AppCDS 765 ms, 둘 다 653 ms(load 4~5로 노이즈 있음, 로컬 0.9.0 installDist 사용).
AppCDS는 같은 JDK 빌드에서만 유효한 아카이브가 필요해 배포 기본값이 될 수 없다. C1-only는 이식 가능하지만 긴 실행(nia)에서 C2 손실 여부를 측정해야 한다.

## 4. 제안

**단계 A — fingerprint 병렬화 (구조적, 계약 불변).** 주 변경.
**단계 B — CLI JVM 고정비 (조건부).** nia full에서 손해가 없을 때만 `DEFAULT_JVM_OPTS`에 C1-only를 넣는다. Gradle plugin 경로에는 영향 없음(daemon 내부 실행).
**단계 C — 후보 1.** A·B 측정 후 self가 여전히 0.82 이상이면 별도 설계로 착수한다. 이 문서 범위 밖.

### 단계 A 상세

**어디.** `index/VerifiedCaptureScope`에 일괄 캡처 진입점을 추가하고 `cli/FreshnessCommand.capture`가 파일 목록을 한 번에 넘긴다.
`ContentFingerprint`의 해시 정의(정렬된 상대 이름 + 파일 다이제스트, 길이 구분 SHA-256)는 손대지 않는다.

**무엇을 병렬로.**
- 입력 목록(role, path, slot) 단위로 최대 4 worker(기존 class batch 정책과 동일)에서 `captureObserved`를 실행한다.
- 디렉터리 입력(class root, source root)은 내부 파일 다이제스트를 같은 pool에서 계산하고, 상위 다이제스트 결합은 정렬 순서대로 주 스레드에서 한다.
- before 패스와 after 패스 모두 적용한다. 두 패스 사이의 `phase` 상태 검사는 그대로 유지한다.

**결정성.**
- 파일 다이제스트는 서로 독립이고, 결합은 기존과 같은 순서로 주 스레드에서 하므로 결과 값은 순차 구현과 바이트 단위로 동일하다. 계약 테스트: 같은 입력에 대해 순차/병렬 `SnapshotProvenance` 동일, 캐시 on/off snapshot 바이트 동일(기존 테스트 유지).
- 오류 우선순위: 계획 단계(입력 존재·symlink·역할 검사)는 모든 입력에 대해 digest보다 먼저 입력 순서대로 실행되므로, 계획 오류가 있으면 파일을 읽지 않고 그 중 첫 입력의 오류를 보고한다. 계획이 모두 통과하면 digest 오류 중 작업 순서상 첫 것을 보고한다. 이미 제출된 digest 작업은 취소하지 않고 끝까지 실행한 뒤 spool을 닫는다(순차 구현과 달리 "같은 입력의 읽기 오류가 뒤 입력의 계획 오류보다 먼저" 보고되지 않는다). 메시지(원시 경로 비노출)는 그대로다.
- interrupt: 호출 스레드가 interrupt되면 `ClassIndexingException("fingerprint capture was interrupted")`으로 보고한다. worker가 만든 spool은 scope가 소유한 기록에 남고, `close()`가 worker 종료(`awaitTermination`)를 기다린 뒤 그 기록으로 닫으므로 `close()` 반환 직후 잔여물이 없다. worker 쪽 interrupt(`ClosedByInterruptException`)도 IO 실패가 아닌 중단으로 보고한다.
- symlink: 계획 단계의 symlink 검사와 실제 열기 사이에 파일이 바뀔 수 있으므로(계획 후 JAR을 먼저 digest하면 class 파일은 수백 ms 뒤에 열린다), 열기와 크기 읽기는 `NOFOLLOW_LINKS`로 링크를 따라가지 않는다. 그 사이 symlink로 바뀐 파일은 digest에서 실패한다. 상위 디렉터리가 symlink로 바뀌는 경우는 이 방어 범위 밖이다.
- 크기 관측: 입력당 `readAttributes` 한 번으로 정규 파일 여부·크기를 읽어 spool 대상 판정·예약·스케줄링 힌트에 모두 쓴다. 속성을 읽지 못한 JAR은 그 pass의 spool 대상에서 빠지므로(fingerprint는 그대로 읽고 header는 직접 읽기), "크기를 모른 채 예약 0으로 spool"하는 경우가 없어 같은 묶음의 뒤 JAR가 예산을 겹쳐 쓰지 못한다. 순차 구현은 정규 파일 검사와 크기 읽기가 별도 syscall이라 그 사이 실패 시 예약 0으로 spool했는데, 그 좁은 창이 사라진 것이 유일한 동작 차이다. 디렉터리 멤버는 크기를 재지 않는다.
- 순서·역할 검증(`verifyInputs`)은 `initialInputs`를 입력 순서대로 채우므로 변하지 않는다.

**spool 예산.** cold population의 `remainingSpoolBytes`·`eligibleJars`는 현재 순차 캡처 순서에 의존한다. 병렬화 후에는 주 스레드가 입력 순서대로 `Files.size` 기준으로 예산을 **선할당**하고, 실제 spool 크기로 사후 정산한다.
차이는 spool 쓰기가 중간에 실패한 경우 후속 JAR에 예산이 되돌아가지 않는 것뿐이며, spool은 최적화이지 검증이 아니므로(`INDEX-CACHE.md`) 정확성에 영향이 없다. 정산은 묶음이 끝난 뒤 이루어지므로 다음 묶음은 돌아온 예산을 쓴다(`spool failure returns its reservation only after the batch` 테스트).

**메모리.** worker당 64 KiB 버퍼와 `MessageDigest` 하나. spool 상한(JAR 128 MiB, 총 256 MiB)은 그대로다.

**무효화 경계.** 없음. fingerprint의 의미·값·비교 시점이 바뀌지 않으므로 캐시 항목·신선도 판정·witness 계약에 영향이 없다. 이 사실 자체를 계약 테스트로 남긴다.

**예상 효과(추정, 측정으로 확인).** nia: captureHash 1363 → 약 600 ms(2×~290). changed ≈ 1450, full ≈ 1850, ratio ≈ 0.78. self: 392개 소파일이라 syscall 지배적이며 88 → 40~50 ms 추정, ratio ≈ 0.84. self는 단계 A만으로 안정 통과를 기대하지 않는다.

### 단계 B 상세

`cli/build.gradle.kts`의 `applicationDefaultJvmArgs`에 `-XX:TieredStopAtLevel=1`을 추가하는 한 줄이다. 채택 조건: nia full 7회 중앙값이 기본 대비 느려지지 않을 것. 느려지면 넣지 않고 spike 기록만 남긴다.
self 추정: F −100 ms → 단계 A와 합쳐 ratio ≈ 0.80.

## 5. 측정 계획

HANDOFF의 측정 계약 그대로: 같은 러너(`measure-one-class-change.py`, `measure-index-cache.py`), 같은 고정 입력(frozen-self 392 class, nia 22 root·227 JAR), 7회 중앙값, load < 2 확인 후 시작, 1차·2차 모두 보존, `--timings` 동반, snapshot 바이트 일치·정확히 1개 재파싱·`hierarchyParsedJars=0` 유지.

순서: ① 기준선(현재 main) → ② 단계 A → ③ 단계 A+B(nia full 손실 검사 포함). 각 단계의 단계별 집계를 `phase_breakdown.py`로 같이 남긴다.
판정: 두 코호트 모두 ratio ≤ 0.85이고, self·nia의 7회 min–max 상한도 0.85 이하면 "안정 달성"으로 쓴다. 중앙값만 통과하면 "통과(조건 민감)"로 쓴다.

## 6. 보조 진단 (목표를 바꾸지 않음)

러너 요약에 단계 계약을 추가한다: `indexParsedClasses == 1`, `hierarchyParsedJars == 0`(이미 있음) + `captureHashNanos(changed) / wall(changed)` 비중 기록. 통과/미달 판정에는 쓰지 않고, 다음에 지표가 흔들릴 때 어느 단계가 흔들렸는지 바로 보이게 하는 용도다.

## 7. 리스크

- 병렬 fingerprint가 IO 경합으로 cold 페이지 캐시에서는 이득이 줄 수 있다. 러너는 warm 페이지 캐시 조건이므로 cold 조건은 별도로 기록만 한다.
- spool 예산 선할당은 극단 실패 경로에서 spool 대상 JAR 집합을 바꿀 수 있다. 정확성은 불변이나 cold/full 비율에 미세 영향이 가능하므로 warm/cold 계약(≤0.85/≤1.10)을 같이 재측정한다.
- C1-only는 JIT 특성상 큰 입력에서 느려질 수 있다. 측정으로 결정하며 기본값 변경을 추정으로 하지 않는다.
- spool 예약은 `Files.size`로 정하므로 결정과 digest 사이에 JAR 크기가 바뀌면 그 묶음의 spool 대상 집합이 순차 구현과 다를 수 있다(순차 구현도 같은 종류의 TOCTOU가 있었다). 정확성에는 영향이 없다.
- 모든 입력의 디렉터리 목록과 파일 관측을 동시에 메모리에 둔다(순차 구현은 입력 하나씩). 문서화된 규모(22 root·227 JAR)에서는 문제없으나 매우 큰 source root에서는 메모리 형태가 다르다.
- self 코호트는 단계 A+B 후에도 0.80~0.84 밴드가 예상이다. 그때 단계 C(후보 1) 또는 목표 재정의를 다시 논의한다. 이 문서는 그 결정을 미리 내리지 않는다.

## 8. 합의가 필요한 항목

1. 단계 A를 먼저 구현하고 측정한 뒤 B를 조건부로 붙이는 순서.
2. 단계 C(전역 분석 재사용)를 지금은 착수하지 않는 것.
3. 구현 브랜치: HANDOFF대로 main(95c2cea)에서 새 브랜치를 만든다. 현재 체크아웃(`feat/adoption-competitiveness`)은 다른 세션이 같은 시각에 EventChannel 커밋(2952aec, 98a633e)을 쌓고 있고 미커밋 `HANDOFF.md`·`AGENTS.md`가 있으므로, 이 체크아웃에서 브랜치를 전환하지 않고 별도 worktree에서 구현하는 편이 안전하다.

## 9. 단계 A 구현·측정 결과 (2026-09-16)

구현: `VerifiedCaptureScope.captureAll`(입력 순서대로 spool 예산 선할당·사후 정산, 첫 JAR 앞 세그먼트는 캐시 준비와 겹침),
`ContentFingerprint`의 계획→digest→결합 분해, `IndexWorkPool.map(chunkSize)`, CLI `FreshnessCommand.capture`·plugin `KartographSnapshotTask`의 일괄 호출.
계약 테스트 `ParallelCaptureTest`(순차/병렬 값·순서 동일, 첫 오류 순서·spool 잔여 없음, 예산 순서). 근거 원본: `build/reports/benchmark-20260916-parallel/README.md`.

| 코호트 | 지표 | 기준선(3회) | 후보(3회, 3차가 최종 코드) |
|---|---|---|---|
| nia | one-class/full | 0.840 / 0.828 / 0.835 | 0.781 / 0.782 / 0.821 |
| nia | warm/full | 0.827 / 0.832 / 0.832 | 0.787 / 0.772 / 0.756 |
| nia | changed captureHash | 1.34 s | 0.62~0.70 s |
| self | one-class/full | **0.859** / 0.846 / 0.838 | **0.851** / 0.821 / 0.838 |
| self | warm/full | 0.823 / 0.809 / 0.829 | **0.857** / 0.788 / 0.763 |
| self | changed captureHash | 82~84 ms | 61 ms(최종), 85 ms(중간 빌드) |

- nia는 예측(0.78)대로 안정적으로 내려갔고 절대 시간도 full 2.56→1.84 s, warm 2.13→1.40 s다.
- self는 예측대로 노이즈 밴드에 남았다. 중간 빌드의 디렉터리 멤버 `Files.size`가 full 모드 captureHash를 +18 ms 늘려 제거했다(교차 측정 90→77 ms).
- 1차 후보의 self warm 0.857 미달은 같은 실행의 모든 모드가 일괄 느려진 부하 사례였고 2차·3차는 통과했다. 기록은 보존한다.
- **15% self 목표는 이 단계로 달성되지 않았다.** 다음은 단계 B(C1-only 측정)와, 그래도 남으면 단계 C다.

## 10. 단계 B 측정 결과 — 기각 (2026-09-16)

main e02d71e 바이너리를 기본 JIT과 `-XX:TieredStopAtLevel=1` 래퍼로 같은 러너·입력·7회로 비교했다(부하 있는 호스트, 순서 기본→C1).
근거: `build/reports/benchmark-20260916-stage-b/README.md`.

비율 지표는 모두 "낮을수록 좋음"이며 one-class 목표는 ≤ 0.85다.

| 코호트 | 지표 | 기본 | C1-only | 변화 |
|---|---|---:|---:|---:|
| self | one-class/full | 0.864 | **0.913** | 악화(+0.049) |
| self | changed / full 벽시계(one-class) | 662 / 769 ms | 611 / 667 ms | −8% / −13% |
| self | warm / full 벽시계(index-cache) | 655 / 825 ms | 557 / 666 ms | −15% / −19% |
| nia | one-class/full | 0.814 | 0.793 | 개선(−0.021) |
| nia | changed / full 벽시계(one-class) | 1523 / 1884 ms | **1721 / 2151 ms** | **+13% / +14%** |
| nia | warm / full 벽시계(index-cache) | 1427 / 1896 ms | **1665 / 2110 ms** | **+17% / +11%** |
| nia | changed captureHash | 629 ms | 906 ms | +44% |

- 채택 조건("nia full이 느려지지 않을 것")을 위반한다. 이 실행에서 nia는 모든 모드에서 11~17% 느려졌다. changed 캡처에서 captureHash가 +277 ms인데 벽시계는 +198 ms이므로 나머지 단계는 약 80 ms 빨라졌다. 즉 손실은 SHA-256 루프에 집중돼 있고, C2 없이 그 루프가 느려지는 것이 유력한 원인이다(JIT 수준의 직접 증거는 없음).
- self는 절대 시간이 줄지만 one-class 비율은 목표에서 멀어진다. C1은 파싱이 많은 full 캡처(769→667, −13%)를 changed 캡처(662→611, −8%)보다 더 많이 줄이므로 비율은 오른다.
- 따라서 단계 B는 채택하지 않고 `applicationDefaultJvmArgs`도 바꾸지 않는다. 단일 부하 호스트·고정 순서(기본→C1)의 7회 실행이며 2차(순서 반전)는 하지 않았다. 두 코호트가 서로 다른 방향으로 채택 조건을 위반하고 차이(nia +11~17%, self 비율 +0.049)가 §9에서 관측한 노이즈 밴드(비율 ±0.03)보다 크다는 근거로 기각했다. 순서 편향 가능성(C1이 뒤에 실행)은 남아 있으나 결론을 바꿀 크기는 아니다.
- §3의 spike(self full 799→695 ms)는 절대 시간에 관해서는 재현됐으나, 비율 목표에는 도움이 되지 않는다는 것이 이번 측정의 결론이다.

**남은 경로.** 지금까지 확인한 지렛대 중 남은 것은 단계 C(변경 없는 class의 runtime·dispatch 분석 결과 재사용)다. 착수 전에 §2의 추정치(self 이득 상한 약 180 ms, 비율 하한 0.63 — 측정값이 아닌 추정)와 의존 추적 비용을 사용자와 다시 저울질해야 한다.

## 11. 결론 — 과제 종료 (2026-09-17)

**얻은 것.** 단계 A(fingerprint 병렬화, PR #61)로 nia 코호트는 one-class 0.84→0.78~0.82, full 2.56→1.84 s, warm 2.13→1.40 s가 됐고
계약 테스트(순차/병렬 값 동일, 실패·interrupt 시 spool 잔여 없음)가 남았다.

**얻지 못한 것.** self 코호트(392 class, 0.8 s)의 one-class 15% 내부 목표는 달성하지 못했다. 기록은 0.9.0 최종 0.859, 2026-09-16 기준선 0.859/0.846/0.838, 단계 A 0.851/0.821/0.838이며
숨기거나 반올림하지 않는다.

**왜 못 얻었는가.** 이 입력에서는 두 모드에 공통인 고정비(JVM 기동·클래스 로딩·JIT 예열·JSON 렌더·fingerprint — fingerprint는 단계 A로 줄었지만 여전히 두 모드가 같이 낸다)가
벽시계의 약 절반(§2 추정, self F≈400 ms/807 ms)이라 비율의 하한이 구조적으로 높다. 캐시가 파싱을 없애도 남는 것은 전역 분석 약 180 ms뿐이고, 단계 B(JIT 옵션)는 비율을 오히려 악화시켰다(§10).

**단계 C를 착수하지 않는 이유.** 전역 분석 재사용은 "캐시 on/off 결과 동일" 계약을 깨뜨릴 수 있는 유일한 후보이며(interprocedural 의존 추적과 무효화 경계 증명 필요),
얻는 것은 self에서 전역 분석 전부를 재사용하는 비현실적 상한이 약 0.18 s(§2 추정), dispatch 후보·상속 폐포처럼 class 단위로 닫히지 않는 부분을 빼고 절반쯤 재사용하면 약 0.1 s다.
dead·impact 판정의 정확성을 걸 만한 이득이 아니다.

**이후 이 지표를 다시 볼 때.** 작은 입력의 벽시계 비율은 노이즈(±0.03)와 고정비에 묶여 있으므로, 단계별 계약으로 잰다: 변경 시 재파싱 정확히 1개(이미 러너가 검증),
`hierarchyParsedJars = 0`, 그리고 전역 분석(assembly+hierarchy+runtime+dispatch)의 changed/full 비율. 15% 벽시계 목표는 폐기가 아니라 "이 입력에서는 판정 불가"로 기록한다.

근거: `build/reports/benchmark-20260916-parallel/`(단계 A, 6회 실행), `build/reports/benchmark-20260916-stage-b/`(단계 B), `build/reports/one-class-design-20260916/`(단계별 집계·spike).

## 12. 머지 후 3관점 리뷰 반영 (2026-09-17)

보안·구조·성능 관점의 읽기 전용 리뷰(에이전트 3종)와 GLM 리뷰를 종합해 "결함"과 "minimal change"로 분류된 항목을 한 PR로 반영했다.
단일 소유자·2-pass 재구성 같은 큰 리팩토링은 과제가 종료된 영역이라 하지 않았다.

- 결함: symlink 검사/열기 분리(열기에 `NOFOLLOW_LINKS`), interrupt 후 spool 정리 비동기(scope 소유 registry + `awaitTermination`), 크기 미상 JAR의 예산 0 spool(속성을 못 읽은 JAR은 spool 대상에서 제외), 도달 불가한 `FreshnessCommand` 순차 fallback 제거, interrupt 메시지 통일.
- 비용: 정렬 comparator가 비교마다 `Files.size`·`relativize`를 재계산하던 것을 키 선계산으로 바꾸고, JAR당 최대 4회이던 stat을 입력당 `readAttributes` 1회로 합쳤으며(디렉터리·witness 같은 비-JAR 입력도 1회 읽는다), `project.toRealPath()`를 배치당 1회로 줄였다.
- 구조: `FileDigestJob.standalone`(정책 힌트)을 관측 크기 `sizeHint`로 바꿔 스케줄러가 파일 시스템을 읽지 않게 했다. `CaptureInput`의 `data`를 뺐고, `IndexWorkPool.map`의 묶음 크기는 nullable(무묶음)로 바꿨다. `captureSegment`를 예약·기록으로 나눴다.
- 테스트: 계획 오류 두 개를 구분(symlink vs missing), 계획 후 symlink 교체 거부, spool 실패 시 묶음 뒤 예산 환급, interrupt 잔여 0을 폴링 없이 단언.

**가장 큰 남은 지렛대는 코드가 아니라 JDK다.** 이 호스트(Apple M4 Pro, `FEAT_SHA256=1`)에서 같은 227개 JAR 209 MB를 digest하면
JDK 17(Homebrew·Temurin 모두)은 단일 스레드 589~607 ms·4 worker 288 ms인데, JDK 21·26은 **단일 92 ms·4 worker 43 ms**다
(aarch64 SHA-256 intrinsic이 JDK 21부터 켜짐, `build/reports/benchmark-20260916-stage-b/sha-jdks.log`).
지금까지의 모든 벤치마크는 러너에 고정된 JDK 17로 쟀으므로, JDK 21에서는 nia의 fingerprint가 캡처당 약 620 ms에서 100 ms 아래로 내려간다.
§11의 결론을 바꾸지는 않지만(self 비율은 고정비 구조라 여전히 판정 불가), 절대 시간 개선의 다음 단계는 "JDK 21 기준 재측정"이다.
