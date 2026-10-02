package local.codex.lan
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class UsageInfoTest {
    @Test fun formatsWindowsAndPreservesUnknownValues() {
        val usage=UsageInfo.parse(JSONObject("""{"fetchedAt":123,"limits":[{"name":"codex","windows":[{"remainingPercent":42.5,"windowDurationMins":300,"resetsAt":2000000000},{"remainingPercent":null,"windowDurationMins":10080,"resetsAt":null}]}]}"""))
        assertEquals("5 小时",usage.buckets[0].windows[0].label)
        assertEquals(42.5,usage.buckets[0].windows[0].remaining!!,0.01)
        assertEquals("7 天",usage.buckets[0].windows[1].label)
        assertEquals("暂不可用",usage.buckets[0].windows[1].percent)
        assertEquals("暂不可用",usage.buckets[0].windows[1].resetText)
        assertEquals(123L,usage.fetchedAt)
    }
    @Test fun missingDataDoesNotPretendQuotaIsFull() {
        assertTrue(UsageInfo.parse(JSONObject()).buckets.isEmpty())
        val usage=UsageInfo.parse(JSONObject("""{"limits":[{"windows":[{"remainingPercent":-20},{"remainingPercent":200},{"remainingPercent":"oops"}]}]}"""))
        assertEquals(0.0,usage.buckets[0].windows[0].remaining!!,0.01)
        assertEquals(100.0,usage.buckets[0].windows[1].remaining!!,0.01)
        assertNull(usage.buckets[0].windows[2].remaining)
    }
}
