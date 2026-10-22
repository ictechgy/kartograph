package dev.kartograph.export

import dev.kartograph.core.AnalysisLimitation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalRetentionReportingTest {
    @Test
    fun `legacy reporter JVM descriptors and default calls remain available`() {
        val type = AdoptionReporter::class.java
        val oldArguments = arrayOf(ReportFormat::class.java, Collection::class.java, Collection::class.java,
            Int::class.javaPrimitiveType!!, Map::class.java, Int::class.javaPrimitiveType!!,
            Collection::class.java, Collection::class.java)
        val oldMethod = type.getDeclaredMethod("render", *oldArguments)
        assertEquals(String::class.java, oldMethod.returnType)
        val oldDefault = type.getDeclaredMethod("render\$default", type, *oldArguments,
            Int::class.javaPrimitiveType!!, Any::class.java)
        val expected = AdoptionReporter.render(ReportFormat.TEXT, emptyList(), emptyList(), 0)
        assertEquals(expected, AdoptionReporter.render(ReportFormat.TEXT, emptyList(), emptyList(), 0,
            emptyMap(), 0, emptyList(), emptyList()))
        assertEquals(expected, AdoptionReporter.render(format = ReportFormat.TEXT, findings = emptyList(),
            limitations = emptyList(), suppressedCount = 0, inputHints = emptyList()))
        assertEquals(expected, oldMethod.invoke(AdoptionReporter, ReportFormat.TEXT, emptyList<Any>(), emptyList<Any>(),
            0, emptyMap<Any, Any>(), 0, emptyList<Any>(), emptyList<Any>()))
        assertEquals(expected, oldDefault.invoke(null, AdoptionReporter, ReportFormat.TEXT, emptyList<Any>(), emptyList<Any>(),
            0, null, 0, null, null, 0xf0, null))
    }

    @Test
    fun `every format reports global caveats with no findings and no retained entries`() {
        val context = ExternalRetentionCodec.parseDocument("""{
            "format":"external-retentions","version":0,"producedBy":{"name":"isthmus","version":"test"},
            "generatedAt":"2026-10-12T12:00:00Z","retentions":[],"provenance":{"version":1,
            "limitations":[{"platform":"swift","target":null,"tool":"future","origin":"opaque",
            "message":"index-freshness: stale\n::error::%<script>"}]}}""").context
        for (format in ReportFormat.entries) {
            val result = AdoptionReporter.render(format, emptyList(), AnalysisLimitation.entries, 0,
                externalRetentionContexts = listOf(context))
            assertTrue(result.contains("index-freshness"), format.name)
            assertTrue(result.contains("capture context"), format.name)
            assertTrue(!result.contains("<script>") && !result.contains("\n::error::"), format.name)
            if (format == ReportFormat.JSON || format == ReportFormat.SARIF) assertTrue(McpJsonCodec.parse(result) is Map<*, *>)
            assertEquals(AdoptionReporter.render(format, emptyList(), emptyList(), 0),
                AdoptionReporter.render(format, emptyList(), emptyList(), 0, externalRetentionContexts = emptyList()))
        }
    }
}
