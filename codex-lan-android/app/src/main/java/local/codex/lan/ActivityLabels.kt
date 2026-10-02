package local.codex.lan

import org.json.JSONObject

object ActivityLabels {
    fun from(snapshot: JSONObject): String {
        val status=snapshot.optJSONObject("thread")?.opt("status")
        val type=if(status is JSONObject)status.optString("type")else status?.toString()
        val flags=if(status is JSONObject)status.optJSONArray("activeFlags")?.toString().orEmpty()else ""
        if(type=="systemError")return "执行出错"
        if(type in listOf("awaitingApproval","waitingForApproval")||flags.contains("waitingOnApproval"))return "等待电脑确认"
        if(type=="awaitingUserInput"||flags.contains("waitingOnUserInput"))return "等待你的输入"
        val turns=snapshot.optJSONArray("turns")
        val newest=(0 until (turns?.length()?:0)).map{turns!!.getJSONObject(it)}.maxByOrNull{it.optLong("startedAt")}
        val active=ChatViewModel.isActiveStatus(status)||newest?.optString("status")=="inProgress"
        if(!active)return when(newest?.optString("status")){"failed"->"执行失败";"interrupted"->"已中断";"completed"->"已完成";else->"就绪"}
        val items=newest?.optJSONArray("items")
        val values=(0 until (items?.length()?:0)).map{items!!.getJSONObject(it)}
        val running=values.lastOrNull{it.optString("status") in listOf("inProgress","running","pending")}
        return when(running?.optString("type")){
            "commandExecution"->"正在执行命令";"fileChange"->"正在修改文件";"mcpToolCall","dynamicToolCall"->"正在调用工具";"webSearch"->"正在搜索";"reasoning"->"思考中"
            else->if(values.lastOrNull()?.optString("type")=="reasoning")"思考中"else "处理中"
        }
    }
}

object ImageSizing {
    fun sample(width: Int,height: Int,full: Boolean): Int {
        require(width>0&&height>0)
        val edge=if(full)4096 else 1024
        val pixels=if(full)4_000_000L else 1_000_000L
        var sample=1
        while(width/sample>edge||height/sample>edge||(width.toLong()/sample)*(height.toLong()/sample)>pixels)sample*=2
        return sample
    }
}
