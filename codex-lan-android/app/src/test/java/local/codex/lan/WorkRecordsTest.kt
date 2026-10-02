package local.codex.lan
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
class WorkRecordsTest {
 @Test fun recordsRetainOrderAndPublicSummariesWithOneLineLabels() {
  val turn=JSONObject("""{"id":"t","items":[
   {"id":"r","type":"reasoning","summary":["核对输入格式。","检查文件大小。"],"content":["provider-private"]},
   {"id":"p","type":"agentMessage","phase":"commentary","text":"正在验证\n上传文件。"},
   {"id":"c","type":"commandExecution","command":"node\n--test","aggregatedOutput":"21 passed","exitCode":0,"status":"completed"},
   {"id":"a","type":"agentMessage","phase":"final","text":"验证完成。"}]}""")
  val items=ChatViewModel.renderTurn(turn);val group=items.first()
  assertTrue(group.detail);assertEquals(listOf("t:r","t:p","t:c"),group.records.map { it.key })
  assertEquals("核对输入格式。\n检查文件大小。",group.records[0].body)
  assertEquals("进度 · 正在验证 上传文件。",group.records[1].line)
  assertTrue(group.records[2].body.contains("21 passed"));assertTrue(group.records[2].body.endsWith("0"))
  assertTrue(group.records.none { it.line.contains('\n') || it.body.contains("provider-private") })
  assertEquals("Codex",items.last().role)
 }
 @Test fun missingSummaryAndStructuredToolAndFileRecordsRemainReadable() {
  assertNull(WorkRecords.from("r",JSONObject("""{"type":"reasoning"}""")))
  val file=WorkRecords.from("f",JSONObject("""{"type":"fileChange","changes":[{"path":"src/app.kt","kind":{"type":"update"},"diff":"+ new line"}],"status":"completed"}"""))!!
  assertTrue(file.title.contains("src/app.kt"));assertTrue(file.body.contains("+ new line"))
  val tool=WorkRecords.from("m",JSONObject("""{"type":"mcpToolCall","server":"desktop","tool":"read","arguments":{"path":"src/app.kt","text":"new content"},"result":{"content":[{"type":"text","text":"message"}]}}"""))!!
  assertTrue(tool.title.contains("desktop.read"));assertTrue(tool.title.contains("src/app.kt"));assertTrue(tool.body.contains("new content"));assertTrue(tool.body.contains("src/app.kt"));assertTrue(tool.body.contains("message"))
 }
}
