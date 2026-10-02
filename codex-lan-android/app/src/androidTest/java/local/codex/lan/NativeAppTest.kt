package local.codex.lan

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
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
    @Test fun previewsImagesNavigatesHistoryAndDisplaysIndependentQuota() {
        val store=SecureStore(InstrumentationRegistry.getInstrumentation().targetContext);val original=store.read()
        val cert=HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val server=MockWebServer();server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false)
        val id="33333333-3333-4333-8333-333333333333";val image="d".repeat(64)
        val png=java.io.ByteArrayOutputStream().also { output -> android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.GREEN)}.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output) }.toByteArray()
        val quotaOffline=AtomicBoolean(false);val appended=AtomicBoolean(false);val imageReads=AtomicInteger()
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            val response=MockResponse().setHeader("Content-Type","application/json")
            return when(request.path){
                "/api/pair"->response.setHeader("Set-Cookie","codex_lan=${"c".repeat(64)}; Secure").setBody("{\"csrf\":\"test-csrf\"}")
                "/api/threads"->response.setBody("{\"threads\":[{\"id\":\"$id\",\"title\":\"图片与导航验证\",\"status\":\"active\"}]}")
                "/api/usage","/api/usage?refresh=1"->if(quotaOffline.get())response.setResponseCode(502).setBody("{\"error\":\"用量不可用\"}")else response.setBody("{\"fetchedAt\":2000000000000,\"limits\":[{\"name\":\"codex\",\"windows\":[{\"windowDurationMins\":300,\"remainingPercent\":75,\"resetsAt\":2000000100},{\"windowDurationMins\":10080,\"remainingPercent\":50,\"resetsAt\":2000000200}]}]}")
                "/api/images/$image"->{assertEquals("codex_lan=${"c".repeat(64)}",request.getHeader("Cookie"));imageReads.incrementAndGet();response.setHeader("Content-Type","image/png").setBody(okio.Buffer().write(png))}
                "/api/threads/$id"->{
                    val turns=org.json.JSONArray()
                    for(i in 0..5)turns.put(JSONObject().put("id","t$i").put("startedAt",i+1).put("status","completed").put("items",org.json.JSONArray().put(JSONObject().put("id","m$i").put("type","agentMessage").put("text","历史锚点$i\n\n"+"阅读段落\n\n".repeat(15)))))
                    val last=org.json.JSONArray().put(JSONObject().put("id","attachment").put("type","userMessage").put("content",org.json.JSONArray().put(JSONObject().put("type","localImage").put("imageId",image).put("name","验证图片"))))
                    last.put(JSONObject().put("id","reason").put("type","reasoning"))
                    if(appended.get())last.put(JSONObject().put("id","new").put("type","agentMessage").put("text","新增回复"))
                    turns.put(JSONObject().put("id","last").put("startedAt",10).put("status","inProgress").put("items",last))
                    response.setBody(JSONObject().put("thread",JSONObject().put("id",id).put("title","图片与导航验证").put("status",JSONObject().put("type","active"))).put("turns",turns).put("page",JSONObject()).toString())
                }
                else->response.setBody("{}")
            }
        }}
        server.start()
        try {
            val vm=ViewModelProvider(compose.activity)[ChatViewModel::class.java]
            compose.runOnIdle{vm.setCertificate(cert.certificatePem().toByteArray());vm.pair("https://127.0.0.1:${server.port}","12345678")}
            compose.waitUntil(30000){vm.state.value.usage!=null&&vm.state.value.items.any{it.images.isNotEmpty()}}
            compose.onNodeWithText("思考中").assertIsDisplayed()
            compose.onNodeWithText("用量").performClick()
            compose.onNodeWithText("5 小时剩余 75%").assertIsDisplayed()
            compose.onNodeWithText("7 天剩余 50%").assertIsDisplayed()
            screenshot("usage")
            quotaOffline.set(true);compose.onNodeWithText("刷新用量").performClick()
            compose.waitUntil(15000){vm.state.value.usageNotice.isNotBlank()&&!vm.state.value.usageRefreshing}
            assertTrue(vm.state.value.connected)
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithText("↓ 最底部").performClick()
            compose.waitUntil(20000){compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag,"image-preview")).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("image-preview").performClick()
            compose.waitUntil(20000){imageReads.get()>=2}
            compose.onNodeWithTag("image-viewer").performTouchInput{pinch(Offset(width*0.4f,height*0.5f),Offset(width*0.6f,height*0.5f),Offset(width*0.2f,height*0.5f),Offset(width*0.8f,height*0.5f))}
            compose.waitUntil(10000){!compose.onNodeWithTag("image-zoom").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString().contains("100%")}
            compose.onNodeWithText("重置缩放").performClick()
            compose.onNodeWithText("双指缩放 · 双击放大 · 100%").assertIsDisplayed()
            screenshot("image")
            compose.onNodeWithText("关闭图片").performClick()
            compose.onNodeWithText("历史位置").performClick()
            compose.onNodeWithText("搜索历史消息").performTextReplacement("历史锚点0")
            compose.onNode(hasText("历史锚点0",substring=true) and !hasSetTextAction()).performClick()
            compose.onNodeWithText("历史锚点0").assertIsDisplayed()
            appended.set(true);compose.waitUntil(15000){vm.state.value.items.any{it.text=="新增回复"}}
            compose.onNodeWithText("历史锚点0").assertIsDisplayed()
            compose.onNodeWithText("↓ 最底部").performClick()
            compose.onNodeWithText("新增回复").assertIsDisplayed()
        }finally{server.shutdown();store.save(original)}
    }
    private fun screenshot(name:String){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bitmap=instrumentation.uiAutomation.takeScreenshot()?:return
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"qa-$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
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
    @Test fun keepsPhoneMessagesVisibleUntilForwardedHistoryArrives() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecureStore(context); val original = store.read()
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        val synced = AtomicBoolean(false)
        val sends = AtomicInteger()
        val id = "33333333-3333-4333-8333-333333333333"
        val other = "44444444-4444-4444-8444-444444444444"
        val prompt = "手机发送保留验证"
        fun snapshot(thread: String): String {
            val items = org.json.JSONArray().put(JSONObject().put("type", "agentMessage").put("id", "before").put("text", "已有桌面回复"))
            if (thread == id && synced.get()) {
                items.put(JSONObject().put("type", "functionCallOutput").put("id", "forwarded")
                    .put("name", "send_message_to_thread").put("namespace", "codex_app")
                    .put("output", JSONObject().put("text", "<codex_delegation><source_thread_id>same</source_thread_id><input>$prompt</input></codex_delegation>").put("truncated", false)))
                items.put(JSONObject().put("type", "agentMessage").put("id", "after").put("text", "桌面已收到消息"))
            }
            return JSONObject().put("thread", JSONObject().put("id", thread).put("title", "消息显示验证"))
                .put("turns", org.json.JSONArray().put(JSONObject().put("id", "turn").put("startedAt", 10).put("items", items)))
                .put("page", JSONObject()).toString()
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response = MockResponse().setHeader("Content-Type", "application/json")
                return when (request.path) {
                    "/api/pair" -> response.setHeader("Set-Cookie", "codex_lan=${"d".repeat(64)}; Secure").setBody("{\"csrf\":\"test-csrf\"}")
                    "/api/threads" -> response.setBody("{\"threads\":[{\"id\":\"$id\",\"title\":\"消息显示验证\"},{\"id\":\"$other\",\"title\":\"另一会话\"}]}")
                    "/api/threads/$id" -> response.setBody(snapshot(id))
                    "/api/threads/$other" -> response.setBody(snapshot(other))
                    "/api/threads/$id/messages" -> {
                        assertEquals(prompt, JSONObject(request.body.readUtf8()).getString("prompt"))
                        sends.incrementAndGet()
                        response.setBody("{\"state\":\"sent\"}").setBodyDelay(1200, java.util.concurrent.TimeUnit.MILLISECONDS)
                    }
                    else -> response.setBody("{\"connected\":true}")
                }
            }
        }
        server.start()
        try {
            val vm = ViewModelProvider(compose.activity)[ChatViewModel::class.java]
            compose.runOnIdle { vm.setCertificate(cert.certificatePem().toByteArray()); vm.pair("https://127.0.0.1:${server.port}", "12345678") }
            compose.waitUntil(30000) { vm.state.value.selected == id && vm.state.value.items.isNotEmpty() }
            compose.runOnIdle { vm.draft(prompt); vm.send() }
            assertEquals("正在发送", vm.state.value.items.single { it.text == prompt }.delivery)
            compose.waitUntil(30000) { !vm.state.value.sending && vm.state.value.draft.isEmpty() }
            compose.onNodeWithText(prompt).assertIsDisplayed()
            assertEquals(1, store.read().getJSONArray("outgoing").length())
            val restored = ChatViewModel(context.applicationContext as android.app.Application)
            assertEquals(prompt, restored.state.value.items.single().text)
            assertNull(restored.state.value.pending)
            compose.runOnIdle { vm.select(other) }
            assertFalse(vm.state.value.items.any { it.text == prompt })
            compose.runOnIdle { vm.select(id) }
            compose.waitUntil(30000) { vm.state.value.items.any { it.text == prompt } }
            synced.set(true)
            compose.runOnIdle { vm.refreshNow() }
            compose.waitUntil(30000) { vm.state.value.items.any { it.text == "桌面已收到消息" } }
            assertEquals(1, vm.state.value.items.count { it.text == prompt })
            assertNull(vm.state.value.items.single { it.text == prompt }.delivery)
            compose.waitUntil(10000) { store.read().getJSONArray("outgoing").length() == 0 }
            compose.onNodeWithText(prompt).assertIsDisplayed()
            assertEquals(1, sends.get())
        } finally { server.shutdown(); store.save(original) }
    }
    @Test fun groupsProjectsCollapsesSearchesAndSelectsOriginalThread() {
        val store = SecureStore(InstrumentationRegistry.getInstrumentation().targetContext); val original = store.read()
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        val ids = listOf("55555555-5555-4555-8555-555555555555", "66666666-6666-4666-8666-666666666666", "77777777-7777-4777-8777-777777777777", "88888888-8888-4888-8888-888888888888", "99999999-9999-4999-8999-999999999999")
        val titles = listOf("第一条会话", "第二条会话", "第三条会话", "独立会话", "临时会话")
        val paths = listOf("C:\\One\\Project\\subfolder", "D:/worktree", "D:/Two/Project", "C:/Codex/ce", "C:/Codex/c-j")
        val projects = listOf("p1", "p1", "p2", null, null)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response = MockResponse().setHeader("Content-Type", "application/json")
                return when (request.path) {
                    "/api/pair" -> response.setHeader("Set-Cookie", "codex_lan=${"e".repeat(64)}; Secure").setBody("{\"csrf\":\"test-csrf\"}")
                    "/api/threads" -> response.setBody(JSONObject().put("threads", org.json.JSONArray().also { array ->
                        ids.forEachIndexed { index, id -> array.put(JSONObject().put("id", id).put("title", titles[index]).put("cwd", paths[index]).put("projectId", projects[index] ?: JSONObject.NULL)) }
                    }).put("projects", org.json.JSONArray().put(JSONObject().put("projectId", "p1").put("label", "项目甲").put("path", "C:/One/Project"))
                        .put(JSONObject().put("projectId", "p2").put("label", "项目乙").put("path", "D:/Two/Project"))).toString())
                    else -> {
                        val index = ids.indexOf(request.path?.substringAfterLast('/')).coerceAtLeast(0)
                        response.setBody(JSONObject().put("thread", JSONObject().put("id", ids[index]).put("title", titles[index]))
                            .put("turns", org.json.JSONArray()).put("page", JSONObject()).toString())
                    }
                }
            }
        }
        server.start()
        try {
            val vm = ViewModelProvider(compose.activity)[ChatViewModel::class.java]
            compose.runOnIdle { vm.setCertificate(cert.certificatePem().toByteArray()); vm.pair("https://127.0.0.1:${server.port}", "12345678") }
            compose.waitUntil(30000) { vm.state.value.threads.size == 5 && vm.state.value.selected == ids[0] }
            compose.onNodeWithText("会话", useUnmergedTree = true).performClick()
            compose.onNodeWithText("C:/One/Project").assertIsDisplayed()
            compose.onNodeWithText("项目甲").assertIsDisplayed()
            assertEquals(2, ProjectGroups.from(vm.state.value.threads).first { it.key == "project:p1" }.threads.size)
            compose.onNodeWithTag("project-header:project:p1").performClick()
            assertTrue(compose.onAllNodesWithText(titles[0]).fetchSemanticsNodes().isEmpty())
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15000) { compose.onAllNodesWithText("搜索会话").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(compose.onAllNodesWithText(titles[0]).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithText("搜索会话").performTextReplacement("C:/One")
            compose.onNodeWithText(titles[0]).assertIsDisplayed()
            compose.onNodeWithText(titles[1]).assertIsDisplayed()
            assertTrue(compose.onAllNodesWithText("D:/Two/Project").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithText("搜索会话").performTextReplacement("未分组")
            compose.onNodeWithText("独立会话").assertIsDisplayed()
            compose.onNodeWithText("临时会话").assertIsDisplayed()
            assertEquals(2, ProjectGroups.from(vm.state.value.threads).first { it.key == "unassigned" }.threads.size)
            screenshot("unassigned")
            compose.onNodeWithText("搜索会话").performTextReplacement("没有匹配的项目")
            compose.onNodeWithText("没有匹配的会话").assertIsDisplayed()
            compose.onNodeWithText("搜索会话").performTextReplacement("第三条")
            compose.onNodeWithText("D:/Two/Project").assertIsDisplayed()
            compose.onNodeWithText("项目乙").assertIsDisplayed()
            screenshot("projects")
            compose.onNodeWithText(titles[2]).performClick()
            compose.waitUntil(15000) { vm.state.value.selected == ids[2] }
            compose.onNodeWithText(titles[2]).assertIsDisplayed()
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
