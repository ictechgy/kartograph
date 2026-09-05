# fixtures 지침

이 디렉터리는 [공통 지침](../AGENTS.md)을 상속한다. 실제 compiler가 기록하는 사실과 오탐/검출 상실의 경계를 증명한다.

## 표본 설계

- 새 오탐 계열은 최소 빌드 가능한 표본에 먼저 넣고 수정 전 실패를 확인한다. 수정 후에는 기대 진단 집합과 보존 근거를 양방향으로 대조한다.
- “보고되면 안 되는 코드”와 “반드시 보고할 코드”를 함께 둔다. 생성 marker·이름·가시성 규칙에는 사용자 동명/유사명 반례가 필요하다.
- 테스트를 통과시키려고 기대값이나 keep 규칙을 넓히지 않는다. 정책 변경은 source/compiler/명세 근거와 보고 상실 여부를 먼저 설명한다.
- private와 class-only 모드, callback/inline/reflection/serialization 경계를 따로 확인한다.
- 생성 annotation을 재현한 stub과 실제 KSP/Hilt/Room/Moshi 산출물을 구분한다. stub만으로 생성기 전체를 검증했다고 주장하지 않는다.
- 외부 표본은 revision·variant·입력·시간·한계를 기록한다. 개인 프로젝트는 소스·심볼·경로를 복사하지 않고 익명 집계만 남긴다. 공개 코드 복사 시 라이선스와 출처를 확인한다.
- 생성 class/JAR/APK, build/cache, SDK 경로, 인증파일을 커밋하지 않는다.

## 검증 경로

- `false-positive-corpus/`: 실제 Android app/library와 [기대값](false-positive-corpus/expectations.tsv). `Scripts/verify-fixture-corpus.sh`, `Scripts/verify-gradle-plugin-fixture.sh`로 검사한다.
- `generated-code-corpus/`: CLASS-retention marker와 nested/anonymous class를 javac로 검증한다. `python3 -m unittest discover -s Scripts/tests -v`에 포함된다.
- `bridge-corpus/`: `Scripts/verify-agent-surface.sh`로 query/bridge JSON의 실제 parser 계약을 확인한다.
- 실제 Room/Moshi KSP 입력은 [RoomFixtures.kt](false-positive-corpus/app/src/main/kotlin/dev/kartograph/fixture/RoomFixtures.kt)와 [MoshiFixtures.kt](false-positive-corpus/app/src/main/kotlin/dev/kartograph/fixture/MoshiFixtures.kt)다. Android verifier가 빌드하며 생성물을 직접 편집하지 않는다. 실제 Hilt 표본은 [공개 검증 재현 절차](../docs/PUBLIC-VALIDATION.md)를 따른다.

명령은 저장소 루트에서 실행한다. SDK/JDK/의존성을 준비할 수 없으면 이유와 재현 명령을 남기고 검증 완료로 표시하지 않는다.
