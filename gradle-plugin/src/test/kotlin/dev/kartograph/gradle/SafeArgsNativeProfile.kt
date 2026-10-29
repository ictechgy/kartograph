package dev.kartograph.gradle

import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testkit.runner.GradleRunner

/** 합성 native fixture의 두 고정 소비 환경만 선택한다. 제품 지원 버전의 설정 API가 아니다. */
internal enum class SafeArgsNativeProfile(val id: String, val gradleVersion: String, val agpVersion: String, val builtinKotlin: Boolean) {
    CURRENT("gradle971-agp932", "9.7.1", "9.3.2", true),
    JAVA_813("gradle813-agp813-java", "8.13", "8.13.0", false);

    fun runner(root: Path, installation: String?, javaFeature: Int): GradleRunner {
        val runner = GradleRunner.create().withProjectDir(root.toFile())
        if (this == JAVA_813) {
            require(javaFeature == 17) { "Gradle 8.13 Java fixture requires JDK 17" }
            val home = Path.of(requireNotNull(installation) { "Gradle 8.13 fixture requires the verified distribution home" })
            require(Files.isDirectory(home) && Files.isRegularFile(home.resolve("bin/gradle"))) { "verified Gradle 8.13 distribution is unavailable" }
            runner.withGradleInstallation(home.toFile())
        }
        return runner
    }

    fun kotlinDsl(javaFeature: Int): String = if (builtinKotlin) """
        kotlin {
            jvmToolchain($javaFeature)
            compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
        }
    """.trimIndent() else ""

    fun kotlinSnapshotDsl(javaFeature: Int): String = if (builtinKotlin)
        "snapshotKotlinToolchain = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of($javaFeature) }" else ""

    /** 같은 관측 closure에 공개 compiler 입력만 연결한다. AGP 8에는 새 callback이 없다. */
    fun compilerBindingDsl(): String = when (this) {
        CURRENT -> "selected.configureJavaCompileTask(recordCompiler)"
        JAVA_813 -> """
            afterEvaluate {
                def matches = android.applicationVariants.findAll { it.name == selected.name }
                if (matches.size() != 1) throw new IllegalStateException('fixture compiler variant missing or ambiguous')
                matches[0].javaCompileProvider.configure(recordCompiler)
            }
        """.trimIndent()
    }

    companion object {
        fun select(value: String?): SafeArgsNativeProfile = entries.singleOrNull { it.id == (value ?: CURRENT.id) }
            ?: throw IllegalArgumentException("unknown SafeArgs native profile")
    }
}
