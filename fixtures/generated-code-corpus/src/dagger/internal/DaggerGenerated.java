package dagger.internal;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

// 실제 Dagger 산출물의 CLASS-retention marker를 재현하는 독립 표본이다.
@Retention(RetentionPolicy.CLASS)
public @interface DaggerGenerated {}
