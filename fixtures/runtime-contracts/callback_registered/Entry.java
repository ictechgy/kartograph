package probe;
public class Entry {
 public static class Unused {}
 public static class Used { public Used(){System.out.println("USED");} }
 public static class Callback implements Runnable { public void run(){new Used();} }
 public static class Registry { Runnable callback; void register(Runnable next){callback=next;} void fire(){if(callback!=null)callback.run();} }
 public static void main(String[] args) { Registry registry=new Registry(); registry.register(new Callback()); registry.fire(); }
}
