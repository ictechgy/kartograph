package dev.kartograph.gradle

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** 환경 선택만 검사하며 실제 Gradle 8.13 또는 Android 성공으로 세지 않는다. */
class SafeArgsNativeProfileTest {
    @Test
    fun `기본 프로필은 기존 Kotlin DSL과 JDK21을 유지한다`(@TempDir root: Path) {
        val profile = SafeArgsNativeProfile.select(null)
        assertEquals(SafeArgsNativeProfile.CURRENT, profile)
        assertTrue(profile.kotlinDsl(21).contains("jvmToolchain(21)"))
        assertTrue(profile.kotlinSnapshotDsl(21).contains("JavaLanguageVersion.of(21)"))
        assertEquals(root.toFile(), profile.runner(root, null, 21).projectDir)
    }

    @Test
    fun `Java813은 Kotlin DSL을 추가하지 않고 설치본과 JDK17을 요구한다`(@TempDir root: Path) {
        val profile = SafeArgsNativeProfile.select("gradle813-agp813-java")
        assertEquals("8.13.0", profile.agpVersion)
        assertEquals("8.13", profile.gradleVersion)
        assertFalse(profile.builtinKotlin)
        assertEquals("", profile.kotlinDsl(17))
        assertEquals("", profile.kotlinSnapshotDsl(17))
        assertFailsWith<IllegalArgumentException> { profile.runner(root, null, 21) }
        assertFailsWith<IllegalArgumentException> { profile.runner(root, null, 17) }
        assertFailsWith<IllegalArgumentException> { profile.runner(root, root.resolve("missing").toString(), 17) }
    }

    @Test
    fun `임의 버전과 빈 선택은 허용하지 않는다`() {
        for (value in listOf("", "8.13", "latest", "gradle813-agp932")) {
            assertFailsWith<IllegalArgumentException> { SafeArgsNativeProfile.select(value) }
        }
    }
}
