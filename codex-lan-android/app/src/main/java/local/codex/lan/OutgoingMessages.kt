package local.codex.lan

import org.json.JSONArray
import org.json.JSONObject

data class OutgoingMessage(
    val request: String, val scope: String, val thread: String, val text: String,
    var anchor: String?, val afterTime: Long, var knownKeys: Set<String>, var delivery: String
)

/** Keeps submitted text visible until it appears in the desktop snapshot. */
class OutgoingMessages {
    private val records = mutableListOf<OutgoingMessage>()
    fun add(request: String, scope: String, thread: String, text: String, desktop: List<ChatItem>, delivery: String = "正在发送") {
        if (records.any { it.request == request }) return
        require(records.size < 64 && records.sumOf { it.text.toByteArray().size } + text.toByteArray().size <= 1024 * 1024) {
            "待同步的消息记录较多，请连接电脑同步后再发送。"
        }
        records += OutgoingMessage(request, scope, thread, text,
            desktop.lastOrNull { !it.key.startsWith("local:") }?.key,
            desktop.maxOfOrNull { it.occurredAt } ?: 0,
            desktop.filter { it.role == "你" && it.text.trim() == text.trim() }.map { it.key }.toSet(), delivery)
    }
    fun status(request: String, delivery: String) { records.find { it.request == request }?.delivery = delivery }
    fun remove(request: String) { records.removeAll { it.request == request } }
    fun reconcile(scope: String, thread: String, desktop: List<ChatItem>): Boolean {
        val consumed = mutableSetOf<String>()
        var lastConfirmed: String? = null
        val confirmed = records.filter { record ->
            if (record.scope != scope || record.thread != thread) return@filter false
            val anchorIndex = desktop.indexOfFirst { it.key == record.anchor }
            val match = desktop.drop(anchorIndex + 1).firstOrNull {
                it.role == "你" && it.text.trim() == record.text.trim() &&
                    it.occurredAt >= record.afterTime && it.key !in record.knownKeys && it.key !in consumed
            }
            if (match != null) {
                consumed += match.key
                lastConfirmed = match.key
                true
            } else {
                record.knownKeys = record.knownKeys + consumed
                val confirmedIndex = desktop.indexOfFirst { it.key == lastConfirmed }
                if (confirmedIndex > anchorIndex) record.anchor = lastConfirmed
                false
            }
        }
        records.removeAll(confirmed.toSet())
        return confirmed.isNotEmpty()
    }
    fun merge(scope: String, thread: String, desktop: List<ChatItem>): List<ChatItem> {
        val result = desktop.toMutableList()
        val lastAtAnchor = mutableMapOf<String?, String>()
        records.filter { it.scope == scope && it.thread == thread }.forEach { record ->
            val previous = lastAtAnchor[record.anchor] ?: record.anchor
            val anchorIndex = result.indexOfFirst { it.key == previous }
            val index = if (previous == null) 0 else if (anchorIndex >= 0) anchorIndex + 1 else result.size
            val key = "local:${record.request}"
            result.add(index, ChatItem(key, "你", record.text, delivery = record.delivery))
            lastAtAnchor[record.anchor] = key
        }
        return result
    }
    fun json(): JSONArray = JSONArray().also { array -> records.forEach { record ->
        array.put(JSONObject().put("request", record.request).put("scope", record.scope).put("thread", record.thread)
            .put("text", record.text).put("anchor", record.anchor ?: JSONObject.NULL)
            .put("afterTime", record.afterTime)
            .put("knownKeys", JSONArray(record.knownKeys.toList())).put("delivery", record.delivery))
    } }
    fun restore(array: JSONArray?) {
        records.clear()
        for (i in 0 until (array?.length() ?: 0).coerceAtMost(64)) {
            val row = array!!.getJSONObject(i)
            val keys = row.optJSONArray("knownKeys") ?: JSONArray()
            val text = row.getString("text")
            if (text.length > 16000 || records.sumOf { it.text.toByteArray().size } + text.toByteArray().size > 1024 * 1024) continue
            records += OutgoingMessage(row.getString("request"), row.getString("scope"), row.getString("thread"), text,
                row.optString("anchor").takeIf { it.isNotBlank() && it != "null" },
                row.optLong("afterTime"),
                (0 until keys.length()).map { keys.getString(it) }.toSet(),
                row.optString("delivery").let { if (it == "正在发送") "发送结果待核对" else it })
        }
    }
}
