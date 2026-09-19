package dev.kartograph.index.fixture

public class AbiExposedType
public class AbiInternalType
public class AbiBodyType
public class AbiInlineType
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.BINARY)
public annotation class AbiPrivateFieldAnnotation
@Target(AnnotationTarget.PROPERTY_SETTER)
@Retention(AnnotationRetention.BINARY)
public annotation class AbiPrivateSetterAnnotation
public class AbiAliasType

public class DependencyAbiPublic {
    @field:AbiPrivateFieldAnnotation
    public val backingField: String = ""
    @set:AbiPrivateSetterAnnotation
    public var privateSetter: String = ""
        private set
    public fun exposed(): List<AbiExposedType> = emptyList()
    internal fun internalOnly(): AbiInternalType? = null
    public fun bodyOnly(): String = AbiBodyType().toString()
    public inline fun inlineBody(): String = AbiInlineType().toString()
}

internal class DependencyAbiInternal {
    public fun notExported(): AbiInternalType? = null
}

public class AbiAnnotationType
public class AbiAnnotationExtra
public class AbiNestedAnnotationType
public enum class AbiAnnotationChoice { FIRST }
public annotation class AbiNestedAnnotation(val type: kotlin.reflect.KClass<*>)
@Target(AnnotationTarget.TYPEALIAS)
@Retention(AnnotationRetention.BINARY)
public annotation class AbiAliasAnnotation(
    val type: kotlin.reflect.KClass<*>, val extras: Array<kotlin.reflect.KClass<*>>,
    val nested: AbiNestedAnnotation, val choice: AbiAnnotationChoice, val label: String,
)
@AbiAliasAnnotation(AbiAnnotationType::class, [AbiAnnotationExtra::class],
    AbiNestedAnnotation(AbiNestedAnnotationType::class), AbiAnnotationChoice.FIRST, "literal")
public typealias PublicDependencyAlias = AbiAliasType
internal fun createsFileFacade(): Int = 1

public class DependencyAbiAliasUser {
    public fun exposeAlias(value: PublicDependencyAlias): PublicDependencyAlias = value
}

public class AbiPublishedInlineType
@PublishedApi
internal inline fun publishedInlineHelper(): String = AbiPublishedInlineType().toString()

public class AbiPublishedPropertyType
public class AbiPublishedClassType
@PublishedApi
internal inline val publishedInlineProperty: String
    get() = AbiPublishedPropertyType().toString()
@PublishedApi
internal class DependencyAbiPublishedClass {
    public fun exposed(): AbiPublishedClassType? = null
}
