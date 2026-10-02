package local.codex.lan

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun UpdatePanel(vm:UpdateViewModel,close:()->Unit) {
    val state by vm.state.collectAsState()
    val context=LocalContext.current
    fun install(){
        val file=state.ready?:return
        try {context.startActivity(ApkUpdates.installIntent(context,file))}
        catch(e:Exception){vm.error("无法打开安装界面：${e.message}")}
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){
        if(context.packageManager.canRequestPackageInstalls())install()else vm.error("尚未允许安装更新，可再次点击“安装更新”开启权限。")
    }
    UpdateDialog(state,vm::check,vm::download,{
        if(context.packageManager.canRequestPackageInstalls())install()
        else try{permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+context.packageName)))}catch(e:Exception){vm.error("请在系统设置允许 Codex LAN 安装应用，再点击安装更新。")}
    },vm::cancel,close)
}

@Composable fun UpdateDialog(state:UpdateState,check:()->Unit,download:()->Unit,install:()->Unit,cancel:()->Unit,close:()->Unit) {
    var expanded by remember(state.release?.version){mutableStateOf(false)}
    AlertDialog(onDismissRequest=close,title={Text("应用更新")},confirmButton={TextButton(onClick=close){Text(if(state.downloading)"稍后查看" else "关闭")}},
        text={Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("当前版本 ${state.installed}",fontSize=12.sp)
            when {
                state.checking->Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Text("正在检查 GitHub Release…")}
                state.available->{
                    Text("发现新版本 ${state.release?.version}",style=MaterialTheme.typography.titleMedium)
                    Text("下载大小约 ${String.format(java.util.Locale.ROOT,"%.1f",(state.release?.size?:0)/(1024.0*1024))} MB",fontSize=12.sp)
                    if(state.release?.notes?.isNotBlank()==true){
                        Text(state.release!!.notes,fontSize=12.sp,maxLines=if(expanded)Int.MAX_VALUE else 8,overflow=TextOverflow.Ellipsis)
                        if(state.release!!.notes.length>180)TextButton(onClick={expanded=!expanded}){Text(if(expanded)"收起版本说明"else "展开版本说明")}
                    }
                }
                state.checked->Text("已是最新版本")
            }
            if(state.downloading){
                LinearProgressIndicator(progress={state.progress/100f},modifier=Modifier.fillMaxWidth())
                Text("正在下载 ${state.progress}%",fontSize=12.sp)
                TextButton(onClick=cancel){Text("取消下载")}
            }else if(state.ready!=null){
                Text("下载及校验完成",fontSize=12.sp)
                Button(onClick=install,modifier=Modifier.fillMaxWidth()){Text("安装更新")}
                Text("首次更新需在系统设置允许本应用安装；返回后由安卓确认安装。",fontSize=11.sp)
            }else if(state.available)Button(onClick=download,modifier=Modifier.fillMaxWidth()){Text("下载更新")}
            if(state.error.isNotBlank())Text(state.error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
            TextButton(onClick=check,enabled=!state.checking&&!state.downloading){Text("检查更新")}
            Text("更新来自公开 GitHub Release，无需连接电脑。",fontSize=11.sp)
        }})
}
