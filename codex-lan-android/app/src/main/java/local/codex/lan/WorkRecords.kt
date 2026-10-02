package local.codex.lan

import org.json.JSONArray
import org.json.JSONObject

data class WorkRecord(val key: String, val title: String, val body: String, val status: String = "") {
    val line get() = listOf(status,title).filter { it.isNotBlank() }.joinToString(" · ").replace(Regex("\\s+")," ")
}
object WorkRecords {
    private fun text(value: Any?): String = when(value) {
        null,JSONObject.NULL -> ""
        is String -> value
        is JSONArray -> (0 until value.length()).map { text(value.opt(it)) }.filter { it.isNotBlank() }.joinToString("\n")
        is JSONObject -> when {
            value.has("text") -> value.optString("text")
            value.has("content") -> text(value.opt("content"))
            value.has("contentItems") -> text(value.opt("contentItems"))
            value.optString("type") in listOf("image","inputImage","audio","inputAudio") -> "[附件]"
            else -> value.toString(2)
        }
        else -> value.toString()
    }
    private fun bounded(value: String) = if(value.length>20000)value.take(20000)+"\n[记录较长，请在电脑查看完整输出]" else value
    private fun parts(vararg values: Pair<String,String>) = bounded(values.filter { it.second.isNotBlank() }.joinToString("\n\n") { it.first+"\n"+it.second })
    fun from(key: String, item: JSONObject): WorkRecord? {
        val status=when(item.optString("status")) { "completed"->"已完成";"failed"->"失败";"interrupted"->"已中断";"inProgress","running","pending"->"运行中";else->"" }
        return when(item.optString("type")) {
            "agentMessage" -> if(item.optString("phase")=="commentary") {
                val message=item.optString("text")
                WorkRecord(key,"进度 · "+message,bounded(message))
            } else null
            "reasoning" -> {
                val summary=text(item.opt("summary"))
                if(summary.isBlank())null else WorkRecord(key,"思考摘要 · $summary",bounded(summary),status)
            }
            "commandExecution" -> {
                val command=text(item.opt("command"))
                WorkRecord(key,"执行命令 · "+command,parts("命令" to command,"目录" to item.optString("cwd"),"输出" to text(item.opt("aggregatedOutput")?:item.opt("output")),"退出码" to (if(item.has("exitCode")&&!item.isNull("exitCode"))item.opt("exitCode").toString()else "")),status)
            }
            "fileChange" -> {
                val changes=item.optJSONArray("changes")?:JSONArray()
                val paths=(0 until changes.length()).mapNotNull { changes.optJSONObject(it)?.optString("path")?.takeIf { p -> p.isNotBlank() } }
                val details=(0 until changes.length()).map { changes.optJSONObject(it) }.filterNotNull().joinToString("\n\n") { change ->
                    listOf(change.optString("path"),text(change.opt("kind")),change.optString("diff").ifBlank { change.optString("patch") }).filter { it.isNotBlank() }.joinToString("\n")
                }
                WorkRecord(key,"修改文件 · "+paths.joinToString(", "),parts("文件" to details,"输出" to text(item.opt("output"))),status)
            }
            "mcpToolCall","dynamicToolCall" -> {
                val tool=item.optString("tool").ifBlank { item.optString("name").ifBlank { item.optString("toolName") } }
                val name=listOf(item.optString("server").ifBlank { item.optString("namespace") },tool).filter { it.isNotBlank() }.joinToString(".")
                val arguments=item.opt("arguments")
                val detail=when(arguments){is JSONObject->arguments.toString(2);is JSONArray->arguments.toString(2);else->text(arguments)}
                val target=if(arguments is JSONObject)listOf("command","cmd","path","filePath","url","query").map { arguments.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()else ""
                WorkRecord(key,"调用工具 · $name"+(if(target.isBlank())""else " · $target"),parts("工具" to name,"参数" to detail,"结果" to text(item.opt("result")?:item.opt("output")),"错误" to text(item.opt("error"))),status)
            }
            "webSearch" -> {
                val query=item.optString("query").ifBlank { item.optJSONObject("action")?.optString("query").orEmpty() }
                WorkRecord(key,"搜索资料 · $query",parts("查询" to query,"操作" to text(item.opt("action")?:item.opt("actions")),"结果" to text(item.opt("result"))),status)
            }
            else -> null
        }
    }
}
