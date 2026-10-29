package inventory;
public class JavaBodies extends JavaBase<String> {
    static String staticValue = mark("static-property");
    String value = mark("instance-property");
    static { mark("static-block"); }
    { mark("instance-block"); }
    public JavaBodies() { super(1); mark("constructor"); }
    public JavaBodies(int n) { this(); mark("secondary"); }
    static String mark(String value) { return value; }
    static void empty() {}
    @Override public String map(String value) { return value; }
    public String overload() { return mark("overload"); }
    public String overload(String value) { return mark(value); }
    void locals() {
        class Local { void zero() {} }
        Runnable anonymous = new Runnable() { public void run() { mark("anonymous"); } };
        Runnable lambda = () -> mark("lambda");
    }
    static class Implicit {}
    static class Explicit { Explicit() {} }
    static class Nested { void zero() {} }
    abstract static class Abstract { abstract void missing(); native void nativeMethod(); }
}
class JavaBase<T> {
    JavaBase(int value) {}
    public T map(T value) { return value; }
}
