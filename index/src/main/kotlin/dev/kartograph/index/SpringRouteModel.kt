package dev.kartograph.index

/**
 * Spring 서버 라우트 추출이 쓰는 선언 모델이다.
 *
 * 바이트코드 reader([SpringBytecodeReader])와 소스 reader([SpringSourceReader])가 같은 모양으로 채운다.
 * 규칙([SpringMappingResolver])은 이 모델만 보므로 두 원천의 결과가 같은 해석을 거친다. 바이트코드는 상수가
 * 이미 풀린 값을 주고, 소스는 어노테이션 토큰 위치를 준다.
 */
internal sealed interface SpringValue {
    /** `String`·`String[]` 속성 값이다. 단일 값도 한 원소 목록으로 담는다. */
    data class Strings(val values: List<String>) : SpringValue

    /** enum 배열 속성 값이다(`RequestMethod[]`). 상수 이름만 담는다. */
    data class Enums(val names: List<String>) : SpringValue

    /** 소스 식을 정적으로 풀지 못한 값이다. 원문은 싣지 않는다 — 문자열 원문이 출력으로 새지 않게 한다. */
    data object Unresolved : SpringValue
}

/**
 * 선언 하나에 붙은 어노테이션이다.
 *
 * @property type 점으로 구분한 어노테이션 타입 이름이다. 소스에서 해석하지 못한 이름은 단순 이름 그대로다
 * @property attributes 명시한 속성만 담는다. 기본값은 [SpringMethod.defaultValue]에서 읽는다
 * @property offset 소스 원문에서 `@` 토큰의 위치다. 바이트코드 원천이면 null이다
 */
internal data class SpringAnnotation(val type: String, val attributes: Map<String, SpringValue>, val offset: Int? = null)

/**
 * `@AliasFor` 한 건이다.
 *
 * @property annotation 대상 어노테이션 타입이다. null이면 같은 타입 안의 거울 속성이다
 * @property attribute 대상 속성 이름이다. 빈 문자열이면 선언한 속성과 같은 이름이다
 */
internal data class SpringAliasTarget(val annotation: String?, val attribute: String)

/**
 * 메서드(또는 어노테이션 속성) 선언이다.
 *
 * @property descriptor JVM 메서드 descriptor다. 소스 원천이면 null이다
 * @property parameterCount 소스 기준 매개변수 수다. Kotlin `suspend`의 continuation 매개변수는 세지 않는다
 * @property firstLine 바이트코드 LineNumberTable의 첫 줄이다. 소스 위치를 고를 때 오버로드를 가른다
 * @property nameOffset 소스 원문에서 메서드 이름 토큰의 위치다
 * @property aliases 어노테이션 속성의 `@AliasFor` 목록이다
 * @property defaultValue 어노테이션 속성의 기본값이다
 */
internal data class SpringMethod(
    val name: String,
    val descriptor: String?,
    val parameterCount: Int,
    val annotations: List<SpringAnnotation>,
    val isAbstract: Boolean = false,
    val isSynthetic: Boolean = false,
    val firstLine: Int? = null,
    val nameOffset: Int? = null,
    val aliases: List<SpringAliasTarget> = emptyList(),
    val defaultValue: SpringValue? = null,
)

/** 타입 선언의 종류다. Spring은 구체 class만 핸들러 bean으로 등록한다. */
internal enum class SpringTypeKind { CLASS, INTERFACE, ANNOTATION, ENUM }

/**
 * 타입 선언이다.
 *
 * @property name 점으로 구분한 이름이다. 중첩 타입은 바깥 이름 뒤에 점으로 잇는다(`pkg.Outer.Inner`)
 * @property internalName JVM internal name이다(`pkg/Outer$Inner`). 신원(usr) 계산에 쓴다
 * @property sourcePath 프로젝트 상대 소스 경로다. 바이트코드 원천이면 소스와 맞춘 뒤 채운다
 * @property sourceFileName 바이트코드 `SourceFile` 속성이다(`OwnerController.java`)
 * @property isTest 테스트 소스 세트의 선언이다
 */
internal data class SpringType(
    val name: String,
    val internalName: String,
    val kind: SpringTypeKind,
    val isAbstract: Boolean,
    val superclass: String?,
    val interfaces: List<String>,
    val annotations: List<SpringAnnotation>,
    val methods: List<SpringMethod>,
    val sourcePath: String? = null,
    val sourceFileName: String? = null,
    val isTest: Boolean = false,
)

/** Spring 어노테이션 이름과 프레임워크 내장 선언이다. 공식 소스(spring-web 7.0.8·6.2.10)의 선언을 옮긴다. */
internal object SpringAnnotations {
    const val REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping"
    const val HTTP_EXCHANGE = "org.springframework.web.service.annotation.HttpExchange"
    const val CONTROLLER = "org.springframework.stereotype.Controller"
    const val REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController"
    const val ALIAS_FOR = "org.springframework.core.annotation.AliasFor"

    /** 동사가 고정된 `@RequestMapping` 합성 어노테이션이다. */
    val MAPPING_VERBS: Map<String, String> = listOf("Get", "Post", "Put", "Delete", "Patch").associate { verb ->
        "org.springframework.web.bind.annotation.${verb}Mapping" to verb.uppercase()
    }

    /** 동사가 고정된 `@HttpExchange` 합성 어노테이션이다. */
    val EXCHANGE_VERBS: Map<String, String> = listOf("Get", "Post", "Put", "Delete", "Patch").associate { verb ->
        "org.springframework.web.service.annotation.${verb}Exchange" to verb.uppercase()
    }

    /** `@RequestMapping` 합성 어노테이션의 속성이다. 모두 `@AliasFor(annotation = RequestMapping.class)`다. */
    private val MAPPING_ATTRIBUTES = listOf("name", "value", "path", "params", "headers", "consumes", "produces", "version")

    /** `@HttpExchange` 합성 어노테이션의 속성이다. */
    private val EXCHANGE_ATTRIBUTES = listOf("value", "url", "contentType", "accept", "headers", "version")

    /** 내장 선언 전체다. 프로젝트 선언과 같은 규칙으로 병합되도록 모델 타입으로 둔다. */
    val BUILT_INS: Map<String, SpringType> by lazy {
        (MAPPING_VERBS.map { (name, verb) -> composed(name, REQUEST_MAPPING, "method", SpringValue.Enums(listOf(verb)), MAPPING_ATTRIBUTES) } +
            EXCHANGE_VERBS.map { (name, verb) -> composed(name, HTTP_EXCHANGE, "method", SpringValue.Strings(listOf(verb)), EXCHANGE_ATTRIBUTES) } +
            annotationType(REST_CONTROLLER, listOf(SpringAnnotation(CONTROLLER, emptyMap())), emptyList()))
            .associateBy { it.name }
    }

    private fun composed(name: String, root: String, attribute: String, value: SpringValue, attributes: List<String>): SpringType =
        annotationType(name, listOf(SpringAnnotation(root, mapOf(attribute to value))), attributes.map { member ->
            SpringMethod(member, null, 0, emptyList(), isAbstract = true, aliases = listOf(SpringAliasTarget(root, member)))
        })

    private fun annotationType(name: String, meta: List<SpringAnnotation>, members: List<SpringMethod>): SpringType =
        SpringType(name, name.replace('.', '/'), SpringTypeKind.ANNOTATION, true, null, emptyList(), meta, members)
}
