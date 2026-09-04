package dev.kartograph.index

import dev.kartograph.core.KeepDeclarationKind
import dev.kartograph.core.KeepMemberCondition
import dev.kartograph.core.KeepMemberKind
import dev.kartograph.core.KeepRule
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.SourceLocation
import dev.kartograph.core.Visibility
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

class KeepRuleScannerTest {
    @Test
    fun `reads class specifications and ignores rules that allow shrinking`(@TempDir projectRoot: Path) {
        val rules = projectRoot.resolve("app/proguard-rules.pro")
        rules.parent.createDirectories()
        rules.writeText(
            """
                # Direct retention roots
                -keep class dev.fixture.Exact
                -keep,allowoptimization,allowobfuscation interface dev.fixture.Contract {
                    *;
                }
                -keep,allowshrinking class dev.fixture.Shrinkable
                -keepnames class dev.fixture.NameOnly
                -keep enum dev.fixture.Inline { *; }
                -keep public protected class dev.fixture.** extends dev.base.*
                -keep @dev.fixture.Marker class dev.annotated.**
            """.trimIndent(),
        )

        val parsedRules = KeepRuleScanner(projectRoot).scan(listOf(rules))

        assertEquals(
            listOf(
                KeepRule(KeepDeclarationKind.CLASS, "dev.fixture.Exact", location = location(2)),
                KeepRule(
                    KeepDeclarationKind.INTERFACE,
                    "dev.fixture.Contract",
                    location = location(3),
                    keepAllMembers = true,
                ),
                KeepRule(
                    KeepDeclarationKind.ENUM,
                    "dev.fixture.Inline",
                    location = location(8),
                    keepAllMembers = true,
                ),
                KeepRule(
                    KeepDeclarationKind.CLASS,
                    "dev.fixture.**",
                    extendsPattern = "dev.base.*",
                    location = location(9),
                    requiredJvmVisibilities = setOf(Visibility.PUBLIC, Visibility.PROTECTED),
                ),
                KeepRule(
                    KeepDeclarationKind.CLASS,
                    "dev.annotated.**",
                    location = location(10),
                    requiredAnnotationPattern = "dev.fixture.Marker",
                ),
            ),
            parsedRules,
        )
    }

    @Test
    fun `rejects unsupported keep patterns without exposing absolute paths`(@TempDir projectRoot: Path) {
        val rules = projectRoot.resolve("proguard-rules.pro")
        rules.writeText("-keep strictfp class dev.fixture.Exact")

        val error = assertFailsWith<KeepRuleScanningException> {
            KeepRuleScanner(projectRoot).scan(listOf(rules))
        }

        assertEquals("unsupported keep rule at proguard-rules.pro:1", error.message)
        assertFalse(error.message.orEmpty().contains(projectRoot.toString()))
    }

    @Test
    fun `parses required and forbidden JVM access flags`(@TempDir projectRoot: Path) {
        val rules = projectRoot.resolve("proguard-rules.pro")
        rules.writeText("-keep final !abstract !private class dev.fixture.Entry")

        val parsedRule = KeepRuleScanner(projectRoot).scan(listOf(rules)).single()

        assertEquals(setOf(JvmModifier.FINAL), parsedRule.requiredJvmModifiers)
        assertEquals(setOf(JvmModifier.ABSTRACT), parsedRule.forbiddenJvmModifiers)
        assertEquals(setOf(Visibility.PRIVATE), parsedRule.forbiddenJvmVisibilities)
    }

    @Test
    fun `reads nested include directives relative to their containing file`(@TempDir projectRoot: Path) {
        val rules = projectRoot.resolve("proguard-rules.pro")
        val nestedDirectory = projectRoot.resolve("rules").createDirectories()
        rules.writeText("-include rules/first.pro")
        nestedDirectory.resolve("first.pro").writeText(
            """
                -keep class dev.fixture.First
                @second.pro
            """.trimIndent(),
        )
        nestedDirectory.resolve("second.pro").writeText("-keep class dev.fixture.Second")

        val parsedRules = KeepRuleScanner(projectRoot).scan(listOf(rules))

        assertEquals(
            listOf(
                KeepRule(
                    KeepDeclarationKind.CLASS,
                    "dev.fixture.First",
                    location = SourceLocation("rules/first.pro", 1),
                ),
                KeepRule(
                    KeepDeclarationKind.CLASS,
                    "dev.fixture.Second",
                    location = SourceLocation("rules/second.pro", 1),
                ),
            ),
            parsedRules,
        )
    }

