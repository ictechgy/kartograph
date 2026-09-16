package probe;

public class Entry {
    public static class Used { public Used() { System.out.println("USED"); } }
    public static class Unused {}
    public static void main(String[] args) { Names.run("selected"); }
}

class Names {
    static void run(String value) { new Entry.Used(); }
    static void run(int value) { new Entry.Unused(); }
}
