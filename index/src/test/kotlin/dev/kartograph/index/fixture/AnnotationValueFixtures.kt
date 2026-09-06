package dev.kartograph.index.fixture

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
internal annotation class ClassValueProbe(val target: KClass<*>)

@ClassValueProbe(AnnotationValueReferenced::class)
internal class AnnotationValueHolder

internal class AnnotationValueReferenced
