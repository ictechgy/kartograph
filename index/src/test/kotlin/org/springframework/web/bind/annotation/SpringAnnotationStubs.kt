package org.springframework.web.bind.annotation

// 테스트 전용 Spring 어노테이션 대역이다. 실제 spring-web 의존성 없이 Kotlin 컴파일러가 만든 매핑 바이트코드(상수 접힘,
// suspend descriptor)를 검증하려고 이름과 속성 모양만 같게 둔다. main 소스에서는 보이지 않는다.

/** `RequestMethod` 대역이다. */
enum class RequestMethod { GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS, TRACE }

/** `@RequestMapping` 대역이다. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequestMapping(
    vararg val value: String = [],
    val path: Array<String> = [],
    val method: Array<RequestMethod> = [],
    val params: Array<String> = [],
)

/** `@GetMapping` 대역이다. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class GetMapping(vararg val value: String = [], val path: Array<String> = [], val produces: Array<String> = [])

/** `@RestController` 대역이다. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RestController
