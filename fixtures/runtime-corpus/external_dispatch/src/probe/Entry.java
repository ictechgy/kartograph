package probe;
public class Entry {
public static class Provider implements java.util.function.Supplier<String> {
 public String get(){ new CallbackBody(); return "done"; }
}
public static class CallbackBody { public CallbackBody(){System.out.println("CALLBACK_BODY_EXECUTED");} }
public static void main(String[] args) { java.util.function.Supplier<String> provider=new Provider(); System.out.println(provider.get()); }
}
