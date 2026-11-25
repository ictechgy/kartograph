package dev.kartograph.index.fixture.typeannotations

/** kotlinc가 JVM 타입 어노테이션(`METHOD_TYPE_PARAMETER`)으로 남기는 타입 매개변수 어노테이션이다. */
@Target(AnnotationTarget.TYPE_PARAMETER)
internal annotation class OnKotlinTypeParameter

/** 타입 사용 위치 어노테이션이다. `-Xemit-jvm-type-annotations` 없이는 Kotlin metadata에만 남는다. */
@Target(AnnotationTarget.TYPE)
internal annotation class OnKotlinType

/** 두 어노테이션을 각각 쓰는 함수를 가진 도달 가능한 owner다. */
internal class TypeAnnotationHolder {
    /** 타입 매개변수 어노테이션을 쓴다. */
    fun <@OnKotlinTypeParameter T> identity(value: T): T = value

    /** 타입 인자 어노테이션을 쓴다. */
    fun count(values: List<@OnKotlinType String>): Int = values.size
}

/** 두 함수를 부르는 진입점이다. */
internal fun useTypeAnnotations(holder: TypeAnnotationHolder): Int = holder.identity(1) + holder.count(listOf("a"))
