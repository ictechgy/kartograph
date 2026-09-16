package probe;
public class Entry {
 public static class Unused {}
 public static class Holder { public static Class<?> type = Target.class; }
 public static class Target { public Target(){new Used();} }
 public static class Used { public Used(){System.out.println("USED");} }
 public static void main(String[] args) throws Exception { ((Class<?>)Holder.class.getDeclaredField("type").get(null)).getDeclaredConstructor().newInstance(); }
}
