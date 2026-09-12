package probe;

public class Entry {
    private static String typeName = name();
    private static String name() { return "probe.Entry$Target"; }
    public static class Target { public Target() { new Used(); } }
    public static class Used { public Used() { System.out.println("USED"); } }
    public static class Unused {}

    public static void main(String[] args) throws Exception {
        Class.forName(typeName).getDeclaredConstructor().newInstance();
    }
}
