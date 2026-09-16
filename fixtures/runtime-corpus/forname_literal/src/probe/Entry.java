package probe;
public class Entry {
public static class Target {}
public static void main(String[] args) throws Exception { System.out.println(Class.forName("probe.Entry$Target").getName()); }
}
