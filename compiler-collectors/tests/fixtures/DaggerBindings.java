package fixture;

import dagger.Binds;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import javax.inject.Inject;
import javax.inject.Named;

public final class DaggerBindings {
    public interface Service {
        String value();
    }

    public static final class Dependency {
        @Inject public Dependency() {}
    }

    public static final class Selected implements Service {
        @Inject public Selected(Dependency dependency) {}
        @Override public String value() { return "selected"; }
    }

    public static final class Unused implements Service {
        @Inject public Unused() {}
        @Override public String value() { return "unused"; }
    }

    @Module
    public abstract static class Bindings {
        @Binds @Named("selected")
        abstract Service selected(Selected value);

        @Provides @Named("unused")
        static Service unused(Unused value) { return value; }
    }

    @Component(modules = Bindings.class)
    public interface App {
        @Named("selected") Service service();
    }

    public static void main(String[] args) {
        System.out.println(DaggerDaggerBindings_App.create().service().value());
    }
}