    @Test
    fun `rejects include cycles with relative evidence`(@TempDir projectRoot: Path) {
        val rules = projectRoot.resolve("proguard-rules.pro")
        val nested = projectRoot.resolve("rules/nested.pro")
        nested.parent.createDirectories()
        rules.writeText("-include rules/nested.pro")
        nested.writeText("-include ../proguard-rules.pro")

        val error = assertFailsWith<KeepRuleScanningException> {
            KeepRuleScanner(projectRoot).scan(listOf(rules))
        }

        assertEquals("cyclic keep rule include at rules/nested.pro:1", error.message)
    }

    @Test
    fun `rejects includes outside the allowed root without reading them`(@TempDir directory: Path) {
        val projectRoot = directory.resolve("project").createDirectories()
        val rules = projectRoot.resolve("proguard-rules.pro")
        directory.resolve("outside.pro").writeText("-keep class dev.fixture.Outside")
        rules.writeText("-include ../outside.pro")

        val error = assertFailsWith<KeepRuleScanningException> {
            KeepRuleScanner(projectRoot).scan(listOf(rules))
        }

        assertEquals("included keep rule file is outside the allowed root at proguard-rules.pro:1", error.message)
        assertFalse(error.message.orEmpty().contains(directory.toString()))
    }

    @Test
    fun `allows explicit external rule roots without exposing their absolute path`(@TempDir directory: Path) {
        val projectRoot = directory.resolve("project").createDirectories()
        val externalRules = directory.resolve("sdk/proguard-defaults.txt")
        externalRules.parent.createDirectories()
        externalRules.writeText("-keep class dev.fixture.PlatformEntry")

        val parsedRule = KeepRuleScanner(projectRoot).scan(listOf(externalRules)).single()

        assertEquals(SourceLocation("external/proguard-defaults.txt", 1), parsedRule.location)
        assertFalse(parsedRule.location.path.contains(directory.toString()))
    }

    @Test
    fun `parses annotated plain members without mistaking them for includes`(@TempDir projectRoot: Path) {
        val rules = projectRoot.resolve("proguard-rules.pro")
        rules.writeText(
            """
                -keep class dev.fixture.Factory {
                    @javax.inject.Inject <init>(...);
                }
            """.trimIndent(),
        )

        val parsedRule = KeepRuleScanner(projectRoot).scan(listOf(rules)).single()

        assertEquals(
            listOf(KeepMemberCondition(KeepMemberKind.CONSTRUCTORS, "javax.inject.Inject")),
            parsedRule.keptMembers,
        )
    }

    @Test
    fun `rejects multiline inheritance instead of dropping its constraint`(@TempDir projectRoot: Path) {
        val rules = projectRoot.resolve("proguard-rules.pro")
        rules.writeText(
            """
                -keep class dev.fixture.Parser
                    extends dev.fixture.BaseParser
            """.trimIndent(),
        )

        val error = assertFailsWith<KeepRuleScanningException> {
            KeepRuleScanner(projectRoot).scan(listOf(rules))
        }

        assertEquals("multiline inheritance is not supported at proguard-rules.pro:2", error.message)
    }

    @Test
    fun `rejects an unterminated member block at its opening line`(@TempDir projectRoot: Path) {
        val rules = projectRoot.resolve("proguard-rules.pro")
        rules.writeText(
            """
                -keep class dev.fixture.First {
                    *;
            """.trimIndent(),
        )

        val error = assertFailsWith<KeepRuleScanningException> {
            KeepRuleScanner(projectRoot).scan(listOf(rules))
        }

        assertEquals("unbalanced keep rule braces at proguard-rules.pro:1", error.message)
    }

    @Test
    fun `rejects another directive after a closing brace on the same line`(@TempDir projectRoot: Path) {
        val rules = projectRoot.resolve("proguard-rules.pro")
        rules.writeText("-keep class dev.fixture.First { *; } -keep class dev.fixture.Dropped")

        val error = assertFailsWith<KeepRuleScanningException> {
            KeepRuleScanner(projectRoot).scan(listOf(rules))
        }

        assertEquals("content after a member block is not supported at proguard-rules.pro:1", error.message)
    }

