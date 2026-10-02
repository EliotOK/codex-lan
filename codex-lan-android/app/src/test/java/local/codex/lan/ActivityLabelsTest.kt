package local.codex.lan
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class ActivityLabelsTest {
    private fun snapshot(type:String="active",items:String="[]",turn:String="inProgress",flags:String="[]")=JSONObject("""{"thread":{"status":{"type":"$type","activeFlags":$flags}},"turns":[{"startedAt":2,"status":"$turn","items":$items},{"startedAt":1,"status":"failed","items":[]}]}""")
    @Test fun reportsLatestReasoningAndRunningTools(){
        assertEquals("思考中",ActivityLabels.from(snapshot(items="""[{"type":"reasoning"}]""")))
        assertEquals("正在执行命令",ActivityLabels.from(snapshot(items="""[{"type":"commandExecution","status":"inProgress"}]""")))
        assertEquals("处理中",ActivityLabels.from(snapshot(items="""[{"type":"commandExecution","status":"completed"}]""")))
    }
    @Test fun reportsWaitingCompletedAndInterruptedStates(){
        assertEquals("等待电脑确认",ActivityLabels.from(snapshot(flags="""["waitingOnApproval"]""")))
        assertEquals("等待你的输入",ActivityLabels.from(snapshot(flags="""["waitingOnUserInput"]""")))
        assertEquals("已完成",ActivityLabels.from(snapshot(type="idle",turn="completed")))
        assertEquals("已中断",ActivityLabels.from(snapshot(type="idle",turn="interrupted")))
    }
    @Test fun boundsImageDecodeMemoryForThumbnailsAndLargeImages(){
        assertEquals(1,ImageSizing.sample(800,600,false))
        val thumbnail=ImageSizing.sample(12000,9000,false)
        assertTrue((12000L/thumbnail)*(9000L/thumbnail)<=1_000_000)
        val full=ImageSizing.sample(12000,9000,true)
        assertTrue((12000L/full)*(9000L/full)<=4_000_000)
        assertTrue(full<=thumbnail)
    }
    @Test fun retainsAttachmentReferencesDuringMessageRendering(){
        val turn=JSONObject("""{"id":"t","items":[{"id":"m","type":"userMessage","content":[{"type":"text","text":"截图"},{"type":"localImage","imageId":"${"a".repeat(64)}","name":"截图.png"}]}]}""")
        val item=ChatViewModel.renderTurn(turn).first()
        assertEquals("截图",item.text);assertEquals("/api/images/${"a".repeat(64)}",item.images.single().reference)
    }
}
