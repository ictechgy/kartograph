package probe;
public class Entry {
public static class Constants { public static final int USED=7; }
public static class UnusedConstants { public static final int UNUSED=9; }
public static void main(String[] args) { System.out.println(Constants.USED); }
}
