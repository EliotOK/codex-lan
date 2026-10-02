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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

private val Mint = Color(0xFFAEF3CC)
private val Background = Color(0xFF111614)
private val SurfaceColor = Color(0xFF1D2521)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            val vm: ChatViewModel = viewModel()
            DisposableEffect(vm) {
                val observer = LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP)vm.pauseReads() }
                lifecycle.addObserver(observer)
                val connectivity = getSystemService(ConnectivityManager::class.java)
                val callback = object: ConnectivityManager.NetworkCallback() { override fun onAvailable(network:Network) { lifecycleScope.launch { vm.reconnect(false) } } }
                connectivity.registerDefaultNetworkCallback(callback)
                onDispose { lifecycle.removeObserver(observer);connectivity.unregisterNetworkCallback(callback);vm.pauseReads() }
            }
            LaunchedEffect(vm) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.poll() } }
            MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Background, background = Background,
                surface = SurfaceColor, onSurface = Color(0xFFE5EEE8), surfaceVariant = Color(0xFF29332D))) {
                val state by vm.state.collectAsStateWithLifecycle()
                Surface(Modifier.fillMaxSize(), color = Background, contentColor = MaterialTheme.colorScheme.onBackground) { App(state, vm) }
            }
        }
    }
}

@Composable private fun App(state: ChatState, vm: ChatViewModel) {
    val updater:UpdateViewModel=viewModel()
    var showUpdate by rememberSaveable{mutableStateOf(false)}
    fun openUpdate(){showUpdate=true;if(!updater.state.value.checked)updater.check()}
    var showThreads by rememberSaveable { mutableStateOf(false) }
    var showFingerprint by remember { mutableStateOf(false) }
    var acknowledge by remember { mutableStateOf(false) }
    var disconnect by remember { mutableStateOf(false) }
    BackHandler(state.paired && showThreads) { showThreads = false }
    Column(Modifier.fillMaxSize().background(Background).safeDrawingPadding().imePadding()) {
        if (!state.paired) Box(Modifier.fillMaxSize()){
            PairScreen(state, vm)
            TextButton(onClick=::openUpdate,modifier=Modifier.align(Alignment.TopEnd).padding(8.dp)){Text("检查更新")}
        }
        else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showThreads = !showThreads }) { Text(if (showThreads) "返回" else "会话") }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(if (showThreads) "桌面会话" else state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(if (state.connected) "● 已连接 · 同一个会话" else "○ 连接中断 · 自动重连", fontSize = 11.sp,
                        color = if (state.connected) Mint else MaterialTheme.colorScheme.error)
                }
                var menu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menu = true }) { Text("•••") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("立即刷新") }, onClick = { menu = false; vm.refreshNow() })
                        DropdownMenuItem(text = { Text("重新连接") }, onClick = { menu = false; vm.reconnect() })
                        DropdownMenuItem(text = { Text("连接和证书") }, onClick = { menu = false; showFingerprint = true })
                        DropdownMenuItem(text = { Text("检查更新") }, onClick = { menu=false;openUpdate() })
                        DropdownMenuItem(text = { Text("断开配对") }, enabled = !state.sending, onClick = { menu = false; disconnect = true })
                    }
                }
            }
            HorizontalDivider(color = Color(0xFF2B3730))
            if (state.notice.isNotBlank()) Notice(state.notice, state.error)
            if (showThreads) ThreadList(state, onSelect = { vm.select(it); showThreads = false }, modifier = Modifier.weight(1f))
            else Conversation(state, vm, Modifier.weight(1f), acknowledge = { acknowledge = true })
        }
    }
    if(showUpdate)UpdatePanel(updater){showUpdate=false}
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
        Text("连接同一 Wi-Fi，在电脑连接面板查看地址和 8 位配对码。", color = Color(0xFFA3B1A8), lineHeight = 23.sp)
        OutlinedTextField(address, { address = it }, label = { Text("电脑地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            enabled = !state.connecting, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) }, label = { Text("8 位配对码") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            enabled = !state.connecting, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        Button(onClick = { vm.pair(address, code); code = "" }, enabled = code.length == 8 && !state.connecting, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            if (state.connecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Background) else Text("连接电脑", fontWeight = FontWeight.SemiBold)
        }
        if (state.notice.isNotBlank()) Notice(state.notice, state.error)
        Text("此安装包已包含当前电脑的公开证书。", color = Color(0xFFA3B1A8), fontSize = 12.sp)
        SelectionContainer { Text("SHA-256\n${state.fingerprint}", color = Color(0xFF7F9387), fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
        TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.connecting) { Text("导入其他电脑证书 (.pem)") }
    }
    candidate?.let { bytes -> AlertDialog(onDismissRequest = { candidate = null }, title = { Text("信任电脑证书") }, text = {
        SelectionContainer { Text("请与电脑上的证书 SHA-256 核对：\n\n${LanClient.fingerprint(bytes)}\n\n证书仅用于本应用连接电脑。", fontSize = 12.sp) }
    }, confirmButton = { TextButton(onClick = { candidate = null; vm.setCertificate(bytes) }) { Text("指纹一致，导入") } },
        dismissButton = { TextButton(onClick = { candidate = null }) { Text("取消") } }) }
}

@Composable private fun Notice(text: String, error: Boolean) {
    Text(text, Modifier.fillMaxWidth().background(if (error) Color(0xFF352521) else Color(0xFF1D2B23)).padding(12.dp),
        color = if (error) Color(0xFFFFB9A7) else Color(0xFFBCD9C7), fontSize = 12.sp, lineHeight = 18.sp)
}
@Composable private fun ThreadList(state: ChatState, onSelect: (String) -> Unit, modifier: Modifier) {
    var search by rememberSaveable { mutableStateOf("") }
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val groups = remember(state.threads, search) { ProjectGroups.from(state.threads, search) }
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(search, { search = it }, label = { Text("搜索会话") }, placeholder = { Text("会话名称、项目或路径") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(16.dp))
        if (state.projectNotice.isNotBlank()) Text(state.projectNotice, Modifier.padding(horizontal = 16.dp), color = Color(0xFF91A799), fontSize = 11.sp)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.threads.isEmpty()) item { Text("正在读取本机会话…", Modifier.padding(12.dp), color = Color(0xFFA3B1A8)) }
            else if (groups.isEmpty()) item { Text("没有匹配的会话", Modifier.padding(12.dp), color = Color(0xFFA3B1A8)) }
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
                            Text("${group.threads.size} 个会话", color = Color(0xFF91A799), fontSize = 11.sp)
                        }
                        if (group.path.isNotBlank()) Text(group.path, color = Color(0xFF91A799), fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 20.dp))
                    }
                }
                if (expanded) items(group.threads, key = { it.id }) { thread ->
                    Surface(shape = MaterialTheme.shapes.medium, color = if (thread.id == state.selected) Color(0xFF283C30) else SurfaceColor,
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp).clickable { onSelect(thread.id) }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(thread.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text(if (thread.active) "● 正在执行" else if (thread.id == state.selected) "当前会话" else "点击继续",
                                color = Color(0xFF91A799), fontSize = 12.sp)
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
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by rememberSaveable(state.selected) { mutableStateOf(true) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showUsage by rememberSaveable { mutableStateOf(false) }
    var historySearch by rememberSaveable(state.selected) { mutableStateOf("") }
    var anchorKey by rememberSaveable(state.selected) { mutableStateOf("") }
    val nearBottom by remember { derivedStateOf { !list.canScrollForward } }
    suspend fun latest() {
        if (state.items.isEmpty()) return
        list.scrollToItem(state.items.size)
        withFrameNanos { }
        val last = list.layoutInfo.visibleItemsInfo.lastOrNull()
        if (last != null) list.scrollBy((last.offset + last.size - list.layoutInfo.viewportEndOffset).coerceAtLeast(0).toFloat())
    }
    LaunchedEffect(state.selected) { follow = true }
    LaunchedEffect(state.items) {
        if (follow && state.items.isNotEmpty()) latest()
    }
    LaunchedEffect(list.isScrollInProgress) { if (!list.isScrollInProgress) follow = nearBottom }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
            if(state.connected&&state.active)CircularProgressIndicator(Modifier.size(12.dp),strokeWidth=1.5.dp)
            Text(if(!state.connected)"等待重连" else if(state.sending)"正在提交消息" else state.activityLabel,
                Modifier.weight(1f).padding(start=8.dp),fontSize=11.sp,color=Mint)
            TextButton(onClick={showHistory=true},enabled=state.items.isNotEmpty()){Text("历史位置")}
            TextButton(onClick={showUsage=true;vm.refreshUsage()}){Text("用量")}
        }
        Text(state.usage?.summary?:if(state.usageRefreshing)"正在读取用量…"else "用量暂不可用 · 点击用量查看",
            Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=4.dp),fontSize=10.sp,color=Color(0xFF91A799),maxLines=2)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item(key = "history") { if (state.cursor != null) TextButton(onClick = vm::loadOlder, enabled = !state.loadingOlder) { Text(if (state.loadingOlder) "正在读取…" else "查看更早消息") } }
                items(state.items, key = { it.key }) { message -> MessageCard(message,vm) }
                if (state.items.isEmpty()) item { Text(if (state.selected.isBlank()) "从会话列表选择聊天" else "正在同步桌面聊天…", color = Color(0xFF91A799), modifier = Modifier.padding(vertical = 32.dp)) }
            }
            if(state.items.isNotEmpty())FilledTonalButton(onClick = { follow = true;anchorKey="";scope.launch { latest() } }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) { Text("↓ 最底部") }
        }
        if (state.pending != null) {
            Row(Modifier.fillMaxWidth().background(Color(0xFF352521)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("发送记录待核对", modifier = Modifier.weight(1f).padding(start = 8.dp), color = Color(0xFFFFB9A7), fontSize = 12.sp)
                TextButton(onClick = { vm.select(state.pending.thread) }) { Text("查看会话") }
                TextButton(onClick = acknowledge, enabled = !state.sending) { Text("核对完成") }
            }
        }
        HorizontalDivider(color = Color(0xFF2B3730))
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={
                val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN").putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1).putExtra(RecognizerIntent.EXTRA_PROMPT,"说话后生成文字，请确认后发送")
                if(intent.resolveActivity(context.packageManager)==null)vm.showNotice("手机未提供语音识别服务，请启用系统语音服务或使用输入法的语音输入。",true)
                else {voiceThread=state.selected;try{voice.launch(intent)}catch(e:Exception){voiceThread="";vm.showNotice("无法启动语音识别：${e.message}",true)}}
            },enabled=state.selected.isNotBlank()&&!state.sending){Text("语音输入")}
            Text("识别后确认发送 · 回复为文字",fontSize=10.sp,color=Color(0xFF91A799))
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(state.draft, vm::draft, placeholder = { Text("继续这个会话…") }, modifier = Modifier.weight(1f), maxLines = 5,
                enabled = state.selected.isNotBlank() && !state.sending)
            Button(onClick = vm::send, enabled = state.selected.isNotBlank() && state.draft.isNotBlank() && !state.sending && state.pending == null) {
                if (state.sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Background) else Text("发送")
            }
        }
        Text(if (state.active) "正在执行 · 消息提交到同一个桌面会话" else "前台自动同步 · 与电脑共享会话", color = Color(0xFF91A799), fontSize = 10.sp,
            modifier = Modifier.padding(start = 20.dp, bottom = 10.dp))
    }
    if(showUsage)UsagePanel(state,{vm.refreshUsage(true)},{showUsage=false})
    if(showHistory)ModalBottomSheet(onDismissRequest={showHistory=false}){
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal=16.dp)){
            Text("跳到历史消息",fontWeight=FontWeight.Bold,fontSize=19.sp)
            Text("点选消息，定位到原对话位置",fontSize=12.sp,color=Color(0xFF91A799),modifier=Modifier.padding(vertical=8.dp))
            OutlinedTextField(historySearch,{historySearch=it},label={Text("搜索历史消息")},singleLine=true,modifier=Modifier.fillMaxWidth())
            if(state.cursor!=null)TextButton(onClick=vm::loadOlder,enabled=!state.loadingOlder){Text(if(state.loadingOlder)"正在加载历史…"else "加载更早消息")}
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                items(state.items.filter{!it.detail&&it.role in listOf("你","Codex")&&(historySearch.isBlank()||it.text.contains(historySearch,ignoreCase=true))},key={it.key}){entry->
                    Surface(color=if(anchorKey==entry.key)Color(0xFF283C30)else SurfaceColor,shape=MaterialTheme.shapes.small,
                        modifier=Modifier.fillMaxWidth().clickable{
                            val index=state.items.indexOfFirst{it.key==entry.key}
                            if(index>=0){follow=false;anchorKey=entry.key;showHistory=false;scope.launch{list.scrollToItem(index+1)}}
                        }){
                        Column(Modifier.padding(12.dp)){
                            Text(entry.role,fontSize=11.sp,color=Mint)
                            Text(entry.text.ifBlank{entry.images.firstOrNull()?.name?:"图片"}.replace('\n',' ').take(160),maxLines=3,overflow=TextOverflow.Ellipsis,fontSize=13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun MessageCard(item: ChatItem,vm:ChatViewModel) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    if (item.role == "状态") { Text(item.text, color = Color(0xFF72897A), fontSize = 11.sp); return }
    val user = item.role == "你"
    Surface(color = if (user) Color(0xFF24392B) else if (item.detail) Color(0xFF19201C) else SurfaceColor,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(if (item.detail) 10.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text((if (item.detail) (if (expanded) "▾ " else "▸ ") else "") + item.role, color = Mint, fontSize = 11.sp,
                modifier = if (item.detail) Modifier.fillMaxWidth().clickable { expanded = !expanded } else Modifier)
            if (!item.detail || expanded) SelectionContainer {
                if (item.detail) Text(item.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                else Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                    MarkdownMessage(item.text,vm::imageBitmap)
                    item.images.forEach{ImagePreview(it,vm::imageBitmap)}
                    item.delivery?.let { Text(it, color = Color(0xFF91A799), fontSize = 11.sp) }
                }
            }
        }
    }
}
