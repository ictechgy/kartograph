package probe;
public class Entry {
 public static class A {}
 public static class B {}
 public @interface Nested { Class<?> type(); }
 public @interface Defaults {
  Class<?>[] types() default {A.class, B.class};
  java.lang.annotation.RetentionPolicy policy() default java.lang.annotation.RetentionPolicy.RUNTIME;
  Nested nested() default @Nested(type=A.class);
 }
 public static void main(String[] args) {}
}
