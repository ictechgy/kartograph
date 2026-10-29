package dev.kartograph.gradle

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompilerCallPositionOptionParserTest {
    private val javaRequired =
        "root=file:///project%20root output=file:///build/evidence.tsv token=file:///build/token"

    @Test
    fun `javac mirrors ordered producer options for one constants plugin invocation`() {
        fun enabled(options: String): Boolean = CompilerCallPositionOptionParser.enabled(
            "javac", listOf("17", "-g", "-Xplugin:KartographEvidence $javaRequired $options"), compilerEvidence = true,
        )
        assertFalse(CompilerCallPositionOptionParser.enabled("javac", listOf("-g"), compilerEvidence = true))
        assertFalse(enabled(""))
        assertTrue(enabled("callPositions=true"))
        assertFalse(enabled("callPositions=false"))
        assertFalse(enabled("callPositions=true callPositions=false"))
        assertTrue(enabled("callPositions=false callPositions=true"))
        assertFalse(enabled("callPositions=true collector=dagger-bindings callPositions=false"))
        assertTrue(enabled("collector=dagger-bindings collector=javac-constants callPositions=true"))
        assertTrue(CompilerCallPositionOptionParser.enabled(
            "javac", listOf("-Xplugin:KartographEvidence\t$javaRequired\tcallPositions=true"), compilerEvidence = true,
        ))
        assertFalse(CompilerCallPositionOptionParser.enabled(
            "javac", listOf("-Xplugin:Other callPositions=true", "path/callPositions=true.txt"), compilerEvidence = true,
        ))
        assertFalse(CompilerCallPositionOptionParser.enabled(
            "javac", listOf("-Xplugin:KartographEvidenceExtra $javaRequired callPositions=true"), compilerEvidence = true,
        ))
        assertFalse(CompilerCallPositionOptionParser.enabled(
            "javac", listOf("-Xplugin:KartographEvidence $javaRequired callPositions=true"), compilerEvidence = false,
        ))
    }

    @Test
    fun `javac rejects malformed flags unsupported collectors and repeated plugin initialization`() {
        fun invalid(argument: String) = assertFailsWith<IllegalArgumentException> {
            CompilerCallPositionOptionParser.enabled("javac", listOf(argument), compilerEvidence = true)
        }
        invalid("-Xplugin:KartographEvidence root=x output=y token=z callPositions=True")
        invalid("-Xplugin:KartographEvidence root=x output=y token=z callPositions=1")
        invalid("-Xplugin:KartographEvidence root=x output=y token=z callPositions=")
        invalid("-Xplugin:KartographEvidence root=x output=y token=z unknown=true")
        invalid("-Xplugin:KartographEvidence root=x output=y callPositions=true")
        invalid("-Xplugin:KartographEvidence root=x output=y token=z collector=dagger-bindings callPositions=true")
        invalid("-Xplugin:KartographEvidence root=x output=y token=z collector=kotlin-constants callPositions=true")
        invalid("-Xplugin:KartographEvidence root=x output=y token=z callPositions=true\u00a0")
        for (second in listOf(
            "-Xplugin:KartographEvidence root=x output=y token=z callPositions=true",
            "-Xplugin:KartographEvidence root=other output=y token=z callPositions=false",
        )) {
            assertFailsWith<IllegalArgumentException> {
                CompilerCallPositionOptionParser.enabled("javac", listOf(
                    "-Xplugin:KartographEvidence root=x output=y token=z callPositions=true", second,
                ), compilerEvidence = true)
            }
        }
    }

    @Test
    fun `Kotlin accepts exact separated or combined option once and ignores substrings`() {
        fun enabled(arguments: List<String>): Boolean =
            CompilerCallPositionOptionParser.enabled("kotlin", arguments, compilerEvidence = true)
        assertFalse(enabled(emptyList()))
        assertTrue(enabled(listOf("-P", "plugin:kartograph.compiler-evidence:callPositions=true")))
        assertFalse(enabled(listOf("-P", "plugin:kartograph.compiler-evidence:callPositions=false")))
        assertTrue(enabled(listOf("-Pplugin:kartograph.compiler-evidence:callPositions=true")))
        assertTrue(enabled(listOf("-P", listOf(
            "plugin:kartograph.compiler-evidence:root=/project",
            "plugin:kartograph.compiler-evidence:output=/build/evidence.tsv",
            "plugin:kartograph.compiler-evidence:token=/build/token",
            "plugin:kartograph.compiler-evidence:callPositions=true",
        ).joinToString(","))))
        assertFalse(enabled(listOf(
            "-P", "plugin:other:callPositions=true",
            "-P", "plugin:kartograph.compiler-evidence:root=/tmp/callPositions=true",
            "source-callPositions=true.kt",
        )))
        assertFalse(CompilerCallPositionOptionParser.enabled(
            "kotlin", listOf("-P", "plugin:kartograph.compiler-evidence:callPositions=true"), compilerEvidence = false,
        ))
    }

    @Test
    fun `Kotlin rejects invalid and duplicate call position options`() {
        for (arguments in listOf(
            listOf("-P", "plugin:kartograph.compiler-evidence:callPositions=True"),
            listOf("-Pplugin:kartograph.compiler-evidence:callPositions=1"),
            listOf("-P", "plugin:kartograph.compiler-evidence:callPositions"),
            listOf("-P", "plugin:kartograph.compiler-evidence:callPositions=true",
                "-P", "plugin:kartograph.compiler-evidence:callPositions=false"),
            listOf("-Pplugin:kartograph.compiler-evidence:callPositions=true",
                "-Pplugin:kartograph.compiler-evidence:callPositions=true"),
            listOf("-P", "plugin:kartograph.compiler-evidence:callPositions=true",
                "-Pplugin:kartograph.compiler-evidence:callPositions=false"),
            listOf("-P", "plugin:kartograph.compiler-evidence:callPositions=true," +
                "plugin:kartograph.compiler-evidence:callPositions=false"),
        )) {
            assertFailsWith<IllegalArgumentException> {
                CompilerCallPositionOptionParser.enabled("kotlin", arguments, compilerEvidence = true)
            }
        }
    }
}
