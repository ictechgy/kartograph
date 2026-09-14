package probe;
public final class Entry {
  static class Names { String value() { return "probe.Unused"; } }
  static final class Selected extends Names { @Override String value() { return "probe.Used"; } }
  public static void main(String[] args) throws Exception { Names selected = new Selected(); Class.forName(selected.value()).getDeclaredConstructor().newInstance(); }
}
class Used { public Used() { System.out.println("USED"); } }
class Unused { public Unused() { System.out.println("UNUSED"); } }
