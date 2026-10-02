package local.codex.lan
import org.junit.Assert.*
import org.junit.Test
class MessageContentTest {
    @Test fun separatesKnownDesktopEnvelopeFromUserMessage() {
        val raw="<in-app-browser-context source=\"ambient-ui-state\">\n# In app browser:\nhttp://localhost\n</in-app-browser-context>\n\n## My request:\n开始\n"
        val content=MessageContent.user(raw)
        assertEquals("开始",content.text)
        assertTrue(content.context!!.contains("# In app browser:"))
    }
    @Test fun preservesUserMarkdownAndHashCharacters() {
        val raw="# 我的标题\n\nC# 与 #标签\n```python\n# 注释\n```"
        val content=MessageContent.user(raw)
        assertEquals(raw,content.text);assertNull(content.context)
    }
    @Test fun unwrapsPhoneInputAndKeepsOriginalEnvelopeAvailable() {
        val raw="<codex_delegation><source_thread_id>id</source_thread_id><input>手机输入</input></codex_delegation>"
        assertEquals("手机输入",MessageContent.user(raw).text)
        assertEquals(raw,MessageContent.user(raw).context)
    }
}
