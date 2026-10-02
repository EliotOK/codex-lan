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

@RunWith(AndroidJUnit4::class)
class CustomizationTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private fun screenshot(name:String) {
  compose.waitForIdle()
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
  java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"qa-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
 }
 @Test fun appearanceAppliesImmediatelyAndSurvivesRecreation() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val store=AppearanceStore(context);val original=store.read()
  try {
   store.save(Appearance());compose.activityRule.scenario.recreate()
   val vm=ViewModelProvider(compose.activity)[ChatViewModel::class.java]
   compose.runOnIdle { vm.disconnect() }
   compose.onNodeWithText("外观与字号").performClick()
   compose.onNodeWithText("放大").performClick()
   compose.onNodeWithText("明亮").performClick()
   compose.onNodeWithText("正文字号 · 17 sp").assertIsDisplayed()
   assertEquals(Appearance("light",17),store.read())
   screenshot("appearance-light")
   compose.onNodeWithText("完成").performClick()
   compose.activityRule.scenario.recreate()
   compose.onNodeWithText("外观与字号").performClick()
   compose.onNodeWithText("正文字号 · 17 sp").assertIsDisplayed()
   compose.onNodeWithText("明亮").assertIsSelected()
  } finally {store.save(original)}
 }
 @Test fun modelSelectionIsPerThreadAndSubmittedWithMessage() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val storage=SecureStore(context);val original=storage.read()
  val appearance=AppearanceStore(context);val originalAppearance=appearance.read()
  val cert=HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
  val server=MockWebServer();server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false)
  val id="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";val other="bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
  val sent=AtomicReference<JSONObject>()
  server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest):MockResponse {
   val response=MockResponse().setHeader("Content-Type","application/json")
   return when(request.path) {
    "/api/pair"->response.setHeader("Set-Cookie","codex_lan=${"e".repeat(64)}; Secure").setBody("{\"csrf\":\"test-csrf\"}")
    "/api/models"->response.setBody("""{"models":[{"id":"gpt-6-luna","efforts":["low","medium","high","max"]}]}""")
    "/api/threads"->response.setBody("""{"threads":[{"id":"$id","title":"外观测试会话"},{"id":"$other","title":"另一个会话"}],"projects":[]}""")
    "/api/threads/$id/messages"->{sent.set(JSONObject(request.body.readUtf8()));response.setBody("{\"state\":\"sent\"}")}
    "/api/logout"->response.setBody("{}")
    "/api/usage"->response.setBody("{\"limits\":[]}")
    else->response.setBody("""{"thread":{"id":"${request.path?.substringAfterLast('/')}","title":"外观测试会话","status":"idle"},"turns":[{"id":"t","startedAt":1780000000000,"status":"completed","items":[{"id":"u","type":"userMessage","content":[{"type":"text","text":"这是一条手机消息。"}]},{"id":"a","type":"agentMessage","text":"## 阅读更轻松\n\n消息按作者排列，主题颜色与字号可随时调整。"}]}],"page":{}}""")
   }
  } }
  server.start()
  try {
   appearance.save(Appearance());compose.activityRule.scenario.recreate()
   val vm=ViewModelProvider(compose.activity)[ChatViewModel::class.java]
   compose.runOnIdle {vm.setCertificate(cert.certificatePem().toByteArray());vm.pair("https://127.0.0.1:${server.port}","12345678")}
   compose.waitUntil(30000) {vm.state.value.selected==id && vm.state.value.models.isNotEmpty() && vm.state.value.items.isNotEmpty()}
   screenshot("custom-chat-dark")
   compose.onNodeWithTag("model-picker-button").performClick()
   compose.onNodeWithText("6 Luna").performClick()
   compose.onNodeWithText("中",substring=false).performClick()
   screenshot("model-picker")
   compose.onNodeWithText("完成").performClick()
   assertNull(sent.get())
   assertEquals(ModelChoice("gpt-6-luna","medium"),vm.state.value.modelChoice)
   compose.runOnIdle {vm.select(other)}
   assertEquals(ModelChoice(),vm.state.value.modelChoice)
   compose.runOnIdle {vm.select(id);vm.draft("验证模型参数")}
   compose.onNodeWithContentDescription("发送").performClick()
   compose.waitUntil(15000) {sent.get()!=null && !vm.state.value.sending}
   assertEquals("gpt-6-luna",sent.get().getString("model"));assertEquals("medium",sent.get().getString("thinking"))
   val restored=ChatViewModel(compose.activity.application)
   assertEquals(ModelChoice("gpt-6-luna","medium"),restored.state.value.modelChoice)
   compose.onNodeWithContentDescription("菜单").performClick();compose.onNodeWithText("外观与字号").performClick()
   compose.onNodeWithText("明亮").performClick();repeat(4){compose.onNodeWithText("放大").performClick()}
   compose.onNodeWithText("完成").performClick()
   compose.onNodeWithTag("message-row:t:a").assertExists()
   screenshot("custom-chat-light")
  } finally {storage.save(original);appearance.save(originalAppearance);server.shutdown()}
 }
}
