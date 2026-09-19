package dev.kartograph.export

import dev.kartograph.core.RetentionReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ExternalRetentionCodecTest {
    private val caller = """{"platform":"dart","path":"lib/camera.dart","line":7}"""
    private val valid = """{
        "format":"external-retentions","version":0,"producedBy":{"name":"isthmus","version":"test"},
        "generatedAt":"2026-09-19T00:00:00Z","retentions":[{
        "symbol":{"qualifiedName":"Camera.handle","usr":"method:sample/Camera#handle()V"},
        "reason":"bridge","evidence":{"channel":"camera","method":"read","caller":$caller}}]}
    """.trimIndent()

    @Test
    fun `v0 retains exact JVM identity and caller evidence`() {
        val item = ExternalRetentionCodec.parse(valid).single()
        assertEquals("method:sample/Camera#handle()V", item.nodeId.value)
        assertEquals(RetentionReason.EXTERNAL_BRIDGE, item.reason)
        assertEquals("camera", item.externalBridge?.channel)
        assertEquals("read", item.externalBridge?.method)
        assertEquals("lib/camera.dart", item.location?.path)
        assertEquals(7, item.location?.line)
        val bridge = assertNotNull(item.externalBridge)
        assertEquals(listOf(bridge.caller), bridge.callers)
    }

    @Test
    fun `methodless evidence supports JS callers and reports omitted callers`() {
        val document = valid.replace("\"method\":\"read\",", "")
            .replace("\"caller\":" + caller, "\"caller\":" + caller + ",\"callers\":[" + caller +
                "," + caller.replace("dart", "js").replace("7", "8") + "],\"callersOmitted\":3")
        val bridge = ExternalRetentionCodec.parse(document).single().externalBridge!!
        assertEquals(null, bridge.method)
        assertEquals(2, bridge.callers.size)
        assertEquals(3L, bridge.callersOmitted)
    }

    @Test
    fun `malformed unsafe and incomplete inputs fail without leaking raw values`() {
        val cases = listOf(
            valid.replace("\"version\":0", "\"version\":1"),
            valid.replace("\"version\":0", "\"version\":0,\"version\":0"),
            valid.replace("\"reason\":\"bridge\"", "\"reason\":\"other\""),
            valid.replace("\"usr\":\"method:sample/Camera#handle()V\"", "\"usr\":\"s:swift\""),
            valid.replace("method:sample/Camera#handle()V", "method:"),
            valid.replace("method:sample/Camera#handle()V", "field: "),
            valid.replace("lib/camera.dart", "../private/camera.dart"),
            valid.replace("lib/camera.dart", "/private/camera.dart"),
            valid.replace("lib/camera.dart", "C:/private/camera.dart"),
            valid.replace("lib/camera.dart", "lib/\\u2028camera.dart"),
            valid.replace("lib/camera.dart", "lib/\\uD800camera.dart"),
            valid.replace("\"line\":7", "\"line\":0"),
            valid.replace("\"line\":7", "\"line\":7.5"),
            valid.replace("\"platform\":\"dart\"", "\"platform\":\"swift\""),
            valid.replace("\"caller\":" + caller, "\"caller\":" + caller + ",\"callers\":[]"),
            valid.replace("\"caller\":" + caller, "\"caller\":" + caller + ",\"callersOmitted\":-1"),
            valid.replace("2026-09-19T00:00:00Z", "yesterday"),
            valid + "garbage", "{}", "[]",
        )
        for (document in cases) {
            val error = assertFailsWith<IllegalArgumentException> { ExternalRetentionCodec.parse(document) }
            assertEquals(true, error.message?.startsWith("invalid external-retentions"))
        }
    }

    @Test
    fun `unknown optional fields are ignored and duplicate retentions are folded`() {
        val future = valid.replace("\"version\":0", "\"version\":0,\"future\":{\"ratio\":1.5}")
        assertEquals(ExternalRetentionCodec.parse(valid), ExternalRetentionCodec.parse(future))
        val entry = valid.substringAfter("\"retentions\":[").substringBeforeLast("]}")
        assertEquals(1, ExternalRetentionCodec.parse(valid.replace(entry, entry + "," + entry)).size)
    }
}
