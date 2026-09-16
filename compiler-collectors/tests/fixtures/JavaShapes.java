package fixture;

public class JavaShapes {
    static final String USED = "same";
    static final String UNUSED = "same";
    static String first() { return USED; }

    class Inner {
        Inner() { System.out.println(USED); }
    }

    enum Mode {
        ONE;
        Mode() { System.out.println(USED); }
    }

    static class Nested {
        Nested() { System.out.println(USED); }
    }
}

class Helper {
    static String second() { return JavaShapes.USED; }
}
