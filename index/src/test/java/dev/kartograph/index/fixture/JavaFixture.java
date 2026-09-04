package dev.kartograph.index.fixture;

public class JavaFixture {
    protected String protectedValue;

    private void hidden() {
    }

    void packageVisible() {
    }

    public Runnable methodReference() {
        return JavaFixture::hiddenStatic;
    }

    public Object constructedType() {
        return new ConstructedOnly();
    }

    public Class<?> classLiteral() {
        return LiteralOnly.class;
    }

    public Class<?> arrayClassLiteral() {
        return ArrayLiteralOnly[].class;
    }

    private static void hiddenStatic() {
    }
}

final class ConstructedOnly {
}

final class LiteralOnly {
}

final class ArrayLiteralOnly {
}

interface DispatchContract {
    Object invoke();
}

final class DispatchImplementation implements DispatchContract {
    @Override
    public Object invoke() {
        return new DispatchDependency();
    }
}

final class DispatchDependency {
}
