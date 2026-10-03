package local.codex.lan

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

data class QuestionCard(val id:Any,val kind:String,val message:String,val questions:JSONArray,val schema:JSONObject,val mode:String) {
    val key get() = "$kind:${id.javaClass.simpleName}:$id"
    companion object {fun list(rows:JSONArray?):List<QuestionCard> = (0 until (rows?.length()?:0)).map {rows!!.getJSONObject(it)}.map {QuestionCard(it.get("id"),it.optString("kind"),it.optString("message"),it.optJSONArray("questions")?:JSONArray(),it.optJSONObject("schema")?:JSONObject(),it.optString("mode","form"))}}
}
data class QueuedMessage(val id:String,val text:String,val canSteer:Boolean) {
    companion object {fun list(rows:JSONArray?):List<QueuedMessage> = (0 until (rows?.length()?:0)).map {rows!!.getJSONObject(it)}.map {QueuedMessage(it.getString("id"),it.optString("text"),it.optBoolean("canSteer"))}}
}
private fun JSONArray.strings() = (0 until length()).map {getString(it)}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun QuestionPanel(state:ChatState,answer:(QuestionCard,JSONObject)->Unit,steer:(QueuedMessage)->Unit,openDesktop:()->Unit,refresh:()->Unit,dismiss:()->Unit) {
    ModalBottomSheet(onDismissRequest=dismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("会话互动",style=MaterialTheme.typography.titleLarge)
            Text("答案直接回传到电脑上的同一个问题。",style=MaterialTheme.typography.bodySmall)
            if(state.controlsNotice.isNotBlank())Text(state.controlsNotice)
            if(state.questions.isEmpty()&&state.queued.isEmpty())Text("当前没有待回答问题或排队消息。")
            state.questions.forEach {card -> key(card.key) {QuestionForm(card,state.answering,answer,openDesktop)} }
            if(state.queued.isNotEmpty())Text("排队消息",style=MaterialTheme.typography.titleMedium)
            state.queued.forEach {message ->
                Text(message.text.ifBlank {"附件消息"},maxLines=4)
                Button(onClick={steer(message)},enabled=message.canSteer&&!state.answering,modifier=Modifier.testTag("steer:${message.id}")) {Text("立即引导")}
            }
            TextButton(onClick=refresh,enabled=!state.answering) {Text("刷新互动状态")}
            TextButton(onClick=dismiss) {Text("关闭")}
        }
    }
}
@Composable private fun QuestionForm(card:QuestionCard,busy:Boolean,submit:(QuestionCard,JSONObject)->Unit,openDesktop:()->Unit) {
    val values=remember(card.key) {mutableStateMapOf<String,Any>()}
    if(card.kind=="plan") {
        Text("需要你的回答",style=MaterialTheme.typography.titleMedium)
        for(i in 0 until card.questions.length()) {
            val q=card.questions.getJSONObject(i);val id=q.getString("id")
            Text(q.getString("question"))
            val options=q.optJSONArray("options")?:JSONArray()
            for(j in 0 until options.length()) {val option=options.getJSONObject(j);val label=option.getString("label")
                FilterChip(selected=values[id]==label,onClick={values[id]=label},enabled=!busy,label={Column {Text(label);if(option.optString("description").isNotBlank())Text(option.getString("description"),style=MaterialTheme.typography.bodySmall)}})
            }
            if(options.length()==0||q.optBoolean("isOther"))OutlinedTextField(values[id]?.toString().orEmpty(),{values[id]=it},label={Text(if(options.length()==0)"回答"else "自定义回答")},enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("answer:$id"))
        }
        Button(onClick={submit(card,JSONObject().put("answers",JSONObject().also {result ->values.forEach {(id,value)->result.put(id,JSONObject().put("answers",JSONArray().put(value)))}}))},enabled=!busy&&(0 until card.questions.length()).all {values[card.questions.getJSONObject(it).getString("id")]?.toString()?.isNotBlank()==true},modifier=Modifier.testTag("submit:${card.key}")) {Text(if(busy)"正在提交…"else "提交答案")}
    } else {
        Text(card.message)
        val fields=card.schema.optJSONObject("properties")?:JSONObject()
        val names=fields.keys().asSequence().toList()
        val supported=card.mode in listOf("form","openai/form")&&names.all {name ->val field=fields.getJSONObject(name);field.optString("type") in listOf("string","boolean","number","integer")||(field.optString("type")=="array"&&field.optJSONObject("items")?.optJSONArray("enum")!=null)}
        if(supported) {
            names.forEach {name ->val field=fields.getJSONObject(name);val label=field.optString("title",name)
                Text(label);if(field.optString("description").isNotBlank())Text(field.getString("description"),style=MaterialTheme.typography.bodySmall)
                val enums=field.optJSONArray("enum")
                when {
                    field.optString("type")=="boolean" -> Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {listOf(true to "是",false to "否").forEach {(value,text)->FilterChip(selected=values[name]==value,onClick={values[name]=value},enabled=!busy,label={Text(text)})}}
                    field.optString("type")=="array" -> {
                        field.getJSONObject("items").getJSONArray("enum").strings().forEach {option ->
                            val chosen=(values[name] as? List<*>)?.filterIsInstance<String>().orEmpty()
                            FilterChip(selected=option in chosen,onClick={values[name]=if(option in chosen)chosen-option else chosen+option},enabled=!busy,label={Text(option)})
                        }
                    }
                    enums!=null -> enums.strings().forEach {option ->FilterChip(selected=values[name]==option,onClick={values[name]=option},enabled=!busy,label={Text(option)})}
                    else -> OutlinedTextField(values[name]?.toString().orEmpty(),{values[name]=it},enabled=!busy,visualTransformation=if(field.optString("format")=="password")androidx.compose.ui.text.input.PasswordVisualTransformation()else androidx.compose.ui.text.input.VisualTransformation.None,modifier=Modifier.fillMaxWidth().testTag("answer:$name"),label={Text("填写答案")})
                }
            }
            val required=card.schema.optJSONArray("required")?.strings().orEmpty()
            Button(onClick={val content=JSONObject();values.forEach {(name,value) -> val type=fields.getJSONObject(name).optString("type");content.put(name,when {type=="array"->JSONArray(value as List<*>);type=="integer"->value.toString().toLongOrNull()?:JSONObject.NULL;type=="number"->value.toString().toDoubleOrNull()?:JSONObject.NULL;else->value})};submit(card,JSONObject().put("action","accept").put("content",content))},enabled=!busy&&required.all {name->values[name]!=null&&values[name].toString().isNotBlank()},modifier=Modifier.testTag("submit:${card.key}")) {Text(if(busy)"正在提交…"else "提交答案")}
            TextButton(onClick={submit(card,JSONObject().put("action","decline").put("content",JSONObject.NULL))},enabled=!busy){Text("拒绝回答")}
            TextButton(onClick={submit(card,JSONObject().put("action","cancel").put("content",JSONObject.NULL))},enabled=!busy){Text("取消本次提问")}
        } else {
            Text("此类提问需要在电脑完成。")
            TextButton(onClick=openDesktop) {Text("在电脑打开当前会话")}
        }
    }
}
