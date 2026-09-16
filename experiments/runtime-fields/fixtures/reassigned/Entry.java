package probe;

public class Entry {
    public static class Holder { public static Class<?> type = Initial.class; }
    public static class Initial { public Initial() {} }
    public static class Target { public Target() { new Used(); } }
    public static class Used { public Used() { System.out.println("USED"); } }
    public static class Unused {}

    private static void select() { Holder.type = Target.class; }

    public static void main(String[] args) throws Exception {
        select();
        Class<?> type = (Class<?>) Holder.class.getDeclaredField("type").get(null);
        type.getDeclaredConstructor().newInstance();
    }
}
