package dev.kartograph.index.fixture;

public final class EnumInventoryJavaFixture {
    private EnumInventoryJavaFixture() {}

    public enum State {
        BETA,
        ALPHA {
            @Override public String label() { return "alpha"; }
        };

        public static final State ALIAS = BETA;
        public String label() { return name(); }
    }

    public enum Empty {}
}