    @Test
    fun `rejects a directory include before attempting to read it`(@TempDir projectRoot: Path) {
        projectRoot.resolve("rules").createDirectories()
        val rules = projectRoot.resolve("proguard-rules.pro")
        rules.writeText("-include rules")

        val error = assertFailsWith<KeepRuleScanningException> {
            KeepRuleScanner(projectRoot).scan(listOf(rules))
        }

        assertEquals("included keep rule path is not a regular file at proguard-rules.pro:1", error.message)
    }

    @Test
    fun `rejects conditional rules instead of keeping their target unconditionally`(@TempDir projectRoot: Path) {
        val rules = projectRoot.resolve("proguard-rules.pro")
        rules.writeText(
            """
                -if class dev.fixture.Condition
                -keep class dev.fixture.Target
            """.trimIndent(),
        )

        val error = assertFailsWith<KeepRuleScanningException> {
            KeepRuleScanner(projectRoot).scan(listOf(rules))
        }

        assertEquals("conditional keep rules are not supported at proguard-rules.pro:1", error.message)
    }

    @Test
    fun `rejects unknown directives instead of silently dropping a keep typo`(@TempDir projectRoot: Path) {
        val rules = projectRoot.resolve("proguard-rules.pro")
        rules.writeText("-kep class dev.fixture.EntryPoint")

        val error = assertFailsWith<KeepRuleScanningException> {
            KeepRuleScanner(projectRoot).scan(listOf(rules))
        }

        assertEquals("unsupported directive at proguard-rules.pro:1", error.message)
    }

    @Test
    fun `ignores class member and optimization directives that do not retain classes`(@TempDir projectRoot: Path) {
        val rules = projectRoot.resolve("proguard-rules.pro")
        rules.writeText(
            """
                -allowaccessmodification
                -dontwarn dev.fixture.**
                -keepclassmembers,allowoptimization class dev.fixture.Model {
                    public <fields>;
                }
            """.trimIndent(),
        )

        assertEquals(emptyList(), KeepRuleScanner(projectRoot).scan(listOf(rules)))
    }

    @Test
    fun `parses annotated member conditions that retain their owning class`(@TempDir projectRoot: Path) {
        val rules = projectRoot.resolve("proguard-rules.pro")
        rules.writeText(
            """
                -keepclasseswithmembers class dev.fixture.Conditional* {
                    @dev.fixture.EntryPoint <methods>;
                }
            """.trimIndent(),
        )

        val parsedRule = KeepRuleScanner(projectRoot).scan(listOf(rules)).single()

        assertEquals(
            KeepRule(
                declarationKind = KeepDeclarationKind.CLASS,
                classNamePattern = "dev.fixture.Conditional*",
                location = locationAtRoot(1),
                memberConditions = listOf(
                    KeepMemberCondition(KeepMemberKind.METHODS, "dev.fixture.EntryPoint"),
                ),
            ),
            parsedRule,
        )
    }

    @Test
    fun `parses ordinary method signature conditions`(@TempDir projectRoot: Path) {
        val rules = projectRoot.resolve("proguard-rules.pro")
        rules.writeText(
            """
                -keepclasseswithmembers class dev.fixture.Callback {
                    public void onEvent(java.lang.String);
                }
            """.trimIndent(),
        )

        val condition = KeepRuleScanner(projectRoot).scan(listOf(rules)).single().memberConditions.single()

        assertEquals(KeepMemberKind.METHODS, condition.kind)
        assertEquals(setOf(Visibility.PUBLIC), condition.requiredJvmVisibilities)
        assertEquals("onEvent", condition.namePattern)
        assertEquals("(Ljava/lang/String;)V", condition.jvmDescriptor)
    }

    @Test
    fun `parses constructor field array and forbidden member visibility`(@TempDir projectRoot: Path) {
        val rules = projectRoot.resolve("proguard-rules.pro")
        rules.writeText(
            """
                -keepclasseswithmembers class dev.fixture.Callback {
                    !private <init>(java.lang.String[]);
                    protected long[] values;
                }
            """.trimIndent(),
        )

        val conditions = KeepRuleScanner(projectRoot).scan(listOf(rules)).single().memberConditions

        assertEquals(
            KeepMemberCondition(
                kind = KeepMemberKind.CONSTRUCTORS,
                forbiddenJvmVisibilities = setOf(Visibility.PRIVATE),
                namePattern = "<init>",
                jvmDescriptor = "([Ljava/lang/String;)V",
            ),
            conditions[0],
        )
        assertEquals(
            KeepMemberCondition(
                kind = KeepMemberKind.FIELDS,
                requiredJvmVisibilities = setOf(Visibility.PROTECTED),
                namePattern = "values",
                jvmDescriptor = "[J",
            ),
            conditions[1],
        )
    }

