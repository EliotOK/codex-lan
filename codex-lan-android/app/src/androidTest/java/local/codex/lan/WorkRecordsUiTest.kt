package local.codex.lan

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkRecordsUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 @Test fun repliesHaveAvatarsAndEachRecordExpandsIndependentlyWithBoundedOutput() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val store=SecureStore(context);val original=store.read();val appearance=AppearanceStore(context);val originalAppearance=appearance.read()
  val cert=HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
  val server=MockWebServer();server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false)
  val id="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
  server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
   val response=MockResponse().setHeader("Content-Type","application/json")
   return when(request.path) {
    "/api/pair"->response.setHeader("Set-Cookie","codex_lan=${"e".repeat(64)}; Secure").setBody("{\"csrf\":\"test-csrf\"}")
    "/api/threads"->response.setBody("""{"threads":[{"id":"$id","title":"逐项记录验证"}],"projects":[]}""")
    "/api/models"->response.setBody("{\"models\":[]}")
    "/api/usage"->response.setBody("{\"limits\":[]}")
    "/api/logout"->response.setBody("{}")
    else-> {
     val items=JSONArray().put(JSONObject().put("id","u").put("type","userMessage").put("content",JSONArray().put(JSONObject().put("type","text").put("text","四个角都留出空间。"))))
      .put(JSONObject().put("id","r").put("type","reasoning").put("summary",JSONArray().put("核对布局尺寸和圆角内侧边距。")))
      .put(JSONObject().put("id","p").put("type","agentMessage").put("phase","commentary").put("text","正在检查字号和气泡边距。"))
      .put(JSONObject().put("id","c").put("type","commandExecution").put("command","node --test "+"long/path/to/test/".repeat(25)).put("aggregatedOutput","测试输出\n".repeat(400)).put("status","completed"))
      .put(JSONObject().put("id","a").put("type","agentMessage").put("phase","final").put("text","已完成，回复保留头像。"))
     response.setBody(JSONObject().put("thread",JSONObject().put("id",id).put("title","逐项记录验证").put("status","idle")).put("turns",JSONArray().put(JSONObject().put("id","t").put("status","completed").put("items",items))).put("page",JSONObject()).toString())
    }
   }
  }};server.start()
  try {
   appearance.save(Appearance("ocean",22));compose.activityRule.scenario.recreate()
   val vm=ViewModelProvider(compose.activity)[ChatViewModel::class.java]
   compose.runOnIdle {vm.setCertificate(cert.certificatePem().toByteArray());vm.pair("https://127.0.0.1:${server.port}","12345678")}
   compose.waitUntil(20000){vm.state.value.items.any{it.records.size==3}}
   compose.onNodeWithTag("reply-avatar:t:a").assertIsDisplayed()
   compose.onNodeWithTag("reply-avatar:t:r").assertDoesNotExist();compose.onNodeWithTag("reply-avatar:t:p").assertDoesNotExist()
   val bubble=compose.onNodeWithTag("user-bubble:t:u").fetchSemanticsNode().boundsInRoot
   val content=compose.onNodeWithTag("bubble-content:t:u").fetchSemanticsNode().boundsInRoot
   val padding=22*context.resources.displayMetrics.density
   assertTrue(content.left-bubble.left>=padding-1);assertTrue(content.top-bubble.top>=padding-1)
   assertTrue(bubble.right-content.right>=padding-1);assertTrue(bubble.bottom-content.bottom>=padding-1)
   compose.waitForIdle();screenshot("bubble-avatar")
   compose.onNodeWithTag("work-group:t:r").performClick()
   compose.onNodeWithTag("work-row:t:c").assertIsDisplayed()
   val layout=mutableListOf<TextLayoutResult>()
   compose.onNodeWithTag("work-row:t:c").performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layout)}
   assertEquals(1,layout.single().lineCount);assertTrue(layout.single().isLineEllipsized(0))
   screenshot("record-list")
   compose.onNodeWithTag("work-row:t:r").performClick()
   compose.onNodeWithTag("work-body:t:r").assertIsDisplayed();compose.onNodeWithTag("work-body:t:p").assertDoesNotExist()
   compose.onNodeWithTag("work-row:t:r").performClick()
   compose.onNodeWithTag("work-row:t:c").performClick()
   assertTrue(compose.onNodeWithTag("work-body:t:c").fetchSemanticsNode().boundsInRoot.height<=280*context.resources.displayMetrics.density+1)
   compose.onNodeWithTag("work-body:t:r").assertDoesNotExist()
   screenshot("record-expanded")
   compose.onNodeWithTag("work-row:t:c").performClick()
   compose.onNodeWithTag("work-body:t:c").assertDoesNotExist()
  } finally {store.save(original);appearance.save(originalAppearance);server.shutdown()}
 }
 private fun screenshot(name: String) {
  compose.waitForIdle();val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
  java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"qa-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
 }
}
