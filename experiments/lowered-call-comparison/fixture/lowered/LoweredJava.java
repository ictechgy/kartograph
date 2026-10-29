package lowered;

import java.util.function.IntUnaryOperator;

public class LoweredJava {
    public static int inlineLeaf(int value) { return value; }
    public static int defaultSeed() { return 7; }
    public static int lambdaLeaf(int value) { return value; }
    public static int acceptKotlinLambda(IntUnaryOperator operation) { return operation.applyAsInt(7); }
    public static int javaInline(LoweredKotlin kotlin) { return kotlin.inlineStep(7); }
    public static int javaExplicitDefault(LoweredKotlin kotlin) { return kotlin.withDefault(7); }
    public static int javaToKotlinLambda(LoweredKotlin kotlin) { return kotlin.acceptJavaLambda(value -> lambdaLeaf(value)); }
}
