package evaluation;

import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/** 표시명 중복·중첩·동적 실행·skip과 assertion 변경을 실제 엔진에서 검증한다. */
public class ObserverContract {
    @ParameterizedTest(name = "same {0}")
    @ValueSource(ints = {1, 2})
    void alpha(int value) { assertEquals(value, value); }

    @ParameterizedTest(name = "same {0}")
    @ValueSource(ints = {1, 2})
    void beta(int value) { assertEquals(value, value + (Boolean.getBoolean("evaluation.changed") ? 1 : 0)); }

    @TestFactory
    Stream<DynamicTest> dynamicCases() {
        return Stream.of(dynamicTest("same", () -> assertEquals(1, 1)), dynamicTest("same", () -> assertEquals(2, 2)));
    }

    @Test @Disabled("observer control")
    void skippedMethod() { throw new AssertionError("must not run"); }

    @Nested
    class Group {
        @Test void nestedMethod() { assertEquals(3, 3); }
    }
}
