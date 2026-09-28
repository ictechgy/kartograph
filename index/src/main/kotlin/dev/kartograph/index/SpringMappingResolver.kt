package dev.kartograph.index

/**
 * [SpringType] 모델에서 Spring MVC·WebFlux 핸들러 메서드와 병합된 매핑 속성을 찾는다.
 *
 * spring-webmvc 7.0.8 `RequestMappingHandlerMapping`의 규칙을 옮긴다.
 * - 핸들러 bean: `@Controller`(메타 포함)를 타입 계층 어디서든 가진 구체 class다(`isHandler`). Boot 2(Spring 5)는
 *   타입 수준 `@RequestMapping`만 있어도 핸들러였다.
 * - 매핑: 요소와 그 타입 계층(메서드는 재정의한 상위 메서드)에서 `@RequestMapping`·`@HttpExchange`를 메타까지 찾되
 *   처음 찾은 요소의 것만 쓴다(`MergedAnnotations` TYPE_HIERARCHY + `firstRunOf`).
 * - 속성 병합: 명시 `@AliasFor`만 따른다. 합성 어노테이션의 속성 값은 기본값이어도 메타 어노테이션 값을 덮는다
 *   (spring-core `TypeMappedAnnotation.getValue` — 별칭 매핑이 있으면 root 값을 쓴다). 관례 기반 덮어쓰기는 Spring 7에서
 *   사라졌으므로 모델링하지 않는다.
 */
internal class SpringMappingResolver(types: Collection<SpringType>, private val legacyTypeLevelHandlers: Boolean) {
    // 내장 선언이 이긴다 — class root나 소스에 같은 이름의 대역·복제본이 있어도 프레임워크 의미를 바꾸지 않는다.
    private val types: Map<String, SpringType> = types.associateBy { it.name } + SpringAnnotations.BUILT_INS

    /** 병합된 매핑이다. 속성 이름은 `path`·`method`·`params`·`headers`·`consumes`·`produces`·`version`으로 맞춘다. */
    data class Mapping(val family: String, val attributes: Map<String, SpringValue>, val annotation: SpringAnnotation)

    /**
     * 핸들러 메서드 하나다.
     *
     * @property handlerType 요청을 받는 구체 controller class다
     * @property declaringType 메서드를 선언한 타입이다(상위 class일 수 있다)
     * @property mappingType·[mappingMethod] 메서드 매핑 어노테이션을 찾은 요소다(인터페이스 메서드일 수 있다)
     * @property typeMapping 타입 수준 매핑이다. 없으면 null이다
     */
    data class Handler(
        val handlerType: SpringType,
        val declaringType: SpringType,
        val method: SpringMethod,
        val mappingType: SpringType,
        val mappingMethod: SpringMethod,
        val methodMapping: Mapping,
        val typeMapping: Mapping?,
    )

    /** 스캔 중 센 한계다. */
    class Stats {
        /** 한 요소에 매핑 어노테이션이 둘 이상이다. Spring은 첫 것만 쓰거나 시작을 거부한다. */
        var multipleMappings = 0

        /** 상위 타입 일부가 모델 밖(라이브러리)이라 상속된 매핑을 볼 수 없는 controller다. */
        val partiallyVisibleControllers = sortedSetOf<String>()

        /** 매핑을 선언했지만 `@Controller`가 보이지 않고 모델 밖 상위 타입이 있어 핸들러인지 정하지 못한 class다. */
        val unconfirmedControllers = sortedSetOf<String>()
    }

    val stats = Stats()

    /**
     * 모든 핸들러를 찾는다. 결과는 controller 이름·메서드 이름 순서다. 핸들러가 아닌데 매핑을 선언하고 모델 밖 상위 타입을
     * 가진 class는 그 상위 타입이 `@Controller`를 줄 수 있으므로 [Stats.unconfirmedControllers]로 센다.
     */
    fun handlers(): List<Handler> {
        val (handlerTypes, others) = types.values.filter(::isConcreteClass).partition(::isHandlerType)
        others.filter { declaresMappings(it) && hasInvisibleSupertype(it, mutableSetOf()) }.forEach { stats.unconfirmedControllers += it.name }
        return handlerTypes.sortedBy { it.name }.flatMap(::handlersOf)
    }

