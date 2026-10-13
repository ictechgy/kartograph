package probe;

public class PsiJava {
    public static int leaf(int value) { return value; }
    public static String leaf(String value) { return value; }
    public static String caller() { return new PsiKotlin().leaf(leaf("seven")); }
    public static int unused() { return 0; }
}
