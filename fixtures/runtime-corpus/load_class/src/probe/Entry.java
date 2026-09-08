package probe;
public class Entry {
public static class Target {}
public static void main(String[] args) throws Exception { System.out.println(Entry.class.getClassLoader().loadClass("probe.Entry$Target").getName()); }
}
