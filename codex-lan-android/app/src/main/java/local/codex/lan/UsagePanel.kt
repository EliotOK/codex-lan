package local.codex.lan

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun UsagePanel(state:ChatState,refresh:()->Unit,close:()->Unit) {
    AlertDialog(onDismissRequest=close,title={Text("账户剩余用量")},confirmButton={TextButton(onClick=close){Text("关闭")}},
        dismissButton={TextButton(onClick=refresh,enabled=!state.usageRefreshing){Text(if(state.usageRefreshing)"读取中…" else "刷新用量")}},
        text={Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("与桌面共享账户额度，约每分钟更新。",fontSize=12.sp)
            state.usage?.buckets?.forEach { bucket ->
                Text(bucket.name,style=MaterialTheme.typography.titleSmall)
                bucket.windows.forEach { window ->
                    Text("${window.label}剩余 ${window.percent}")
                    window.remaining?.let { value -> LinearProgressIndicator(progress={ (value/100).toFloat() },modifier=Modifier.fillMaxWidth()) }
                    Text("重置：${window.resetText}（手机当地时间）",fontSize=11.sp)
                }
                bucket.credits?.let { Text("额外额度：$it credits",fontSize=12.sp) }
            }
            if(state.usage?.buckets?.isEmpty()==true)Text("桌面尚未提供用量数据。")
            if(state.usageNotice.isNotBlank())Text(state.usageNotice,fontSize=12.sp,color=MaterialTheme.colorScheme.error)
            if(state.usage==null&&state.usageNotice.isBlank())Text("正在读取用量…")
            state.usage?.fetchedAt?.takeIf{it>0}?.let { Text("更新：${java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))}",fontSize=11.sp) }
        }})
}
