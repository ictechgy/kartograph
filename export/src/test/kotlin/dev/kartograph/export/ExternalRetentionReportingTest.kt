package dev.kartograph.export

import dev.kartograph.core.AnalysisLimitation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalRetentionReportingTest {
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
