# Self-analysis entry points: the standalone CLI and Gradle's plugin loader.
-keep class dev.kartograph.cli.MainKt { *; }
-keep class dev.kartograph.gradle.KartographPlugin { *; }
# 실제 Gradle 소비 테스트의 빌드 스크립트가 호출하는 compiler producer API다.
-keep class dev.kartograph.gradle.CompilerWitnesses { *; }
-keep class dev.kartograph.gradle.KotlinCompilerWitnesses { *; }
# Gradle가 등록된 task에서 reflection으로 호출하는 실행 진입점이다.
-keepclasseswithmembers class dev.kartograph.gradle.** {
    @org.gradle.api.tasks.TaskAction <methods>;
}
# CompilerWitnesses가 doFirst/doLast/upToDateWhen에 넘기는 실제 Action/Spec bridge 진입점이다.
# Gradle 실행 구현은 자체 분석 class root 밖에 있으므로 정확한 erased callback만 root로 둔다.
-keep class dev.kartograph.gradle.BeginWitness {
    public void execute(java.lang.Object);
}
-keep class dev.kartograph.gradle.CompleteWitness {
    public void execute(java.lang.Object);
}
-keep class dev.kartograph.gradle.MatchingWitness {
    public boolean isSatisfiedBy(java.lang.Object);
}
# 설치된 analysis/core JAR만 사용하는 별도 소비자가 검증하는 공개 library 진입점이다.
# public 전체나 같은 owner의 다른 overload/helper를 자동 보존하지 않는다.
-keep class dev.kartograph.analysis.AuthoredCallUniverse {
    public dev.kartograph.analysis.AuthoredCallUniverseResult examine(dev.kartograph.core.CodeGraph, dev.kartograph.analysis.AuthoredCallerScope);
}
