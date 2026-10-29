package retrofit2.http

// 테스트 전용 Retrofit 어노테이션 대역이다. 실제 retrofit 의존성 없이 Kotlin 컴파일러가 만든 서비스 인터페이스 바이트코드
// (추상 메서드, suspend continuation, 인터페이스 상속, 기본 인자)를 검증하려고 이름·속성·보존 정책만 같게 둔다.
// main 소스에서는 보이지 않는다.

/** `@GET` 대역이다. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class GET(val value: String = "")

/** `@POST` 대역이다. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class POST(val value: String = "")

/** `@HTTP` 대역이다. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class HTTP(val method: String, val path: String = "", val hasBody: Boolean = false)

/** `@Path` 대역이다. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Path(val value: String, val encoded: Boolean = false)

/** `@Query` 대역이다. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Query(val value: String, val encoded: Boolean = false)

/** `@Body` 대역이다. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Body
