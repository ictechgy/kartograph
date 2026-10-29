package lowered

import java.util.function.IntUnaryOperator

class LoweredKotlin {
    inline fun inlineStep(value: Int): Int = LoweredJava.inlineLeaf(value)
    fun withDefault(value: Int = LoweredJava.defaultSeed()): Int = value
    fun kotlinInline(): Int = inlineStep(7)
    fun kotlinDefault(): Int = withDefault()
    fun kotlinToJavaLambda(): Int = LoweredJava.acceptKotlinLambda { LoweredJava.lambdaLeaf(it) }
    fun acceptJavaLambda(operation: IntUnaryOperator): Int = operation.applyAsInt(7)
    fun unused(): Int = 0
}
