package probe;
public class Entry {
 public static class Unused {}
 public static class Used { public Used(){System.out.println("USED");} }
 public static void main(String[] args) { new Used(); }
}
