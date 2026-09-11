package probe;

public class Entry {
    public static class Used { public Used() { System.out.println("USED"); } }
    public static class Unused {}
    public static void main(String[] args) throws Exception {
        Class.forName(Names.wrap("Used")).getDeclaredConstructor().newInstance();
    }
}

class Names {
    static String wrap(String suffix) { return name(7L, suffix); }
    static String name(long ignored, String suffix) { return "probe.Entry$" + suffix; }
    static String name(int ignored) { return "probe.Entry$Unused"; }
}