    @Test
    fun `parses native method modifier conditions`(@TempDir projectRoot: Path) {
        val rules = projectRoot.resolve("proguard-rules.pro")
        rules.writeText(
            """
                -keepclasseswithmembers class dev.fixture.NativeBridge {
                    native <methods>;
                }
            """.trimIndent(),
        )

        val condition = KeepRuleScanner(projectRoot).scan(listOf(rules)).single().memberConditions.single()

        assertEquals(KeepMemberKind.METHODS, condition.kind)
        assertEquals(setOf(JvmModifier.NATIVE), condition.requiredJvmModifiers)
    }

    @Test
    fun `parses an inline plain keep constructor specification`(@TempDir projectRoot: Path) {
        val rules = projectRoot.resolve("proguard-rules.pro")
        rules.writeText("-keep class dev.fixture.Controller { <init>(...); }")

        val parsedRule = KeepRuleScanner(projectRoot).scan(listOf(rules)).single()

        assertEquals(
            listOf(
                KeepMemberCondition(
                    kind = KeepMemberKind.CONSTRUCTORS,
                ),
            ),
            parsedRule.keptMembers,
        )
        assertFalse(parsedRule.keepAllMembers)
    }

    @Test
    fun `ignores assume no side effects blocks because they do not retain code`(@TempDir projectRoot: Path) {
        val rules = projectRoot.resolve("proguard-rules.pro")
        rules.writeText(
            """
                -assumenosideeffects class android.util.Log {
                    public static int d(...);
                }
            """.trimIndent(),
        )

        assertEquals(emptyList(), KeepRuleScanner(projectRoot).scan(listOf(rules)))
    }

    @Test
    fun `parses qualified and bare member wildcards without treating them as fields`(@TempDir projectRoot: Path) {
        val rules = projectRoot.resolve("proguard-rules.pro")
        rules.writeText(
            """
                -keepclasseswithmembers class dev.fixture.Callback {
                    public static <methods>;
                    <fields>;
                }
            """.trimIndent(),
        )

        val conditions = KeepRuleScanner(projectRoot).scan(listOf(rules)).single().memberConditions

        assertEquals(
            KeepMemberCondition(
                kind = KeepMemberKind.METHODS,
                requiredJvmVisibilities = setOf(Visibility.PUBLIC),
                requiredJvmModifiers = setOf(JvmModifier.STATIC),
            ),
            conditions[0],
        )
        assertEquals(KeepMemberCondition(KeepMemberKind.FIELDS), conditions[1])
    }

    @Test
    fun `rejects a plain keep brace on the following line instead of dropping members`(@TempDir projectRoot: Path) {
        val rules = projectRoot.resolve("proguard-rules.pro")
        rules.writeText(
            """
                -keep class dev.fixture.Controller
                {
                    *;
                }
            """.trimIndent(),
        )

        val error = assertFailsWith<KeepRuleScanningException> {
            KeepRuleScanner(projectRoot).scan(listOf(rules))
        }

        assertEquals("member block opening must be on the keep rule line at proguard-rules.pro:2", error.message)
    }

    @Test
    fun `ignores conditional class rules that allow shrinking`(@TempDir projectRoot: Path) {
        val rules = projectRoot.resolve("proguard-rules.pro")
        rules.writeText(
            """
                -keepclasseswithmembers,allowshrinking class dev.fixture.Conditional* {
                    @dev.fixture.EntryPoint <methods>;
                }
            """.trimIndent(),
        )

        assertEquals(emptyList(), KeepRuleScanner(projectRoot).scan(listOf(rules)))
    }

    @Test
    fun `reports missing files without exposing absolute paths`(@TempDir projectRoot: Path) {
        val missingRules = projectRoot.resolve("missing.pro")

        val error = assertFailsWith<KeepRuleScanningException> {
            KeepRuleScanner(projectRoot).scan(listOf(missingRules))
        }

        assertEquals("keep rule file cannot be resolved", error.message)
        assertFalse(error.message.orEmpty().contains(projectRoot.toString()))
    }

    private fun location(line: Int): SourceLocation = SourceLocation("app/proguard-rules.pro", line)

    private fun locationAtRoot(line: Int): SourceLocation = SourceLocation("proguard-rules.pro", line)
}
