package dev.kartograph.gradle

import java.nio.file.Path
import groovy.lang.GroovyShell
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

    @Test
    fun `관측 버전은 AGP 표시 문구가 아닌 공개 version 값이다`() {
        for (profile in SafeArgsNativeProfile.entries) {
            val parts = profile.agpVersion.split('.').map(String::toInt)
            GroovyShell().evaluate("""
                def observed = new com.android.build.api.AndroidPluginVersion(${parts[0]}, ${parts[1]}, ${parts[2]})
                assert observed.version == '${profile.agpVersion}'
                assert observed.toString() == 'Android Gradle Plugin version ${profile.agpVersion}'
            """.trimIndent())
        }
    }

    @Test
    fun `8 계열은 평가 후 같은 이름의 공개 provider만 연결한다`() {
        val script = """
            def pending = []; def consumed = []
            def compiler = new Object()
            binding.setVariable('selected', [name:'debug'])
            binding.setVariable('recordCompiler', { consumed.add(it) })
            binding.setVariable('afterEvaluate', { pending.add(it) })
            binding.setVariable('android', [applicationVariants:[
                [name:'release', javaCompileProvider:[configure:{ throw new AssertionError('wrong variant') }]],
                [name:'debug', javaCompileProvider:[configure:{ action -> action(compiler) }]]]])
            ${SafeArgsNativeProfile.JAVA_813.compilerBindingDsl()}
            assert consumed.empty
            assert pending.size() == 1
            pending[0]()
            assert consumed.size() == 1 && consumed[0].is(compiler)
        """.trimIndent()
        GroovyShell().evaluate(script)
    }

    @Test
    fun `공개 callback과 누락 모호 provider 대조군을 유지한다`() {
        GroovyShell().evaluate("""
            def compiler = new Object(); def consumed = []
            binding.setVariable('selected', [configureJavaCompileTask:{ action -> action(compiler) }])
            binding.setVariable('recordCompiler', { consumed.add(it) })
            ${SafeArgsNativeProfile.CURRENT.compilerBindingDsl()}
            assert consumed.size() == 1 && consumed[0].is(compiler)
        """.trimIndent())
        for (variants in listOf("[]", "[[name:'debug'],[name:'debug']]")) {
            assertFailsWith<IllegalStateException> {
                GroovyShell().evaluate("""
                    binding.setVariable('selected', [name:'debug'])
                    binding.setVariable('recordCompiler', { throw new AssertionError('unexpected compiler') })
                    binding.setVariable('afterEvaluate', { it() })
                    binding.setVariable('android', [applicationVariants:$variants])
                    ${SafeArgsNativeProfile.JAVA_813.compilerBindingDsl()}
                """.trimIndent())
            }
        }
    }
}
