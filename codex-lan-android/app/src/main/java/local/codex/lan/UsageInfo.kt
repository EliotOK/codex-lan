package local.codex.lan

import org.json.JSONObject
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class UsageWindow(val minutes: Long?, val remaining: Double?, val reset: Long?) {
    val label: String get() = minutes?.let { when { it % 1440L == 0L -> "${it / 1440} 天"; it % 60L == 0L -> "${it / 60} 小时"; else -> "$it 分钟" } } ?: "用量窗口"
    val percent: String get() = remaining?.let { "${DecimalFormat("0.#").format(it)}%" } ?: "暂不可用"
    val resetText: String get() = reset?.let { runCatching { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) }.getOrNull() } ?: "暂不可用"
}
data class UsageBucket(val name: String, val windows: List<UsageWindow>, val credits: String?)
data class UsageInfo(val buckets: List<UsageBucket>, val fetchedAt: Long) {
    val summary: String get() = buckets.firstOrNull()?.windows?.joinToString(" · ") { "${it.label}剩余 ${it.percent}" }?.ifBlank { "暂无用量窗口" } ?: "暂无用量数据"
    companion object {
        fun parse(data: JSONObject): UsageInfo {
            val limits=data.optJSONArray("limits")
            val buckets=(0 until (limits?.length() ?: 0)).map { i ->
                val b=limits!!.getJSONObject(i); val values=b.optJSONArray("windows")
                val windows=(0 until (values?.length() ?: 0)).map { j ->
                    val w=values!!.getJSONObject(j)
                    fun numeric(key:String): Double? = (w.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }
                    UsageWindow(numeric("windowDurationMins")?.toLong()?.takeIf{it>0},numeric("remainingPercent")?.coerceIn(0.0,100.0),numeric("resetsAt")?.toLong()?.takeIf{it>0})
                }
                val c=b.optJSONObject("credits")
                UsageBucket(b.optString("name","Codex"),windows,if(c?.optBoolean("unlimited")==true)"不限额" else c?.optString("balance")?.takeIf{it.matches(Regex("\\d+(\\.\\d+)?"))}?.let { DecimalFormat("0.##").format(it.toBigDecimal()) })
            }
            return UsageInfo(buckets,data.optLong("fetchedAt"))
        }
    }
}
