package probe;
public class Entry {
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface Config { Class<?> value() default DefaultTarget.class; }
public static class DefaultTarget { public DefaultTarget(){System.out.println("ANNOTATION_DEFAULT_EXECUTED");} }
@Config public static class Configured {}
public static void main(String[] args) throws Exception { Configured.class.getAnnotation(Config.class).value().getDeclaredConstructor().newInstance(); }
}
