package probe;

public class Entry {
    public static class Holder { public static Class<?> type = Target.class; }
    public static class Target { public Target() { new Used(); } }
    public static class Used { public Used() { System.out.println("USED"); } }
    public static class Unused {}

    public static void main(String[] args) throws Exception {
        Class<?> type = (Class<?>) Holder.class.getDeclaredField("type").get(null);
        type.getDeclaredConstructor().newInstance();
    }
}
