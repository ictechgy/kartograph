package dev.kartograph.gradle

import kotlin.test.Test
import kotlin.test.assertFailsWith

/** 실제 관측된 전체 action JAR만 허용하며 이름이나 버전 문자열은 대체 근거가 아니다. */
class SafeArgsActionArtifactsTest {
    @Test
    fun `두 관측 artifact와 변조되거나 이름뿐인 입력을 구분한다`() {
        SafeArgsTaskBytes.requireSupportedActionArtifact("470ae06ceef38cf2c69f1a20838bc80ece735bc92c2615f5bad77a50ab379d25")
        SafeArgsTaskBytes.requireSupportedActionArtifact("0a69dc9e20abce45bbe11135351dca180dd6b98b77274fd2b347655d0cd6fa41")
        for (value in listOf("", "gradle-core-8.13.jar", "8.13", "0".repeat(64),
            "0a69dc9e20abce45bbe11135351dca180dd6b98b77274fd2b347655d0cd6fa40")) {
            assertFailsWith<IllegalArgumentException> { SafeArgsTaskBytes.requireSupportedActionArtifact(value) }
        }
    }
}
