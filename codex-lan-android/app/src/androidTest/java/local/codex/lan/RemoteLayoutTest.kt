package local.codex.lan

import android.content.ContentValues
import android.provider.MediaStore
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class RemoteLayoutTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 @Test fun uploadsRestoreWithDraftAndPermissionsOpenSelectedDesktopChat() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val storage=SecureStore(context);val original=storage.read()
  val cert=HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
  val server=MockWebServer();server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false)
  val id="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";val file="cccccccc-cccc-4ccc-8ccc-cccccccccccc"
  val uploaded=AtomicReference<String>();val sent=AtomicReference<JSONObject>();val opens=AtomicInteger()
  server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
   val response=MockResponse().setHeader("Content-Type","application/json")
   return when {
    request.path=="/api/pair"->response.setHeader("Set-Cookie","codex_lan=${"e".repeat(64)}; Secure").setBody("{\"csrf\":\"test-csrf\"}")
    request.path=="/api/threads"->response.setBody("""{"threads":[{"id":"$id","title":"Remote 布局测试"}],"projects":[]}""")
    request.path=="/api/models"->response.setBody("""{"models":[{"id":"gpt-6.1-sol","efforts":["high"]}]}""")
    request.path=="/api/usage"->response.setBody("{\"limits\":[]}")
    request.path?.contains("/uploads?")==true->{uploaded.set(request.body.readUtf8());assertNotNull(request.getHeader("X-CSRF-Token"));response.setBody("""{"id":"$file","name":"手机附件.txt","size":10,"image":false}""")}
    request.path=="/api/threads/$id/messages"->{sent.set(JSONObject(request.body.readUtf8()));response.setBody("{\"state\":\"sent\"}")}
    request.path=="/api/threads/$id/permissions"->response.setBody("""{"editable":false,"message":"请在 Desktop 输入区点击盾牌修改。"}""")
    request.path=="/api/threads/$id/open-desktop"->{opens.incrementAndGet();response.setBody("{}")}
    request.path=="/api/logout"->response.setBody("{}")
    else->response.setBody("""{"thread":{"id":"$id","title":"Remote 布局测试","status":"idle"},"turns":[{"id":"t","status":"completed","items":[{"id":"a","type":"agentMessage","text":"## 与电脑共享会话\n\n从加号上传文件，模型与权限入口集中在输入区。"}]}],"page":{}}""")
   }
  }}
  server.start()
  val uri=requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME,"手机附件.txt");put(MediaStore.MediaColumns.MIME_TYPE,"text/plain") }))
  try {
   context.contentResolver.openOutputStream(uri)!!.use { it.write("phone-file".toByteArray()) }
   val vm=ViewModelProvider(compose.activity)[ChatViewModel::class.java]
   compose.runOnIdle {vm.setCertificate(cert.certificatePem().toByteArray());vm.pair("https://127.0.0.1:${server.port}","12345678")}
   compose.waitUntil(20000){vm.state.value.selected==id && vm.state.value.models.isNotEmpty()}
   compose.onNodeWithContentDescription("上传文件").performClick()
   compose.onNodeWithText("选择图片").assertIsDisplayed();compose.onNodeWithText("选择文件").assertIsDisplayed()
   compose.runOnIdle { InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) }
   compose.waitForIdle()
   compose.runOnIdle {vm.uploadFiles(listOf(uri))}
   compose.waitUntil(15000){!vm.state.value.uploading && vm.state.value.attachments.size==1}
   assertEquals("phone-file",uploaded.get());compose.onNodeWithTag("pending-file:$file").assertIsDisplayed()
   val restored=ChatViewModel(compose.activity.application);assertEquals(vm.state.value.attachments,restored.state.value.attachments)
   compose.onNodeWithContentDescription("权限设置").performClick()
   compose.onNodeWithText("跟随 Desktop").assertIsDisplayed()
   compose.onNodeWithText("在电脑打开当前会话").performClick()
   compose.waitUntil(10000){opens.get()==1}
   compose.runOnIdle {vm.chooseModel(ModelChoice("gpt-6.1-sol","high"));vm.draft("请查看附件")}
   compose.waitForIdle()
   val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
   java.io.File(context.getExternalFilesDir(null),"qa-remote-layout.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
   compose.onNodeWithContentDescription("发送").performClick()
   compose.waitUntil(15000){sent.get()!=null&&!vm.state.value.sending}
   assertEquals("请查看附件",sent.get().getString("prompt"));assertEquals(file,sent.get().getJSONArray("attachments").getString(0))
   assertTrue(vm.state.value.attachments.isEmpty())
  } finally {context.contentResolver.delete(uri,null,null);storage.save(original);server.shutdown()}
 }
}
