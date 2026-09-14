package probe;
public final class Entry {
  static final class Names { String value(long ignored, String suffix) { return "probe." + suffix; } }
  public static void main(String[] args) throws Exception { Class.forName(new Names().value(7L, "Used")).getDeclaredConstructor().newInstance(); }
}
class Used { public Used() { System.out.println("USED"); } }
class Unused { public Unused() { System.out.println("UNUSED"); } }
