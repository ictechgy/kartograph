package dev.kartograph.export

import dev.kartograph.analysis.*
import kotlin.test.*
import org.junit.jupiter.api.Test

class ImpactReportSummaryTest {
    private fun summary(prefix: String) = ImpactSummary(
        candidates = 12,
        byModule = (1..4).map { ImpactBucket("$prefix-module-$it", Int.MAX_VALUE) },
        byFile = (1..4).map { ImpactBucket("$prefix-file-$it", it) },
        byTestStatus = (1..4).map { ImpactBucket("$prefix-test-$it", it) },
        byRelation = (1..4).map { ImpactBucket("$prefix-relation-$it", it) },
        byPathStatus = (1..4).map { ImpactBucket("$prefix-path-$it", it) },
    )

    private val report = ImpactReport(emptyList(), emptyList(), 12, emptyList(), listOf("kept"), ImpactTruncation(),
        navigation = ImpactNavigation(observed = summary("observed"), filtered = summary("filtered")))

    @Test fun `existing JVM renderer entry point remains callable`() {
        val entry = ImpactReportCodec::class.java.getMethod("render", ImpactReport::class.java, Map::class.java)
        assertEquals(ImpactReportCodec.render(report), entry.invoke(ImpactReportCodec, report, emptyMap<String, QuerySnapshot>()))
    }

    @Test fun `bounded summaries retain deterministic prefixes totals and explicit omissions`() {
        val original = ImpactReportCodec.render(report)
        assertEquals(original, ImpactReportCodec.render(report, summaryLimit = null))

        val document = McpJsonCodec.parse(ImpactReportCodec.render(report, summaryLimit = 2)) as Map<*, *>
        assertEquals("partial", document["status"])
        assertEquals(12L, (document["observedAffected"] as Number).toLong())
        assertEquals(listOf("kept"), document["limitations"])
        val observed = ((document["summary"] as Map<*, *>)["observed"] as Map<*, *>)
        assertEquals(listOf("observed-file-1", "observed-file-2"),
            (observed["byFile"] as List<Map<*, *>>).map { it["value"] })
        val navigation = ((document["summaryNavigation"] as Map<*, *>)["observed"] as Map<*, *>)
        val files = navigation["byFile"] as Map<*, *>
        assertEquals(4L, (files["originalBuckets"] as Number).toLong())
        assertEquals(2L, (files["returnedBuckets"] as Number).toLong())
        assertEquals(2L, (files["omittedBuckets"] as Number).toLong())
        assertEquals(7L, (files["omittedCandidates"] as Number).toLong())
        assertEquals(true, files["truncated"])
        val modules = navigation["byModule"] as Map<*, *>
        assertEquals(4_294_967_294L, (modules["omittedCandidates"] as Number).toLong())
        assertEquals(mapOf("summaryLimit" to 2L,
            "summary" to "Summary buckets are a deterministic prefix; use CLI impact without --summary-limit for the complete summary or request more buckets."),
            document["resultMetadata"])
    }
}
