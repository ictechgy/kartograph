package dev.kartograph.fixture

import androidx.annotation.Keep
import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class ClassValueRef(val target: KClass<*>)

class AnnotationValueTarget

class EnclosingContainer {
    class UsedNested
}

class ForNameTarget

internal fun usedTopLevelHelper(): String = "helper"

internal fun unusedTopLevelFunction(): String = "unused"

internal var unusedTopLevelProperty: Int = 0

internal inline fun unusedInlineTopLevel(block: () -> Int): Int = block()

@Keep
@ClassValueRef(AnnotationValueTarget::class)
class TopLevelAnchor {
    fun useTopLevel(): String = usedTopLevelHelper()
    fun useNested(): EnclosingContainer.UsedNested = EnclosingContainer.UsedNested()
    fun reflect(): Class<*> = Class.forName("dev.kartograph.fixture.ForNameTarget")
}
