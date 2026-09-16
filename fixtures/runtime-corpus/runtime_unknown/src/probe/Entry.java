package probe;
public class Entry {
 public abstract static class MissingImplementation implements java.util.function.Supplier<String> {}
 public static void main(String[] args) throws Exception {
  String name=args[0];
  Class.forName(name);
  Entry.class.getClassLoader().loadClass(name);
  Object value=Class.forName(name).getDeclaredConstructor().newInstance();
  java.util.ServiceLoader.load(Class.forName(name)).iterator().hasNext();
  ((java.util.function.Supplier<?>)value).get();
 }
}