    private fun isConcreteClass(type: SpringType): Boolean =
        type.kind == SpringTypeKind.CLASS && !type.isAbstract && type.name !in SpringAnnotations.BUILT_INS

    private fun declaresMappings(type: SpringType): Boolean = (type.annotations + type.methods.flatMap { it.annotations })
        .any { annotation -> merged(annotation, emptySet()) != null }

    private fun isHandlerType(type: SpringType): Boolean {
        return hierarchyHas(type, mutableSetOf()) { annotation -> isStereotype(annotation.type, emptySet()) } ||
            (legacyTypeLevelHandlers && typeMapping(type, mutableSetOf()) != null)
    }

    private fun isStereotype(annotationType: String, visiting: Set<String>): Boolean {
        if (annotationType == SpringAnnotations.CONTROLLER) return true
        if (annotationType in visiting) return false
        return types[annotationType]?.annotations?.any { isStereotype(it.type, visiting + annotationType) } == true
    }

    private fun hierarchyHas(type: SpringType, visited: MutableSet<String>, predicate: (SpringAnnotation) -> Boolean): Boolean {
        if (!visited.add(type.name)) return false
        if (type.annotations.any(predicate)) return true
        return supertypes(type).any { hierarchyHas(it, visited, predicate) }
    }

    private fun supertypes(type: SpringType): List<SpringType> =
        (type.interfaces + listOfNotNull(type.superclass)).mapNotNull(types::get)

    private fun handlersOf(handlerType: SpringType): List<Handler> {
        recordInvisibleSupertypes(handlerType)
        val typeMapping = typeMapping(handlerType, mutableSetOf())
        return candidateMethods(handlerType).mapNotNull { (declaring, method) ->
            val found = searchMethod(declaring, method, mutableSetOf(), own = method) ?: return@mapNotNull null
            Handler(handlerType, declaring, method, found.first, found.second, found.third, typeMapping)
        }
    }

