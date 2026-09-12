package fixture;

@dagger.Module
public final class LibraryModule {
    @dagger.Provides static String value() { return "unused"; }
}
