package local.codex.lan
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
class UploadedFileTest {
 @Test fun attachmentEchoReconciliationRequiresMatchingFilesAndRestoresDraftMetadata() {
  val file=UploadedFile("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","照片.jpg",8,true)
  val other=file.copy(id="bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
  assertEquals(file,UploadedFile.parse(file.json()))
  val outgoing=OutgoingMessages();outgoing.add("r","s","t","",emptyList(),files=listOf(file))
  val restored=OutgoingMessages();restored.restore(outgoing.json())
  assertEquals(listOf(file),restored.merge("s","t",emptyList()).single().files)
  assertFalse(restored.reconcile("s","t",listOf(ChatItem("bad","你","",files=listOf(other)))))
  assertTrue(restored.reconcile("s","t",listOf(ChatItem("ok","你","",files=listOf(file)))))
 }
 @Test fun forwardedFilesDisplayRequestAndPreviewWithAuthenticatedImagePath() {
  val file=UploadedFile("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","照片.jpg",8,true)
  val raw="# Files mentioned by the user:\n\n## 照片.jpg: C:/uploads/照片.jpg\nImage attachment: true\n\n## My request:\n\n看图"
  val turn=JSONObject().put("id","t").put("lanThreadId","bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
    .put("items",org.json.JSONArray().put(JSONObject().put("id","i").put("type","functionCallOutput").put("namespace","codex_app").put("name","send_message_to_thread")
      .put("output",JSONObject().put("text","<codex_delegation><input>$raw</input></codex_delegation>"))
      .put("lanAttachments",UploadedFile.json(listOf(file)))))
  val items=ChatViewModel.renderTurn(turn);val message=items.first()
  assertEquals("看图",message.text);assertEquals(listOf(file),message.files)
  assertTrue(message.images.single().reference.startsWith("/api/upload-images/"))
  assertTrue(items.last().detail)
 }
}
