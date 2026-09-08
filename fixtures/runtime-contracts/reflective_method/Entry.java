package probe;
public class Entry {
 public static class Unused {}
 public static class Target { public static void run(){new Used();} }
 public static class Used { public Used(){System.out.println("USED");} }
 public static void main(String[] args) throws Exception { Target.class.getDeclaredMethod("run").invoke(null); }
}
