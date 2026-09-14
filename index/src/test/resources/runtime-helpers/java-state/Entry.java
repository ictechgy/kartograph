package probe;
public final class Entry {
  static final class Names { private final String value; Names(String value) { this.value = value; } String value() { return value; } }
  public static void main(String[] args) throws Exception { Class.forName(new Names(args[0]).value()).getDeclaredConstructor().newInstance(); }
}
class Used { public Used() { System.out.println("USED"); } }
class Unused { public Unused() { System.out.println("UNUSED"); } }
