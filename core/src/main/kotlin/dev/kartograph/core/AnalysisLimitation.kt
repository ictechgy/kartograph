package dev.kartograph.core

/** 분석 결과를 소비할 때 함께 고려해야 하는 정적 분석의 알려진 한계다. */
public enum class AnalysisLimitation(public val description: String) {
    REFLECTION_STRINGS("runtime reflection beyond bounded intra-method class and constructor values is not fully resolved"),
    DYNAMIC_REGISTRATION("manifest-absent runtime component registration is not resolved"),
    INLINE_CONSTANT_REFERENCES("compile-time constant owners are retained because inlined use sites are unavailable"),
    GENERATED_CODE_COVERAGE("only supported generation markers and sibling relationships identify generated code"),
    ANNOTATION_VALUE_REFERENCES("class references in SOURCE-retention annotations are not available in compiled inputs"),
    TOP_LEVEL_DECLARATIONS("file-facade top-level properties and their accessors are conservatively not reported"),
    /**
     * DI·직렬화·runtime 진입 annotation이 보존한 owner와 그 모든 member는 생성 코드나 framework가 실제로 쓰는지
     * 증명하지 않은 root다. `@Inject` 생성자만 있는 class, 미사용 `@Expose` DTO, 등록되지 않은 `@TypeConverter`
     * class처럼 쓰이지 않는 owner도 보고되지 않는다.
     */
    FRAMEWORK_ANNOTATION_OWNERS(
        "owners retained by supported dependency-injection, serialization or runtime-entry annotations " +
            "(such as @Inject constructors, @Expose fields, @TypeConverter methods or @Component types) and all " +
            "their members are roots without proof that generated code or a framework uses them",
    ),
    /**
     * `@Retention(SOURCE)` 어노테이션 class는 사용처가 class 파일에 남지 않아 도달 불가가 미사용을 뜻하지 않는다.
     * `dead`는 그런 class를 보고하지 않으며(사용 여부와 무관), 모든 형식이 이 한계로 그 사실을 알린다.
     */
    SOURCE_RETENTION_ANNOTATIONS(
        "SOURCE-retention annotation classes are never reported as unused because class files do not record their uses",
    ),
}
