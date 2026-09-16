package probe;

public class Entry {
    public static class Used { public Used() { System.out.println("USED"); } }
    public static class Unused {}
    public static void main(String[] args) throws Exception {
        Class.forName(Names.name()).getDeclaredConstructor().newInstance();
    }
}

class Names {
    static String name() { return "probe.Entry$Used"; }
}
