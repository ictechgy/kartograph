package dev.kartograph.index.fixture

internal fun usedTopLevelFunction(): String = "used"

internal fun unusedTopLevelFunction(): String = "unused"

internal inline fun inlinedTopLevelFunction(block: () -> Int): Int = block()

var topLevelProperty: Int = 0

fun main() {
    usedTopLevelFunction()
}

internal class FacadeConsumer {
    fun consume(): String = usedTopLevelFunction() + inlinedTopLevelFunction { 1 }
}
