# Exact class rule used by the retention corpus.
-keep class dev.kartograph.fixture.KeptByRule
-keep class dev.kartograph.fixture.KeptByWildcard*
-keep public class dev.kartograph.fixture.KeptByExtends* extends dev.kartograph.fixture.RuleBase
-include rules/included.pro
# Member fixture는 public entry에서 시작하고 reflection-only private method를 별도 보존한다.
-keep class dev.kartograph.fixture.PrivateMemberFixture { public int entry(); }
-keepclassmembers class dev.kartograph.fixture.PrivateMemberFixture { private int reflected(); }
