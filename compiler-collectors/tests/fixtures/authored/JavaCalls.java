package authored;
public class JavaCalls {
    public static class Printable { public String toString() { return "printable"; } }
    public static String implicit(Printable receiver) { return "value=" + receiver; }
    public static String explicit(Printable receiver) { return "value=" + receiver.toString(); }
    public static String zero() { return "literal"; }
    public static String choose(boolean flag, Printable receiver) {
        if (flag) return receiver.toString();
        return "empty";
    }
    public static int external(int value) { return Math.abs(value); }
    public static Object unsupportedNew() { return new Object(); }
    public static Runnable unsupportedLambda(Printable receiver) { return () -> receiver.toString(); }
    public static java.util.function.Supplier<String> unsupportedReference(Printable receiver) { return receiver::toString; }
}
