package probe;
public class JavaConstants {
    public static final String USED = "same";
    public static final String UNUSED = "same";
    public static String read() { return USED; }
    public static String shadow() { String USED = "same"; return USED; }
}
