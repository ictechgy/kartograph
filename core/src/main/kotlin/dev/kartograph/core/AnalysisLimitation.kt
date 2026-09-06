package dev.kartograph.core

/** 분석 결과를 소비할 때 함께 고려해야 하는 정적 분석의 알려진 한계다. */
public enum class AnalysisLimitation(public val description: String) {
    REFLECTION_STRINGS("string-based runtime references other than constant Class.forName literals are not resolved"),
    DYNAMIC_REGISTRATION("manifest-absent runtime component registration is not resolved"),
    INLINE_CONSTANT_REFERENCES("compile-time constant owners are retained because inlined use sites are unavailable"),
    GENERATED_CODE_COVERAGE("only supported generation markers and sibling relationships identify generated code"),
    ANNOTATION_VALUE_REFERENCES("class references in SOURCE-retention annotations and annotation member default values are not recovered from bytecode"),
    TOP_LEVEL_DECLARATIONS("file-facade top-level properties and their accessors are conservatively not reported"),
}
