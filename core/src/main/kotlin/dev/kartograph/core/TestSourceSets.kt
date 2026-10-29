package dev.kartograph.core

/**
 * 테스트 소스 세트 경로 규칙의 단일 정의다.
 *
 * `routes`의 기본 테스트 제외와 `language-traversal`의 테스트 정점 제외가 같은 경계를 쓰도록 한곳에 둔다.
 * 두 문서가 다른 규칙을 쓰면 isthmus가 routes에서 뺀 테스트 호출 지점이 순회에서는 도달 정점으로 남는다.
 */
public object TestSourceSets {
    /** `src/testDebug`·`src/androidTestRelease`·`src/testFixtures` 같은 변형 테스트 세트다. */
    private val VARIANT_TEST_SOURCE_SET = Regex("^(?:test|androidTest)[A-Z].*")

    /** source set 바로 아래에 오는 언어·자원 디렉터리다. 이것이 뒤따르는 `src/<이름>`은 source set으로 확정한다. */
    private val SOURCE_ROOT_DIRECTORIES = setOf("java", "kotlin", "groovy", "scala", "resources", "res", "assets", "aidl", "cpp")

    /**
     * `/`로 구분한 project 상대 경로가 테스트 소스 세트 아래인지 판단한다.
     *
     * `src/test`, `src/androidTest`·`src/integrationTest`처럼 `Test`로 끝나는 세트, `src/test<Variant>`·
     * `src/androidTest<Variant>`·`src/testFixtures`를 테스트로 본다. 단, 경로의 다른 `src/<이름>` 쌍이 production source
     * set을 확정하면(`src/main`이거나, 테스트 세트가 아닌 이름 뒤에 `java`·`kotlin` 같은 언어 디렉터리가 오면) 거짓이다.
     * 그래서 main 소스 안의 `…/src/test/…` 모양 패키지(`com.example.src.test`)나 `src/LoadTest/app/src/main/…` 같은
     * 상위 디렉터리를 테스트로 오인하지 않는다. production을 테스트로 오인하면 순회가 그 구현을 빼 `bound`를 잘못 매기므로,
     * 두 신호가 섞이면 production으로 판정한다(반대 방향 오판은 bound가 약해질 뿐이다). Gradle source set을 관례 밖
     * 디렉터리로 옮긴 프로젝트는 알아보지 못한다.
     *
     * @param path project 상대 경로다. `\` 구분자는 `/`로 바꿔 본다
     * @return 테스트 소스 세트 아래면 참이다
     */
    public fun isTestSourcePath(path: String): Boolean {
        val parts = path.replace('\\', '/').split('/')
        var test = false
        for (index in 0 until parts.size - 1) {
            if (parts[index] != "src") continue
            val name = parts[index + 1]
            when {
                isTestSourceSet(name) -> test = true
                name == "main" || parts.getOrNull(index + 2) in SOURCE_ROOT_DIRECTORIES -> return false
            }
        }
        return test
    }

    private fun isTestSourceSet(name: String): Boolean = name == "test" || name.endsWith("Test") || VARIANT_TEST_SOURCE_SET.matches(name)
}
