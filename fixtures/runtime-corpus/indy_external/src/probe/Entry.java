package probe;
public class Entry {
 public static void main(String[] args) {
  java.util.function.Supplier<String> supplier = "value"::trim;
  System.out.println(supplier.get());
 }
}
