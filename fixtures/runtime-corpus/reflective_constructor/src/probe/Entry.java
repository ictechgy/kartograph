package probe;
public class Entry {
public static class Target { public Target(){ new ConstructorBody(); } }
public static class ConstructorBody { public ConstructorBody(){System.out.println("CONSTRUCTOR_BODY_EXECUTED");} }
public static void main(String[] args) throws Exception { Class.forName("probe.Entry$Target").getDeclaredConstructor().newInstance(); }
}
