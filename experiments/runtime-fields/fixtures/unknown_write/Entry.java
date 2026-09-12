package probe;

public class Entry {
    public static class Holder { public static Class<?> type = Initial.class; }
    public static class Initial { public Initial() {} }
    public static class Target { public Target() { new Used(); } }
    public static class Used { public Used() { System.out.println("USED"); } }
    public static class Unused {}

    public static void main(String[] args) throws Exception {
        java.lang.reflect.Field field = Holder.class.getDeclaredField("type");
        field.set(null, Class.forName(args[0]));
        Class<?> type = (Class<?>) field.get(null);
        type.getDeclaredConstructor().newInstance();
    }
}
