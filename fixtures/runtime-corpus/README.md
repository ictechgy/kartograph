# Runtime corpus

Java/Kotlin compiler 입력으로 상수 사용처 손실, reflection, 외부 dispatch, ServiceLoader와 DI 보존을 검사한다.
각 사례는 별도 class root로 컴파일한다. 진입점 Entry/KotlinEntry와 직접 member만 keep하며 소비 대상은 keep하지 않는다.
상수·미사용 Inject는 보수적 보존의 대조군이고, 실제 실행되는 target과 그 본문 의존성을 반드시 구분한다.
Java 입력은 RuntimeEvidenceCliTest에서 컴파일한다. Scripts/verify-runtime-corpus.py는 Java/Kotlin 13종과 미사용 대조군을 실제로 실행하고 SAM의 indy/class backend 양쪽을 비교한다. 새 오탐을 숨기기 위해 기대값이나 keep 범위를 넓히지 않는다.
