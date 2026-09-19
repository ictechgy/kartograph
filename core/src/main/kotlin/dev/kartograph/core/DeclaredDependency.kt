package dev.kartograph.core

/** Gradle 의존성 선언 scope다. processor·runtime 전용 scope는 컴파일 참조로 판정할 수 없다. */
public enum class DependencyScope(public val option: String) {
    API("api"),
    IMPLEMENTATION("implementation"),
    COMPILE_ONLY("compileOnly"),
    COMPILE_ONLY_API("compileOnlyApi"),
    RUNTIME_ONLY("runtimeOnly"),
    ANNOTATION_PROCESSOR("annotationProcessor"),
    KAPT("kapt"),
    KSP("ksp"),
    TEST_IMPLEMENTATION("testImplementation"),
    TEST_COMPILE_ONLY("testCompileOnly"),
    TEST_RUNTIME_ONLY("testRuntimeOnly"),
    ;

    public companion object {
        /** 입력 문서의 scope 문자열과 정확히 일치하는 값만 반환한다. */
        public fun fromOption(value: String): DependencyScope? = entries.firstOrNull { it.option == value }
    }
}

/** 선언됐지만 bytecode 참조로 사용 여부를 판정할 artifact 하나다. */
public data class DeclaredDependency(
    val coordinate: String,
    val scope: DependencyScope,
    val artifact: String,
)
