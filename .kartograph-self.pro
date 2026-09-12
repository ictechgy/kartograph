# Self-analysis entry points: the standalone CLI and Gradle's plugin loader.
-keep class dev.kartograph.cli.MainKt { *; }
-keep class dev.kartograph.gradle.KartographPlugin { *; }
# 실제 Gradle 소비 테스트의 빌드 스크립트가 호출하는 compiler producer API다.
-keep class dev.kartograph.gradle.CompilerWitnesses { *; }
-keep class dev.kartograph.gradle.KotlinCompilerWitnesses { *; }
