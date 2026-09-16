package probe;
public class Entry {
 public interface Step { void run(); }
 public static class Marker { public static boolean live; public static boolean dormant; }
 public static class Live implements Step { public void run(){ Marker.live=true; } }
 public static class Dormant implements Step { public void run(){ Marker.dormant=true; } }
 public static void execute(Step step){if(step!=null)step.run();}
 public static void main(String[] args) throws Exception { new Dormant(); Step step=new Live(); execute(step); }
}
