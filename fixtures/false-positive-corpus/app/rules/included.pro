-keep class dev.kartograph.fixture.KeptByClasspath* extends dev.kartograph.fixture.library.ExternalRuleTarget
-keep @dev.kartograph.fixture.RuleMarker class dev.kartograph.fixture.*
-keepclasseswithmembers class dev.kartograph.fixture.Conditional* {
    @dev.kartograph.fixture.RuleMemberMarker <methods>;
}
-keep final class dev.kartograph.fixture.*FinalRule
-keep class dev.kartograph.fixture.KeptWithAllMembers { *; }
-keepclasseswithmembers class dev.kartograph.fixture.SignatureKept {
    public int onEvent(java.lang.String);
}
-keep class dev.kartograph.fixture.PlainSignatureKept { <init>(...); }
