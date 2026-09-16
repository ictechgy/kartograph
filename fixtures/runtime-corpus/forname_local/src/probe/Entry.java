package probe;
public class Entry {
public static class Target {}
public static void main(String[] args) throws Exception { String name="probe.Entry$Target"; System.out.println(Class.forName(name).getName()); }
}
