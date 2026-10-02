package local.codex.lan

import android.os.Bundle
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.net.ConnectivityManager
import android.net.Network
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mint: Color
    @Composable get() = MaterialTheme.colorScheme.primary
private val Background: Color
    @Composable get() = MaterialTheme.colorScheme.background
private val SurfaceColor: Color
    @Composable get() = MaterialTheme.colorScheme.surface
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            val vm: ChatViewModel = viewModel()
            val appearanceStore = remember { AppearanceStore(applicationContext) }
            var appearance by remember { mutableStateOf(appearanceStore.read()) }
            LaunchedEffect(appearance.theme) {
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(appearance.colors().background.toArgb()))
                val bars=if(appearance.theme=="light")SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT)else SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle=bars,navigationBarStyle=bars)
            }
            DisposableEffect(vm) {
                val observer = LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP)vm.pauseReads() }
                lifecycle.addObserver(observer)
                val connectivity = getSystemService(ConnectivityManager::class.java)
                val callback = object: ConnectivityManager.NetworkCallback() { override fun onAvailable(network:Network) { lifecycleScope.launch { vm.reconnect(false) } } }
                connectivity.registerDefaultNetworkCallback(callback)
                onDispose { lifecycle.removeObserver(observer);connectivity.unregisterNetworkCallback(callback);vm.pauseReads() }
            }
            LaunchedEffect(vm) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.poll() } }
            CompositionLocalProvider(LocalAppearance provides appearance) {
                MaterialTheme(colorScheme = appearance.colors()) {
                    val state by vm.state.collectAsStateWithLifecycle()
                    Surface(Modifier.fillMaxSize(), color = Background, contentColor = MaterialTheme.colorScheme.onBackground) {
                        App(state, vm) { next -> appearance = next; appearanceStore.save(next) }
                    }
                }
            }
        }
    }
}

@Composable private fun App(state: ChatState, vm: ChatViewModel, changeAppearance: (Appearance) -> Unit) {
    val updater:UpdateViewModel=viewModel()
    var showUpdate by rememberSaveable{mutableStateOf(false)}
    var showAppearance by rememberSaveable { mutableStateOf(false) }
    fun openUpdate(){showUpdate=true;if(!updater.state.value.checked)updater.check()}
    var showThreads by rememberSaveable { mutableStateOf(false) }
    var showFingerprint by remember { mutableStateOf(false) }
    var acknowledge by remember { mutableStateOf(false) }
    var disconnect by remember { mutableStateOf(false) }
    BackHandler(state.paired && showThreads) { showThreads = false }
    Column(Modifier.fillMaxSize().background(Background).safeDrawingPadding().imePadding()) {
        if (!state.paired) Box(Modifier.fillMaxSize()){
            PairScreen(state, vm)
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                TextButton(onClick={showAppearance=true}) { Text("外观与字号") }
                TextButton(onClick=::openUpdate) { Text("检查更新") }
            }
        }
        else {
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                Surface(shape=CircleShape,color=SurfaceColor) { IconButton(onClick={showThreads=!showThreads}) { RemoteIcon("back",if(showThreads)"返回"else "会话") } }
                Surface(shape=RoundedCornerShape(28.dp),color=SurfaceColor,modifier=Modifier.weight(1f).clickable { showThreads=!showThreads }) {
                    Column(Modifier.padding(horizontal=18.dp,vertical=10.dp)) {
                        Text(if(showThreads)"桌面会话"else state.title,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=15.sp,fontWeight=FontWeight.Medium)
                        Text(if(state.connected)"本地电脑 · ● 已连接"else "本地电脑 · ○ 重连中",fontSize=11.sp,color=if(state.connected)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,maxLines=1)
                    }
                }
                Surface(shape=RoundedCornerShape(28.dp),color=SurfaceColor) {
                    Row {
                        IconButton(onClick={showFingerprint=true}) { RemoteIcon("laptop","连接和证书") }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick={menu=true}) { RemoteIcon("more","菜单") }
                            DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                                DropdownMenuItem(text={Text("外观与字号")},onClick={menu=false;showAppearance=true})
                                DropdownMenuItem(text={Text("立即刷新")},onClick={menu=false;vm.refreshNow()})
                                DropdownMenuItem(text={Text("重新连接")},onClick={menu=false;vm.reconnect()})
                                DropdownMenuItem(text={Text("连接和证书")},onClick={menu=false;showFingerprint=true})
                                DropdownMenuItem(text={Text("检查更新")},onClick={menu=false;openUpdate()})
                                DropdownMenuItem(text={Text("断开配对")},enabled=!state.sending&&!state.uploading,onClick={menu=false;disconnect=true})
                            }
                        }
                    }
                }
            }
            if (state.notice.isNotBlank()) Notice(state.notice, state.error)
            if (showThreads) ThreadList(state, onSelect = { vm.select(it); showThreads = false }, modifier = Modifier.weight(1f))
            else Conversation(state, vm, Modifier.weight(1f), acknowledge = { acknowledge = true })
        }
    }
    if(showUpdate)UpdatePanel(updater){showUpdate=false}
    if(showAppearance)AppearancePanel(LocalAppearance.current,changeAppearance){showAppearance=false}
    if (showFingerprint) AlertDialog(onDismissRequest = { showFingerprint = false }, confirmButton = {
        TextButton(onClick = { showFingerprint = false }) { Text("关闭") }
    }, title = { Text("当前电脑") }, text = { SelectionContainer { Text("${state.endpoint}\n\n配对码在电脑连接面板的“连接设置”中修改，已配对设备保持连接。\n\n证书 SHA-256\n${state.fingerprint}", fontSize = 12.sp) } })
    if (acknowledge) AlertDialog(onDismissRequest = { acknowledge = false }, title = { Text("核对发送记录") }, text = {
        Text("请查看对应会话，确认这条消息是否已经出现。解除提示后不会自动重新发送，草稿仍会保留。\n\n${state.pending?.prompt.orEmpty().take(160)}")
    }, confirmButton = { TextButton(onClick = { acknowledge = false; vm.acknowledgePending() }) { Text("已核对会话") } },
        dismissButton = { TextButton(onClick = { acknowledge = false }) { Text("返回查看") } })
    if (disconnect) AlertDialog(onDismissRequest = { disconnect = false }, title = { Text("断开配对") }, text = {
        Text("断开后需要重新输入配对码，草稿和待核对的发送记录会保留。")
    }, confirmButton = { TextButton(onClick = { disconnect = false; vm.disconnect() }) { Text("断开") } },
        dismissButton = { TextButton(onClick = { disconnect = false }) { Text("取消") } })
}

