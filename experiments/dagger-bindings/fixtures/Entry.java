package probe;
import dagger.Component;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import javax.inject.Inject;
import javax.inject.Named;

public class Entry {
    static final java.util.List<String> events = new java.util.ArrayList<>();
    public interface Service { String value(); }
    public static class Dependency { @Inject public Dependency() {events.add("dependency");} }
    public static class Selected implements Service {
        @Inject public Selected(Dependency dependency) {events.add("selected");}
        public String value(){return "selected";}
    }
    public static class Unused { @Inject public Unused() {events.add("unused");} }
    @Module public abstract static class Bindings {
        @Binds @Named("selected") abstract Service selected(Selected value);
        @Provides @Named("unused") static Service unused(Unused value){return () -> "unused";}
    }
    @Component(modules=Bindings.class) public interface App {
        @Named("selected") Service service();
    }
    public static void main(String[] args) {
        System.out.println(DaggerEntry_App.create().service().value());
        System.out.println(String.join(",", events));
    }
}
