package dev.kartograph.index.fixture.sourceretention

/** 사용처가 class 파일에 남지 않는 `@IntDef`류 어노테이션이다. 쓰이지만 bytecode로는 증명할 수 없다. */
@Retention(AnnotationRetention.SOURCE)
annotation class KotlinSourceMode

/** BINARY 어노테이션은 사용처가 class 파일에 남으므로 쓰이지 않으면 계속 보고되어야 한다. */
@Retention(AnnotationRetention.BINARY)
annotation class UnusedBinaryMode

/** SOURCE 어노테이션을 매개변수에 붙여 쓰는 진입점이다. */
fun useKotlinMode(@KotlinSourceMode mode: Int): Int = mode