    /** 핸들러 class와 상위 class의 메서드, 재정의되지 않은 인터페이스 default 메서드다(`MethodIntrospector.selectMethods`). */
    private fun candidateMethods(handlerType: SpringType): List<Pair<SpringType, SpringMethod>> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<Pair<SpringType, SpringMethod>>()
        var current: SpringType? = handlerType
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current.name)) {
            val type: SpringType = current
            addNotOverridden(type, type.methods.filter { !it.isSynthetic }, seen, result)
            current = type.superclass?.let(types::get)
        }
        allInterfaces(handlerType, mutableSetOf()).forEach { iface ->
            addNotOverridden(iface, iface.methods.filter { !it.isSynthetic && !it.isAbstract }, seen, result)
        }
        return result
    }

    /**
     * 앞선(더 구체적인) 타입이 같은 시그니처를 선언하지 않은 메서드만 더한다. 같은 타입 안의 오버로드는 소스 원천에서
     * 매개변수 수가 같아도 서로 가리지 않는다.
     */
    private fun addNotOverridden(type: SpringType, methods: List<SpringMethod>, seen: MutableSet<String>, result: MutableList<Pair<SpringType, SpringMethod>>) {
        methods.filter { signature(it) !in seen }.forEach { result += type to it }
        methods.forEach { seen += signature(it) }
    }

    private fun allInterfaces(type: SpringType, visited: MutableSet<String>): List<SpringType> {
        if (!visited.add(type.name)) return emptyList()
        val direct = type.interfaces.mapNotNull(types::get)
        return direct + direct.flatMap { allInterfaces(it, visited) } + (type.superclass?.let(types::get)?.let { allInterfaces(it, visited) }.orEmpty())
    }

    private fun signature(method: SpringMethod): String = method.name + (method.descriptor?.substringBefore(')') ?: "/${method.parameterCount}")

    private fun recordInvisibleSupertypes(type: SpringType) {
        if (hasInvisibleSupertype(type, mutableSetOf())) stats.partiallyVisibleControllers += type.name
    }

    /** 타입 계층에 모델 밖 상위 타입이 있는지 본다. 플랫폼·Spring 패키지는 매핑을 싣지 않으므로 세지 않는다. */
    private fun hasInvisibleSupertype(type: SpringType, visited: MutableSet<String>): Boolean {
        if (!visited.add(type.name)) return false
        return (type.interfaces + listOfNotNull(type.superclass)).any { name ->
            val known = types[name] ?: return@any IGNORED_SUPERTYPE_PACKAGES.none(name::startsWith)
            hasInvisibleSupertype(known, visited)
        }
    }

    /**
     * 메서드 매핑을 요소 → 인터페이스 → 상위 class 순서로 찾는다.
     *
     * @param own 선언 타입의 메서드 자신이다. 오버로드가 같은 매개변수 수를 가져도 이름으로 다시 찾지 않게 한다
     */
    private fun searchMethod(type: SpringType, signature: SpringMethod, visited: MutableSet<String>, own: SpringMethod? = null): Triple<SpringType, SpringMethod, Mapping>? {
        if (!visited.add(type.name)) return null
        (own ?: findMethod(type, signature))?.let { method -> elementMapping(method.annotations)?.let { return Triple(type, method, it) } }
        type.interfaces.mapNotNull(types::get).forEach { iface -> searchMethod(iface, signature, visited)?.let { return it } }
        return type.superclass?.let(types::get)?.let { searchMethod(it, signature, visited) }
    }

    /** 같은 descriptor, 없으면 같은 이름·매개변수 수의 유일한 메서드다(제네릭 상위 메서드의 소거 차이를 받는다). */
    private fun findMethod(type: SpringType, signature: SpringMethod): SpringMethod? {
        val named = type.methods.filter { it.name == signature.name && !it.isSynthetic }
        if (signature.descriptor != null) named.firstOrNull { it.descriptor == signature.descriptor }?.let { return it }
        return named.filter { it.parameterCount == signature.parameterCount }.singleOrNull()
    }

    /** 타입 수준 매핑을 타입 → 인터페이스 → 상위 class 순서로 찾는다. */
    fun typeMapping(type: SpringType, visited: MutableSet<String>): Mapping? {
        if (!visited.add(type.name)) return null
        elementMapping(type.annotations)?.let { return it }
        type.interfaces.mapNotNull(types::get).forEach { iface -> typeMapping(iface, visited)?.let { return it } }
        return type.superclass?.let(types::get)?.let { typeMapping(it, visited) }
    }

    private fun elementMapping(annotations: List<SpringAnnotation>): Mapping? {
        val mappings = annotations.mapNotNull { annotation -> merged(annotation, emptySet())?.let { Mapping(it.first, it.second, annotation) } }
        if (mappings.size > 1) stats.multipleMappings++
        return mappings.firstOrNull()
    }

    // ---- 속성 병합 ----

    private fun merged(annotation: SpringAnnotation, visiting: Set<String>): Pair<String, Map<String, SpringValue>>? {
        if (annotation.type == SpringAnnotations.REQUEST_MAPPING || annotation.type == SpringAnnotations.HTTP_EXCHANGE) {
            return annotation.type to rootAttributes(annotation.type, annotation.attributes)
        }
        if (annotation.type in visiting) return null
        val declaration = types[annotation.type]?.takeIf { it.kind == SpringTypeKind.ANNOTATION } ?: return null
        val inner = visiting + annotation.type
        val meta = declaration.annotations.firstNotNullOfOrNull { merged(it, inner) } ?: return null
        return meta.first to overridden(meta, declaration, annotation, inner)
    }

    private fun rootAttributes(family: String, values: Map<String, SpringValue>): Map<String, SpringValue> {
        val result = linkedMapOf<String, SpringValue>()
        values.forEach { (name, value) -> canonicalRoot(family, name)?.let { key -> result[key] = mirror(result[key], value) } }
        return result
    }

    /** `value`↔`path`(`url`) 거울 속성이다. 둘 다 비어 있지 않고 다르면 Spring이 거부하는 선언이다. */
    private fun mirror(existing: SpringValue?, value: SpringValue): SpringValue = when {
        existing == null || isEmpty(existing) -> value
        isEmpty(value) || existing == value -> existing
        else -> SpringValue.Unresolved
    }

    private fun isEmpty(value: SpringValue): Boolean = value is SpringValue.Strings && value.values.all(String::isEmpty) ||
        value is SpringValue.Enums && value.names.isEmpty()

    private fun overridden(meta: Pair<String, Map<String, SpringValue>>, declaration: SpringType, annotation: SpringAnnotation, visiting: Set<String>): Map<String, SpringValue> {
        val overrides = linkedMapOf<String, MutableList<Pair<SpringValue, Boolean>>>()
        declaration.methods.forEach { member ->
            val value = annotation.attributes[member.name] ?: member.defaultValue ?: EMPTY
            val explicit = annotation.attributes.containsKey(member.name) && value != (member.defaultValue ?: EMPTY) && !isEmpty(value)
            aliasTargets(declaration, member).mapNotNull { canonical(it, member.name, meta.first, visiting) }.distinct().forEach { key ->
                overrides.getOrPut(key) { mutableListOf() } += value to explicit
            }
        }
        val result = meta.second.toMutableMap()
        overrides.forEach { (key, candidates) ->
            val explicit = candidates.filter { it.second }.map { it.first }.distinct()
            result[key] = when (explicit.size) { 0 -> candidates.first().first; 1 -> explicit.single(); else -> SpringValue.Unresolved }
        }
        return result
    }

    /** 속성의 다른 어노테이션 대상 별칭이다. 같은 타입 안 거울 속성의 대상도 합친다. */
    private fun aliasTargets(declaration: SpringType, member: SpringMethod): List<SpringAliasTarget> {
        val (local, external) = member.aliases.partition { it.annotation == null || it.annotation == declaration.name }
        val mirrored = local.mapNotNull { target -> declaration.methods.firstOrNull { it.name == target.attribute && it !== member } }
            .flatMap { mirror -> mirror.aliases.filter { it.annotation != null && it.annotation != declaration.name }.map { it.withDefaultName(mirror.name) } }
        return external.map { it.withDefaultName(member.name) } + mirrored
    }

    private fun SpringAliasTarget.withDefaultName(name: String): SpringAliasTarget = if (attribute.isEmpty()) copy(attribute = name) else this

    /** 별칭 대상을 root 계열(`@RequestMapping`·`@HttpExchange`)의 정규 속성 이름까지 따라간다. */
    private fun canonical(target: SpringAliasTarget, memberName: String, family: String, visiting: Set<String>): String? {
        val annotationType = target.annotation ?: return null
        val attribute = target.attribute.ifEmpty { memberName }
        if (annotationType == SpringAnnotations.REQUEST_MAPPING || annotationType == SpringAnnotations.HTTP_EXCHANGE) {
            return if (annotationType == family) canonicalRoot(family, attribute) else null
        }
        if (annotationType in visiting && annotationType !in types) return null
        val declaration = types[annotationType] ?: return null
        val member = declaration.methods.firstOrNull { it.name == attribute } ?: return null
        return aliasTargets(declaration, member).firstNotNullOfOrNull { canonical(it, member.name, family, visiting + annotationType) }
    }

    private fun canonicalRoot(family: String, attribute: String): String? = if (family == SpringAnnotations.REQUEST_MAPPING) {
        when (attribute) {
            "value", "path" -> "path"
            "method", "params", "headers", "consumes", "produces", "version" -> attribute
            else -> null
        }
    } else {
        when (attribute) {
            "value", "url" -> "path"
            "method", "headers", "version" -> attribute
            "contentType" -> "consumes"
            "accept" -> "produces"
            else -> null
        }
    }

    private companion object {
        val EMPTY = SpringValue.Strings(emptyList())

        /** 매핑을 싣지 않는 플랫폼·프레임워크 상위 타입 패키지다. 이 밖의 모델 밖 상위 타입만 가시성 한계로 센다. */
        val IGNORED_SUPERTYPE_PACKAGES = listOf("java.", "javax.", "jakarta.", "kotlin.", "org.springframework.")
    }
}
