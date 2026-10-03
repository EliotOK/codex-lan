package local.codex.lan

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class QuestionFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun planAndMcpAnswersReturnToOriginalRequestAndQueuedMessageCanSteer() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=SecureStore(context);val original=store.read()
        val cert=HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val server=MockWebServer();server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false)
        val id="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val plan=AtomicReference<JSONObject>();val mcp=AtomicReference<JSONObject>();val steer=AtomicReference<JSONObject>();val queued=AtomicBoolean(true)
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            val response=MockResponse().setHeader("Content-Type","application/json")
            return when(request.path) {
                "/api/pair"->response.setHeader("Set-Cookie","codex_lan=${"e".repeat(64)}; Secure").setBody("{\"csrf\":\"test-csrf\"}")
                "/api/threads"->response.setBody("""{"threads":[{"id":"$id","title":"提问通路测试"}],"projects":[]}""")
                "/api/models"->response.setBody("{\"models\":[]}")
                "/api/usage"->response.setBody("{\"limits\":[]}")
                "/api/threads/$id/controls"->{
                    val questions=when {plan.get()==null->"""[{"id":17,"kind":"plan","questions":[{"id":"scope","question":"选择范围","options":[{"label":"当前模块","description":"只改当前模块"},{"label":"整个项目"}]}]}]""";mcp.get()==null->"""[{"id":"mcp-1","kind":"mcp","mode":"form","message":"确认提交方式","schema":{"type":"object","required":["choice","confirmed"],"properties":{"choice":{"type":"string","enum":["测试后提交","直接提交"]},"confirmed":{"type":"boolean","title":"保留草稿"}}}}]""";else->"[]"}
                    response.setBody("""{"available":true,"questions":$questions,"queued":${if(queued.get())"""[{"id":"queued-one","text":"优先修复测试","canSteer":true}]"""else "[]"}}""")
                }
                "/api/threads/$id/answer"->{assertEquals("test-csrf",request.getHeader("X-CSRF-Token"));val body=JSONObject(request.body.readUtf8());if(body.get("questionId") is Number)plan.set(body)else mcp.set(body);response.setBody("{\"state\":\"submitted\"}")}
                "/api/threads/$id/steer"->{steer.set(JSONObject(request.body.readUtf8()));queued.set(false);response.setBody("{\"state\":\"submitted\"}")}
                "/api/logout"->response.setBody("{}")
                else->response.setBody("""{"thread":{"id":"$id","status":"active"},"turns":[],"page":{}}""")
            }
        }};server.start()
        try {
            val vm=ViewModelProvider(compose.activity)[ChatViewModel::class.java]
            compose.runOnIdle {vm.setCertificate(cert.certificatePem().toByteArray());vm.pair("https://127.0.0.1:${server.port}","12345678")}
            compose.waitUntil(20000){vm.state.value.questions.isNotEmpty()}
            compose.onNodeWithTag("conversation-controls").performClick()
            compose.onNodeWithText("当前模块",substring=false).performClick()
            compose.onNodeWithText("提交答案").performClick()
            compose.waitUntil(15000){plan.get()!=null&&vm.state.value.questions.firstOrNull()?.kind=="mcp"}
            assertEquals(17,plan.get().getInt("questionId"));assertEquals("当前模块",plan.get().getJSONObject("response").getJSONObject("answers").getJSONObject("scope").getJSONArray("answers").getString(0))
            compose.onNodeWithText("测试后提交").performClick();compose.onNodeWithText("否",substring=false).performClick()
            compose.onNodeWithText("提交答案").performClick()
            compose.waitUntil(15000){mcp.get()!=null&&vm.state.value.questions.isEmpty()}
            assertEquals("mcp-1",mcp.get().getString("questionId"));assertFalse(mcp.get().getJSONObject("response").getJSONObject("content").getBoolean("confirmed"))
            compose.onNodeWithText("立即引导").performScrollTo().performClick()
            compose.waitUntil(15000){steer.get()!=null&&vm.state.value.queued.isEmpty()}
            assertEquals("queued-one",steer.get().getString("messageId"));assertTrue(steer.get().getString("actionId").isNotBlank())
        }finally {store.save(original);server.shutdown()}
    }
}
