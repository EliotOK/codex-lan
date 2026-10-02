package local.codex.lan

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class NativeAppTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun rendersMarkdownRecoversConnectionAndSendsConfirmedVoiceText() {
        val store = SecureStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = store.read()
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        val offline = AtomicBoolean(false)
        val sends = AtomicInteger()
        val sentText = AtomicReference<String>()
        val id = "22222222-2222-4222-8222-222222222222"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response = MockResponse().setHeader("Content-Type", "application/json")
                if(offline.get()) return response.setResponseCode(502).setBody("{\"error\":\"连接测试中断\"}")
                return when(request.path) {
                    "/api/pair" -> response.setHeader("Set-Cookie", "codex_lan=${"c".repeat(64)}; Secure").setBody("{\"csrf\":\"test-csrf\"}")
                    "/api/threads" -> response.setBody("{\"threads\":[{\"id\":\"$id\",\"title\":\"功能验证\"}]}")
                    "/api/threads/$id" -> response.setBody(JSONObject().put("thread",JSONObject().put("id",id).put("title","功能验证")).put("turns",org.json.JSONArray().put(JSONObject().put("id","turn-1").put("items",org.json.JSONArray().put(JSONObject().put("type","agentMessage").put("id","message-1").put("text","# 正常标题\n\n**正文**\n\n```python\n# literal\n```"))))).put("page",JSONObject()).toString())
                    "/api/threads/$id/messages" -> {
                        sentText.set(JSONObject(request.body.readUtf8()).getString("prompt"))
                        sends.incrementAndGet(); response.setBody("{\"state\":\"sent\"}")
                    }
                    else -> response.setBody("{\"connected\":true}")
                }
            }
        }
        server.start()
        try {
            val vm = ViewModelProvider(compose.activity)[ChatViewModel::class.java]
            compose.runOnIdle { vm.setCertificate(cert.certificatePem().toByteArray()); vm.pair("https://127.0.0.1:${server.port}","12345678") }
            compose.waitUntil(30000){vm.state.value.selected==id && vm.state.value.items.isNotEmpty()}
            compose.onNodeWithText("正常标题").assertIsDisplayed()
            compose.onNodeWithText("正文").assertIsDisplayed()
            compose.onNodeWithText("# literal").assertIsDisplayed()
            offline.set(true)
            compose.waitUntil(20000){!vm.state.value.connected}
            offline.set(false)
            compose.waitUntil(25000){vm.state.value.connected}
            assertTrue(vm.state.value.paired)
            compose.runOnIdle { vm.reconnect(); vm.draft("已有草稿"); vm.voiceResult("another-thread","其他会话的语音"); vm.voiceResult(id,"语音识别文字") }
            compose.waitUntil(25000){vm.state.value.connected}
            assertEquals("已有草稿\n语音识别文字",vm.state.value.draft)
            assertEquals("其他会话的语音",store.read().getJSONObject("drafts").getString("another-thread"))
            assertEquals(0,sends.get())
            compose.onNodeWithText("语音输入").assertIsDisplayed()
            compose.runOnIdle { vm.send() }
            compose.waitUntil(20000){sends.get()==1 && !vm.state.value.sending}
            assertEquals("已有草稿\n语音识别文字",sentText.get())
            assertNull(vm.state.value.pending)
        } finally { server.shutdown(); store.save(original) }
    }
    @Test fun pairReadAndRestoreDesktopChat() {
        val args = InstrumentationRegistry.getArguments()
        val code = requireNotNull(args.getString("pairingCode")) { "Pass -e pairingCode for read-only desktop integration" }
        val address = args.getString("endpoint", "https://127.0.0.1:8787")
        val title = requireNotNull(args.getString("threadTitle"))
        compose.onNodeWithText("电脑地址").performTextReplacement(address)
        compose.onNodeWithText("8 位配对码").performTextReplacement(code)
        compose.onNodeWithText("连接电脑").performClick()
        compose.waitUntil(45000) { compose.onAllNodesWithText("● 已连接 · 同一个会话").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("会话", useUnmergedTree = true).performClick()
        compose.onNodeWithText("搜索会话").performTextReplacement(title)
        compose.waitUntil(45000) { compose.onAllNodes(hasText(title) and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(title) and !hasSetTextAction()).performClick()
        compose.waitUntil(45000) { compose.onAllNodesWithText("继续这个会话…").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(45000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.waitUntil(45000) { compose.onAllNodesWithText("● 已连接 · 同一个会话").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun preservesUncertainSendAcrossRestartAndRequiresManualCheck() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecureStore(context); val original = store.read()
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val server = MockWebServer(); server.useHttps(tls.sslSocketFactory(), false)
        val sends = AtomicInteger()
        val id = "11111111-1111-4111-8111-111111111111"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response = MockResponse().setHeader("Content-Type", "application/json")
                return when (request.path) {
                    "/api/pair" -> response.setHeader("Set-Cookie", "codex_lan=${"b".repeat(64)}; Secure").setBody("{\"csrf\":\"test-csrf\"}")
                    "/api/threads" -> response.setBody("{\"threads\":[{\"id\":\"$id\",\"title\":\"通信测试\"}]}")
                    "/api/threads/$id" -> response.setBody("{\"thread\":{\"id\":\"$id\",\"title\":\"通信测试\"},\"turns\":[],\"page\":{}}")
                    "/api/threads/$id/messages" -> { sends.incrementAndGet(); response.setResponseCode(502).setBody("{\"state\":\"unknown\",\"error\":\"发送结果待确认\"}") }
                    else -> response.setBody("{\"connected\":true}")
                }
            }
        }
        server.start()
        try {
            val vm = ViewModelProvider(compose.activity)[ChatViewModel::class.java]
            compose.runOnIdle { vm.setCertificate(cert.certificatePem().toByteArray()); vm.pair("https://127.0.0.1:${server.port}", "12345678") }
            compose.waitUntil(30000) { vm.state.value.selected == id && vm.state.value.paired }
            compose.runOnIdle { vm.draft("模拟发送结果不确定"); vm.send() }
            compose.waitUntil(30000) { vm.state.value.pending != null && !vm.state.value.sending && sends.get() == 1 }
            val request = vm.state.value.pending!!.request
            assertEquals(request, store.read().getJSONObject("pending").getString("request"))
            compose.activityRule.scenario.recreate()
            val restored = ViewModelProvider(compose.activity)[ChatViewModel::class.java]
            compose.waitUntil(30000) { restored.state.value.connected }
            assertEquals(request, restored.state.value.pending!!.request)
            assertEquals("模拟发送结果不确定", restored.state.value.draft)
            compose.runOnIdle { restored.send() }
            assertEquals(1, sends.get())
            compose.runOnIdle { restored.acknowledgePending() }
            assertNull(restored.state.value.pending)
            assertEquals(1, sends.get())
        } finally { server.shutdown(); store.save(original) }
    }
    @Test fun storesSensitiveConnectionWithAndroidKeystore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecureStore(context)
        val original = store.read()
        try {
            val token = "keystore-test-token-0123456789"
            store.save(JSONObject().put("token", token).put("pending", "test-pending-record"))
            val serialized = context.getSharedPreferences("connection", Context.MODE_PRIVATE).getString("encrypted", "")!!
            assertFalse(serialized.contains(token))
            assertFalse(serialized.contains("test-pending-record"))
            assertEquals(token, store.read().getString("token"))
            assertEquals("test-pending-record", store.read().getString("pending"))
        } finally { store.save(original) }
    }
}
