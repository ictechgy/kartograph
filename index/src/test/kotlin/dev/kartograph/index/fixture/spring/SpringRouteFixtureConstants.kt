package dev.kartograph.index.fixture.spring

/** 매핑 경로의 최상위 상수다. 바이트코드에는 어노테이션 값으로 접혀 들어간다. */
const val FIXTURE_BASE = "/fixture"

/** object 상수와 문자열 템플릿이다. */
object FixtureRoutes {
    /** 템플릿으로 만든 상수다. */
    const val ITEMS = "$FIXTURE_BASE/items"
}
