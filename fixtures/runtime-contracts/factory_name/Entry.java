package probe;
public class Entry {
 public static class Unused {}
 public static class Target { public Target(){new Used();} }
 public static class Used { public Used(){System.out.println("USED");} }
 public static String name(){return "probe.Entry$Target";}
 public static void main(String[] args) throws Exception { Class.forName(name()).getDeclaredConstructor().newInstance(); }
}
