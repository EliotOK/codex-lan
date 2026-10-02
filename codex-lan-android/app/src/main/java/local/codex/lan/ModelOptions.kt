package local.codex.lan

import org.json.JSONObject
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

fun modelDisplayName(id: String) = id.removePrefix("gpt-").split('-').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
fun ModelChoice.compactLabel() = model?.let { modelDisplayName(it)+(thinking?.let { effort -> " "+effortLabel(effort) } ?: "") } ?: "跟随桌面"
data class ModelOption(val id: String, val efforts: List<String>)
data class ModelChoice(val model: String? = null, val thinking: String? = null) {
    val label get() = model ?: "跟随桌面"
    fun json() = JSONObject().also { value -> model?.let { value.put("model", it) }; thinking?.let { value.put("thinking", it) } }
    companion object {
        fun parse(value: JSONObject?) = ModelChoice(value?.optString("model")?.takeIf { it.isNotBlank() && it != "null" }, value?.optString("thinking")?.takeIf { it.isNotBlank() && it != "null" })
        fun catalog(value: JSONObject): List<ModelOption> {
            val rows = value.optJSONArray("models") ?: return emptyList()
            return (0 until rows.length()).map { rows.getJSONObject(it) }.mapNotNull { row ->
                val id=row.optString("id"); val efforts=row.optJSONArray("efforts") ?: return@mapNotNull null
                if (!id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,80}"))) return@mapNotNull null
                ModelOption(id, (0 until efforts.length()).map { efforts.getString(it) }.distinct())
            }.distinctBy { it.id }
        }
    }
}
fun effortLabel(value: String) = when(value) { "none"->"无";"minimal"->"最低";"low"->"低";"medium"->"中";"high"->"高";"xhigh"->"更高";"max"->"最高";"ultra"->"极高";else->value }
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ModelPanel(state: ChatState, choose: (ModelChoice) -> Unit, refresh: () -> Unit, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest=dismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("模型与推理强度", style=MaterialTheme.typography.titleLarge)
            Text("选择随下一条消息提交到当前会话；发送前不会修改桌面设置。", style=MaterialTheme.typography.bodySmall)
            if(state.modelNotice.isNotBlank())Text(state.modelNotice, color=MaterialTheme.colorScheme.error)
            FilterChip(selected=state.modelChoice.model==null, onClick={choose(ModelChoice())}, label={Text("跟随桌面当前设置")})
            state.models.forEach { option ->
                FilterChip(selected=state.modelChoice.model==option.id, onClick={choose(ModelChoice(option.id, if("high" in option.efforts)"high"else option.efforts.firstOrNull()))}, label={Text(modelDisplayName(option.id))}, modifier=Modifier.fillMaxWidth())
            }
            state.models.firstOrNull { it.id==state.modelChoice.model }?.let { model ->
                Text("推理强度", style=MaterialTheme.typography.titleMedium)
                FilterChip(selected=state.modelChoice.thinking==null, onClick={choose(state.modelChoice.copy(thinking=null))}, label={Text("跟随桌面强度")})
                model.efforts.chunked(3).forEach { row -> Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    row.forEach { effort -> FilterChip(selected=state.modelChoice.thinking==effort, onClick={choose(state.modelChoice.copy(thinking=effort))}, label={Text(effortLabel(effort))}) }
                } }
            }
            TextButton(onClick=refresh) { Text("刷新可用模型") }
            TextButton(onClick=dismiss, modifier=Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
}
