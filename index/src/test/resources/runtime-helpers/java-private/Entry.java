package probe;
public class Entry {
  private String value() { return "probe.Used"; }
  public static void main(String[] args) throws Exception { Class.forName(new Entry().value()).getDeclaredConstructor().newInstance(); }
}
class Used { public Used() { System.out.println("USED"); } }
class Unused { public Unused() { System.out.println("UNUSED"); } }
