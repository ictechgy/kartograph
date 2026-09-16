package probe;

public class Entry {
    public static class Parent { public static Class<?> type = Target.class; }
    public static class Child extends Parent {}
    public static class Target { public Target() { new Used(); } }
    public static class Used { public Used() { System.out.println("USED"); } }
    public static class Unused {}

    public static void main(String[] args) throws Exception {
        Class<?> type = (Class<?>) Child.class.getField("type").get(null);
        type.getDeclaredConstructor().newInstance();
    }
}