@Composable private fun PairScreen(state: ChatState, vm: ChatViewModel) {
    var address by rememberSaveable(state.endpoint) { mutableStateOf(state.endpoint) }
    var code by remember { mutableStateOf("") }
    var candidate by remember { mutableStateOf<ByteArray?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (output.size() <= 16384) {
                            val count = input.read(buffer, 0, minOf(buffer.size, 16385 - output.size()))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("无法读取证书")
                }
                require(data.size <= 16384) { "证书文件过大" }
                LanClient.parseCertificate(data).checkValidity()
                candidate = data
            } catch (e: Exception) { vm.report(e) }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(32.dp))
        Text("⌘", color = Mint, fontSize = 44.sp)
        Text("桌面的会话，\n随身继续。", fontSize = 30.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold)
        Text("连接同一 Wi-Fi，在电脑连接面板查看地址和 8 位配对码。", color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 23.sp)
        OutlinedTextField(address, { address = it }, label = { Text("电脑地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            enabled = !state.connecting, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) }, label = { Text("8 位配对码") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            enabled = !state.connecting, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        Button(onClick = { vm.pair(address, code); code = "" }, enabled = code.length == 8 && !state.connecting, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            if (state.connecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Background) else Text("连接电脑", fontWeight = FontWeight.SemiBold)
        }
        if (state.notice.isNotBlank()) Notice(state.notice, state.error)
        Text("此安装包已包含当前电脑的公开证书。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        SelectionContainer { Text("SHA-256\n${state.fingerprint}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
        TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.connecting) { Text("导入其他电脑证书 (.pem)") }
    }
    candidate?.let { bytes -> AlertDialog(onDismissRequest = { candidate = null }, title = { Text("信任电脑证书") }, text = {
        SelectionContainer { Text("请与电脑上的证书 SHA-256 核对：\n\n${LanClient.fingerprint(bytes)}\n\n证书仅用于本应用连接电脑。", fontSize = 12.sp) }
    }, confirmButton = { TextButton(onClick = { candidate = null; vm.setCertificate(bytes) }) { Text("指纹一致，导入") } },
        dismissButton = { TextButton(onClick = { candidate = null }) { Text("取消") } }) }
}

