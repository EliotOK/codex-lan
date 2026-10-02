package local.codex.lan

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OutgoingMessagesTest {
    private fun message(key: String, text: String, time: Long = 10) = ChatItem(key, "你", text, occurredAt = time)
    @Test fun restoresForwardedHistoryInItsOriginalPosition() {
        val output = JSONObject().put("type", "functionCallOutput").put("id", "phone")
            .put("name", "send_message_to_thread").put("namespace", "codex_app")
            .put("output", JSONObject().put("text", "<codex_delegation>\n<source_thread_id>same</source_thread_id>\n<input># 标题\n\n手机消息 & C#</input>\n</codex_delegation>").put("truncated", false))
        val turn = JSONObject().put("id", "turn").put("startedAt", 10).put("items", JSONArray()
            .put(JSONObject().put("type", "agentMessage").put("id", "before").put("text", "先前进度"))
            .put(output).put(JSONObject().put("type", "agentMessage").put("id", "after").put("text", "后续回复")))
        val items = ChatViewModel.renderTurn(turn)
        assertEquals(listOf("先前进度", "# 标题\n\n手机消息 & C#", "后续回复"), items.map { it.text })
        assertEquals("你", items[1].role)
        assertEquals(10L, items[1].occurredAt)
        output.getJSONObject("output").put("truncated", true)
        assertNull(MessageContent.forwarded(output))
        output.getJSONObject("output").put("truncated", false)
        output.put("name", "read_thread")
        assertNull(MessageContent.forwarded(output))
    }
    @Test fun keepsSubmittedMessagesThroughRefreshAndEncryptedStoreSerialization() {
        val cache = OutgoingMessages()
        val desktop = listOf(ChatItem("before", "Codex", "已有回复", occurredAt = 10))
        cache.add("request", "computer", "chat", "手机消息", desktop)
        cache.status("request", "已提交 · 等待桌面同步")
        val restored = OutgoingMessages().apply { restore(JSONArray(cache.json().toString())) }
        assertFalse(restored.reconcile("computer", "chat", desktop))
        val merged = restored.merge("computer", "chat", desktop)
        assertEquals("手机消息", merged.last().text)
        assertEquals("已提交 · 等待桌面同步", merged.last().delivery)
        val synced = desktop + message("forwarded", "手机消息", 11) + ChatItem("reply", "Codex", "收到")
        assertTrue(restored.reconcile("computer", "chat", synced))
        assertEquals(synced, restored.merge("computer", "chat", synced))
        assertEquals(0, restored.json().length())
    }
    @Test fun doesNotConfuseEarlierIdenticalMessagesOrOtherComputersAndChats() {
        val cache = OutgoingMessages()
        val desktop = listOf(message("old", "继续"), ChatItem("end", "状态", "完成", occurredAt = 10))
        cache.add("r", "computer", "chat", "继续", desktop)
        assertFalse(cache.reconcile("computer", "chat", desktop))
        assertEquals(desktop, cache.merge("other", "chat", desktop))
        assertEquals(desktop, cache.merge("computer", "other", desktop))
        assertFalse(cache.reconcile("other", "chat", desktop + message("new", "继续", 11)))
        assertFalse(cache.reconcile("computer", "chat", listOf(message("unloaded-old", "继续", 9))))
        assertTrue(cache.reconcile("computer", "chat", listOf(message("new", "继续", 11))))
    }
    @Test fun reconcilesRepeatedSubmissionsOneToOneAndKeepsSendOrder() {
        val cache = OutgoingMessages()
        val desktop = listOf(ChatItem("before", "Codex", "回复", occurredAt = 10))
        cache.add("first", "computer", "chat", "继续", desktop)
        cache.add("second", "computer", "chat", "继续", desktop)
        assertEquals(listOf("before", "local:first", "local:second"), cache.merge("computer", "chat", desktop).map { it.key })
        val first = desktop + message("new-1", "继续", 11)
        assertTrue(cache.reconcile("computer", "chat", first))
        // The remaining submission must not consume the same snapshot message again.
        val serialized = cache.json()
        assertEquals(1, serialized.length())
        assertFalse(cache.reconcile("computer", "chat", first))
        assertEquals(listOf("before", "new-1", "local:second"), cache.merge("computer", "chat", first).map { it.key })
        val second = first + message("new-2", "继续", 12)
        assertTrue(cache.reconcile("computer", "chat", second))
        assertEquals(second, cache.merge("computer", "chat", second))
    }
    @Test fun uncertainMessagesRestoreWithAccurateStatusAndRespectStorageBudget() {
        val cache = OutgoingMessages()
        cache.add("r", "c", "t", "待确认消息", emptyList())
        val restored = OutgoingMessages().apply { restore(cache.json()) }
        assertEquals("发送结果待核对", restored.merge("c", "t", emptyList()).single().delivery)
        restored.remove("r")
        assertTrue(restored.merge("c", "t", emptyList()).isEmpty())
        repeat(64) { restored.add("r$it", "c", "t", "消息", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { restored.add("overflow", "c", "t", "消息", emptyList()) }
    }
}
