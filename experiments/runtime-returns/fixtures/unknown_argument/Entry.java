package probe;

public class Entry {
    public static class Used { public Used() { System.out.println("USED"); } }
    public static class Unused {}
    public static void main(String[] args) throws Exception {
        Class.forName(Names.name(args[0])).getDeclaredConstructor().newInstance();
    }
}

class Names {
    static String name(String suffix) { return "probe.Entry$" + suffix; }
}