@Composable private fun Notice(text: String, error: Boolean) {
    Text(text, Modifier.fillMaxWidth().background(if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
}
@Composable private fun ThreadList(state: ChatState, onSelect: (String) -> Unit, modifier: Modifier) {
    var search by rememberSaveable { mutableStateOf("") }
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val groups = remember(state.threads, search) { ProjectGroups.from(state.threads, search) }
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(search, { search = it }, label = { Text("搜索会话") }, placeholder = { Text("会话名称、项目或路径") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(16.dp))
        if (state.projectNotice.isNotBlank()) Text(state.projectNotice, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.threads.isEmpty()) item { Text("正在读取本机会话…", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else if (groups.isEmpty()) item { Text("没有匹配的会话", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            groups.forEach { group ->
                val expanded = search.isNotBlank() || group.key !in collapsed
                item(key = "group:${group.key}") {
                    Column(Modifier.fillMaxWidth().testTag("project-header:${group.key}")
                        .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
                        .clickable {
                            collapsed = if (group.key in collapsed) collapsed - group.key else collapsed + group.key
                        }.padding(horizontal = 4.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (expanded) "▾" else "▸", color = Mint, modifier = Modifier.padding(end = 8.dp))
                            Text(group.name, Modifier.weight(1f), color = Mint, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${group.threads.size} 个会话", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                        if (group.path.isNotBlank()) Text(group.path, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 20.dp))
                    }
                }
                if (expanded) items(group.threads, key = { it.id }) { thread ->
                    Surface(shape = MaterialTheme.shapes.medium, color = if (thread.id == state.selected) MaterialTheme.colorScheme.secondaryContainer else SurfaceColor,
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp).clickable { onSelect(thread.id) }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(thread.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text(if (thread.active) "● 正在执行" else if (thread.id == state.selected) "当前会话" else "点击继续",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Conversation(state: ChatState, vm: ChatViewModel, modifier: Modifier, acknowledge: () -> Unit) {
    val context = LocalContext.current
    var voiceThread by rememberSaveable { mutableStateOf("") }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==Activity.RESULT_OK) {
            val text=result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if(text.isNullOrBlank())vm.showNotice("没有识别到文字，可以再试一次。",true) else vm.voiceResult(voiceThread,text)
        }
        voiceThread=""
    }
    var uploadThread by rememberSaveable { mutableStateOf("") }
    val filePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if(uris.isNotEmpty()) {
            if(uploadThread==vm.state.value.selected)vm.uploadFiles(uris) else vm.showNotice("会话已切换，请在目标会话重新选择附件。",true)
        };uploadThread=""
    }
    var showAttachments by rememberSaveable { mutableStateOf(false) }
    var showPermissions by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by rememberSaveable(state.selected) { mutableStateOf(true) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showUsage by rememberSaveable { mutableStateOf(false) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var historySearch by rememberSaveable(state.selected) { mutableStateOf("") }
    var anchorKey by rememberSaveable(state.selected) { mutableStateOf("") }
    val nearBottom by remember { derivedStateOf { !list.canScrollForward } }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    var manualScroll by remember(state.selected) { mutableStateOf(false) }
    val latestCount by rememberUpdatedState(state.items.size)
    suspend fun latest() {
        if (latestCount==0) return
        list.scrollToItem(latestCount)
        withFrameNanos { }
        val last = list.layoutInfo.visibleItemsInfo.lastOrNull()
        if (last != null) list.scrollBy((last.offset + last.size + list.layoutInfo.afterContentPadding - list.layoutInfo.viewportEndOffset).coerceAtLeast(0).toFloat())
    }
    LaunchedEffect(state.selected) { follow = true }
    LaunchedEffect(state.items,follow,nearBottom) {
        if (follow && !nearBottom && state.items.isNotEmpty()) latest()
    }
    LaunchedEffect(dragging,list.isScrollInProgress) {
        if(dragging){manualScroll=true;follow=false}
        else if(manualScroll&&!list.isScrollInProgress){follow=nearBottom;manualScroll=false}
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
            if(state.connected&&state.active)CircularProgressIndicator(Modifier.size(12.dp),strokeWidth=1.5.dp)
            Text(if(!state.connected)"等待重连" else if(state.sending)"正在提交消息" else state.activityLabel,
                Modifier.weight(1f).padding(start=8.dp),fontSize=11.sp,color=Mint)
            TextButton(onClick={showHistory=true},enabled=state.items.isNotEmpty()){Text("历史位置")}
            TextButton(onClick={showUsage=true;vm.refreshUsage()}){Text("用量")}
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                item(key = "history") { if (state.cursor != null) TextButton(onClick = vm::loadOlder, enabled = !state.loadingOlder) { Text(if (state.loadingOlder) "正在读取…" else "查看更早消息") } }
                items(state.items, key = { it.key }) { message -> MessageCard(message,vm) }
                if (state.items.isEmpty()) item { Text(if (state.selected.isBlank()) "从会话列表选择聊天" else "正在同步桌面聊天…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 32.dp)) }
            }
            if(state.items.isNotEmpty()&&!nearBottom)FilledTonalButton(onClick = { follow = true;anchorKey="";scope.launch { latest() } }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) { Text("↓ 最底部") }
        }
        if (state.pending != null) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("发送记录待核对", modifier = Modifier.weight(1f).padding(start = 8.dp), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp)
                TextButton(onClick = { vm.select(state.pending.thread) }) { Text("查看会话") }
                TextButton(onClick = acknowledge, enabled = !state.sending) { Text("核对完成") }
            }
        }
        Surface(shape=RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp)) {
            Column(Modifier.padding(start=14.dp,end=10.dp,top=16.dp,bottom=8.dp)) {
                if(state.attachments.isNotEmpty())Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    state.attachments.forEach { file -> InputChip(selected=false,onClick={vm.removeAttachment(file.id)},enabled=!state.sending&&!state.uploading,
                        label={Text(file.name,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=170.dp))},trailingIcon={Text("×")},modifier=Modifier.testTag("pending-file:${file.id}")) }
                }
                BasicTextField(state.draft,vm::draft,modifier=Modifier.fillMaxWidth().padding(horizontal=4.dp).heightIn(min=42.dp).testTag("message-input"),
                    enabled=state.selected.isNotBlank()&&!state.sending,maxLines=5,cursorBrush=SolidColor(MaterialTheme.colorScheme.onSurface),
                    textStyle=LocalTextStyle.current.copy(color=MaterialTheme.colorScheme.onSurface,fontSize=LocalAppearance.current.fontSize.sp,lineHeight=(LocalAppearance.current.fontSize*1.5).sp),
                    decorationBox={inner -> Box { if(state.draft.isEmpty())Text("继续这个会话…",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=LocalAppearance.current.fontSize.sp);inner() } })
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={showAttachments=true},enabled=state.selected.isNotBlank()&&!state.sending&&!state.uploading) { if(state.uploading)CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)else RemoteIcon("plus","上传文件") }
                    IconButton(onClick={showPermissions=true;vm.refreshPermissions()},enabled=state.selected.isNotBlank()) { RemoteIcon("shield","权限设置") }
                    TextButton(onClick={showModels=true;vm.refreshModels()},enabled=!state.sending&&state.selected.isNotBlank(),modifier=Modifier.weight(1f).testTag("model-picker-button")) {
                        Text(state.modelChoice.compactLabel(),maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurface,fontSize=13.sp)
                    }
                    IconButton(onClick={
                        val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN").putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true)
                            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1).putExtra(RecognizerIntent.EXTRA_PROMPT,"说话后生成文字，请确认后发送")
                        if(intent.resolveActivity(context.packageManager)==null)vm.showNotice("手机未提供语音识别服务，请启用系统语音服务或使用输入法的语音输入。",true)
                        else {voiceThread=state.selected;try{voice.launch(intent)}catch(e:Exception){voiceThread="";vm.showNotice("无法启动语音识别：${e.message}",true)}}
                    },enabled=state.selected.isNotBlank()&&!state.sending) { RemoteIcon("mic","语音输入") }
                    FilledIconButton(onClick=vm::send,enabled=state.selected.isNotBlank()&&(state.draft.isNotBlank()||state.attachments.isNotEmpty())&&!state.sending&&!state.uploading&&state.pending==null,
                        colors=IconButtonDefaults.filledIconButtonColors(containerColor=MaterialTheme.colorScheme.onSurface,contentColor=MaterialTheme.colorScheme.surface),modifier=Modifier.size(44.dp)) {
                        if(state.sending)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)else RemoteIcon("send","发送")
                    }
                }
            }
        }

    }
    if(showAttachments)ModalBottomSheet(onDismissRequest={showAttachments=false}) {
        Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("添加附件",style=MaterialTheme.typography.titleLarge)
            Text("上传到当前电脑，随下一条消息引用。每条最多 5 个文件，单个 20 MB，总计 40 MB。",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={showAttachments=false;uploadThread=state.selected;filePicker.launch(arrayOf("image/*"))},modifier=Modifier.fillMaxWidth()) { Text("选择图片") }
            TextButton(onClick={showAttachments=false;uploadThread=state.selected;filePicker.launch(arrayOf("*/*"))},modifier=Modifier.fillMaxWidth()) { Text("选择文件") }
        }
    }
    if(showPermissions)ModalBottomSheet(onDismissRequest={showPermissions=false}) {
        Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("会话权限",style=MaterialTheme.typography.titleLarge)
            Text("跟随 Desktop",style=MaterialTheme.typography.titleMedium)
            Text(state.permissionNotice,style=MaterialTheme.typography.bodyMedium)
            Button(onClick={vm.openDesktop();showPermissions=false},enabled=state.connected,modifier=Modifier.fillMaxWidth()) { Text("在电脑打开当前会话") }
            TextButton(onClick={showPermissions=false},modifier=Modifier.fillMaxWidth()) { Text("关闭") }
        }
    }
    if(showUsage)UsagePanel(state,{vm.refreshUsage(true)},{showUsage=false})
    if(showModels)ModelPanel(state,vm::chooseModel,{vm.refreshModels(true)}){showModels=false}
    if(showHistory)ModalBottomSheet(onDismissRequest={showHistory=false}){
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal=16.dp)){
            Text("跳到历史消息",fontWeight=FontWeight.Bold,fontSize=19.sp)
            Text("点选消息，定位到原对话位置",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=8.dp))
            OutlinedTextField(historySearch,{historySearch=it},label={Text("搜索历史消息")},singleLine=true,modifier=Modifier.fillMaxWidth())
            if(state.cursor!=null)TextButton(onClick=vm::loadOlder,enabled=!state.loadingOlder){Text(if(state.loadingOlder)"正在加载历史…"else "加载更早消息")}
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                items(state.items.filter{!it.detail&&it.role in listOf("你","Codex")&&(historySearch.isBlank()||it.text.contains(historySearch,ignoreCase=true))},key={it.key}){entry->
                    Surface(color=if(anchorKey==entry.key)MaterialTheme.colorScheme.secondaryContainer else SurfaceColor,shape=MaterialTheme.shapes.small,
                        modifier=Modifier.fillMaxWidth().clickable{
                            val index=state.items.indexOfFirst{it.key==entry.key}
                            if(index>=0){follow=false;anchorKey=entry.key;showHistory=false;scope.launch{list.scrollToItem(index+1)}}
                        }){
                        Column(Modifier.padding(12.dp)){
                            Text(entry.role,fontSize=11.sp,color=Mint)
                            Text(entry.text.ifBlank{entry.images.firstOrNull()?.name?:entry.files.firstOrNull()?.name?:"附件"}.replace('\n',' ').take(160),maxLines=3,overflow=TextOverflow.Ellipsis,fontSize=13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun MessageCard(item: ChatItem,vm:ChatViewModel) {
    val density = LocalDensity.current
    val scale = LocalAppearance.current.fontSize / 14f
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * scale)) {
        MessageRow(item, vm)
    }
}
@Composable private fun MessageRow(item: ChatItem,vm:ChatViewModel) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val clipboard=androidx.compose.ui.platform.LocalClipboardManager.current
    if(item.role=="状态") { Text(item.text,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=10.sp);return }
    val user=item.role=="你"
    Column(Modifier.fillMaxWidth().testTag("message-row:${item.key}"),horizontalAlignment=if(user)Alignment.End else Alignment.Start) {
        if(item.detail)Text((if(expanded)"▾ "else "▸ ")+item.role,modifier=Modifier.fillMaxWidth().clickable{expanded=!expanded}.padding(vertical=8.dp),color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=13.sp)
        else if(item.role=="Codex · 进度")Text("正在处理",color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=11.sp,modifier=Modifier.padding(bottom=8.dp))
        if(!item.detail||expanded)Surface(color=if(user)MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,shape=RoundedCornerShape(22.dp),modifier=if(user)Modifier.fillMaxWidth(0.92f)else Modifier.fillMaxWidth()) {
            Column(Modifier.padding(if(user)16.dp else 0.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(item.detail)SelectionContainer { Text(item.text,fontFamily=FontFamily.Monospace,fontSize=12.sp,lineHeight=19.sp) }
                else {
                    if(item.text.isNotBlank())SelectionContainer { MarkdownMessage(item.text,vm::imageBitmap) }
                    item.images.forEach { ImagePreview(it,vm::imageBitmap) }
                    item.files.filter{!it.image}.forEach { Text("↗ "+it.name+" · "+((it.size+1023)/1024)+" KB",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    item.delivery?.let { Text(it,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=11.sp) }
                }
            }
        }
        if(!user&&!item.detail&&item.text.isNotBlank())IconButton(onClick={clipboard.setText(androidx.compose.ui.text.AnnotatedString(item.text))},modifier=Modifier.size(36.dp).padding(top=4.dp)) { RemoteIcon("copy","复制消息",18f) }
    }
}

@Composable private fun RemoteIcon(kind: String, label: String, iconSize: Float = 24f) {
    val color=LocalContentColor.current
    Canvas(Modifier.size(iconSize.dp).semantics { contentDescription=label }) {
        val scale=size.width/24f
        fun line(x1:Float,y1:Float,x2:Float,y2:Float)=drawLine(color,Offset(x1*scale,y1*scale),Offset(x2*scale,y2*scale),2*scale,cap=androidx.compose.ui.graphics.StrokeCap.Round)
        fun outline(points: List<Pair<Float,Float>>,close:Boolean=false){val p=Path();points.forEachIndexed { i,(x,y) -> if(i==0)p.moveTo(x*scale,y*scale)else p.lineTo(x*scale,y*scale) };if(close)p.close();drawPath(p,color,style=Stroke(1.8f*scale))}
        when(kind) {
            "plus"->{line(12f,3f,12f,21f);line(3f,12f,21f,12f)}
            "back"->{line(4f,12f,21f,12f);outline(listOf(12f to 4f,4f to 12f,12f to 20f))}
            "send"->{line(12f,20f,12f,4f);outline(listOf(5f to 11f,12f to 4f,19f to 11f))}
            "shield"->{outline(listOf(12f to 2f,21f to 6f,20f to 15f,16f to 20f,12f to 23f,8f to 20f,4f to 15f,3f to 6f),true);outline(listOf(8f to 12f,11f to 15f,16f to 9f))}
            "laptop"->{outline(listOf(5f to 3f,19f to 3f,19f to 17f,5f to 17f),true);outline(listOf(3f to 17f,21f to 17f,23f to 21f,1f to 21f),true)}
            "more"->listOf(5f,12f,19f).forEach { y -> drawCircle(color,1.6f*scale,Offset(12f*scale,y*scale)) }
            "mic"->{drawRoundRect(color,Offset(9f*scale,2f*scale),androidx.compose.ui.geometry.Size(6f*scale,12f*scale),androidx.compose.ui.geometry.CornerRadius(3f*scale),style=Stroke(1.8f*scale));outline(listOf(5f to 10f,5f to 14f,8f to 18f,16f to 18f,19f to 14f,19f to 10f));line(12f,18f,12f,23f)}
            "copy"->{drawRoundRect(color,Offset(3f*scale,7f*scale),androidx.compose.ui.geometry.Size(13f*scale,14f*scale),androidx.compose.ui.geometry.CornerRadius(3f*scale),style=Stroke(1.8f*scale));outline(listOf(8f to 3f,18f to 3f,21f to 6f,21f to 15f))}
        }
    }
}
