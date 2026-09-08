package probe;
public class Entry {
public static class Provider implements Runnable { public Provider(){} public void run(){System.out.println("SERVICE_PROVIDER_EXECUTED");} }
public static void main(String[] args) { for(Runnable provider: java.util.ServiceLoader.load(Runnable.class)) provider.run(); }
}
