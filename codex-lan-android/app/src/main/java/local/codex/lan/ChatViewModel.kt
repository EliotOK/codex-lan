package local.codex.lan

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.net.ssl.SSLException

data class ThreadInfo(val id: String, val title: String, val project: String, val active: Boolean, val projectPath: String = "", val projectId: String? = null, val cwd: String = "")
data class ChatImage(val reference: String, val name: String = "图片")
data class ChatItem(val key: String, val role: String, val text: String, val detail: Boolean = false, val images: List<ChatImage> = emptyList(), val delivery: String? = null, val occurredAt: Long = 0)
data class PendingSend(val thread: String, val prompt: String, val request: String, val choice: ModelChoice = ModelChoice())
data class ChatState(
    val endpoint: String = "https://192.168.1.220:8787", val paired: Boolean = false,
    val connected: Boolean = false, val connecting: Boolean = false, val threads: List<ThreadInfo> = emptyList(),
    val selected: String = "", val title: String = "选择会话", val items: List<ChatItem> = emptyList(),
    val draft: String = "", val sending: Boolean = false, val notice: String = "", val error: Boolean = false,
    val active: Boolean = false, val activityLabel: String = "就绪", val cursor: String? = null, val loadingOlder: Boolean = false,
    val pending: PendingSend? = null, val fingerprint: String = "",
    val usage: UsageInfo? = null, val usageNotice: String = "", val usageRefreshing:Boolean = false, val projectNotice: String = "",
    val models: List<ModelOption> = emptyList(), val modelChoice: ModelChoice = ModelChoice(), val modelNotice: String = ""
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val storage = SecureStore(app)
    private var cert = app.resources.openRawResource(R.raw.desktop_ca).use { it.readBytes() }
    private var client: LanClient? = null
    private var savedCredentials = Credentials()
    private val drafts = linkedMapOf<String, String>()
    private val modelChoices = linkedMapOf<String, ModelChoice>()
    private fun choiceKey(thread: String = state.value.selected) = messageScope() + ":" + thread
    private var lastModelsCheck = 0L
    private val modelsLock = Mutex()
    private val turns = linkedMapOf<String, JSONObject>()
    private val outgoing = OutgoingMessages()
    private fun messageScope() = state.value.endpoint + ":" + LanClient.fingerprint(cert)
    private fun desktopItems() = turns.values.sortedBy { it.optLong("startedAt") }.flatMap { renderTurn(it) }
    private fun showMessages() { change { it.copy(items = outgoing.merge(messageScope(), it.selected, desktopItems())) } }
    private val refreshLock = Mutex()
    private var refreshCount = 0
    private var loadedOlder = false
    private val usageLock = Mutex()
    private var lastUsageCheck = 0L
    private val imageLock = kotlinx.coroutines.sync.Semaphore(1)
    private val previews = object: android.util.LruCache<String,android.graphics.Bitmap>(12*1024*1024){override fun sizeOf(key:String,value:android.graphics.Bitmap)=value.allocationByteCount}
    private val mutable = MutableStateFlow(ChatState())
    val state = mutable.asStateFlow()
    private fun change(transform: (ChatState) -> ChatState) { mutable.value = transform(mutable.value) }
    init {
        try {
            val data = storage.read()
            if (data.has("certificate")) {
                val restored = Base64.decode(data.getString("certificate"), Base64.NO_WRAP)
                LanClient.parseCertificate(restored).checkValidity()
                cert = restored
            }
            savedCredentials = Credentials(data.optString("token"), data.optString("csrf"))
            data.optJSONObject("drafts")?.let { values -> values.keys().forEach { drafts[it] = values.getString(it) } }
            data.optJSONObject("modelChoices")?.let { values -> values.keys().forEach { modelChoices[it] = ModelChoice.parse(values.optJSONObject(it)) } }
            val selected = data.optString("selected")
            val pending = data.optJSONObject("pending")?.let { PendingSend(it.getString("thread"), it.getString("prompt"), it.getString("request"), ModelChoice.parse(it.optJSONObject("choice"))) }
            change { it.copy(endpoint = data.optString("endpoint", it.endpoint), selected = selected,
                paired = savedCredentials.token.isNotBlank(), draft = drafts[selected].orEmpty(), pending = pending,
                notice = if (pending != null) "有一条消息的发送结果待确认。请先查看对应会话。" else "") }
            outgoing.restore(data.optJSONArray("outgoing"))
            change { it.copy(modelChoice=modelChoices[choiceKey()].let { choice -> choice ?: ModelChoice() }) }
            if (pending != null) outgoing.add(pending.request, messageScope(), pending.thread, pending.prompt, emptyList(), "发送结果待核对")
            showMessages()
            if (state.value.paired) client = LanClient(state.value.endpoint, cert, savedCredentials)
        } catch (_: Exception) {
            savedCredentials = Credentials()
            change { it.copy(paired = false, notice = "无法读取保存的连接，请重新配对并核对最近发送的消息。", error = true) }
        }
        change { it.copy(fingerprint = LanClient.fingerprint(cert)) }
    }
    private fun persist() {
        val s = state.value
        val creds = client?.credentials ?: savedCredentials
        val data = JSONObject().put("endpoint", s.endpoint).put("selected", s.selected)
            .put("certificate", Base64.encodeToString(cert, Base64.NO_WRAP))
            .put("token", creds.token).put("csrf", creds.csrf).put("drafts", JSONObject(drafts as Map<*, *>))
            .put("outgoing", outgoing.json())
            .put("modelChoices", JSONObject().also { values -> modelChoices.forEach { (key,choice) -> values.put(key,choice.json()) } })
        s.pending?.let { data.put("pending", JSONObject().put("thread", it.thread).put("prompt", it.prompt).put("request", it.request).put("choice", it.choice.json())) }
        storage.save(data)
    }
    fun setCertificate(bytes: ByteArray) {
        try {
            require(bytes.size <= 16384) { "证书文件过大" }
            LanClient.parseCertificate(bytes).checkValidity()
            cert = bytes; client = null; savedCredentials = Credentials(); previews.evictAll();lastUsageCheck=0L;lastModelsCheck=0L
            turns.clear()
            change { it.copy(paired = false, connected = false, usage=null,usageNotice="",models=emptyList(),modelChoice=ModelChoice(),modelNotice="",fingerprint = LanClient.fingerprint(bytes), notice = "证书已更新，请重新配对。", error = false) }
            showMessages()
            persist()
        } catch (e: Exception) { report(e) }
    }
    fun pair(endpoint: String, code: String) {
        if (state.value.connecting) return
        change { it.copy(connecting = true, notice = "正在连接电脑…", error = false) }
        viewModelScope.launch {
            try {
                val next = LanClient(endpoint, cert)
                withContext(Dispatchers.IO) { next.pair(code) }
                client = next; savedCredentials = next.credentials; previews.evictAll()
                refreshCount = 0; turns.clear(); loadedOlder = false;lastUsageCheck=0L;lastModelsCheck=0L
                change { it.copy(endpoint = next.base, paired = true, connected = true, threads = emptyList(), items = emptyList(), cursor = null,
                    usage=null,usageNotice="",models=emptyList(),modelChoice=modelChoices[choiceKey()] ?: ModelChoice(),modelNotice="",notice = "已配对，同步桌面会话。", error = false) }
                change { it.copy(modelChoice=modelChoices[choiceKey()] ?: ModelChoice()) }
                showMessages()
                persist(); refresh()
            } catch (e: Exception) { report(e) }
            finally { change { it.copy(connecting = false) } }
        }
    }
    suspend fun poll() {
        var pause = 1200L
        while (kotlin.coroutines.coroutineContext.isActive) {
            if (state.value.paired) {
                val ok = refresh()
                pause = if (ok) 1200 else (pause * 2).coerceAtMost(15000)
            } else pause = 1200
            delay(pause)
        }
    }
    private suspend fun refresh(): Boolean = refreshLock.withLock {
        val api = client ?: return@withLock false
        try {
            val list = if (state.value.threads.isEmpty() || refreshCount++ % 15 == 0) withContext(Dispatchers.IO) { api.threads() } else null
            if (api !== client) return@withLock false
            if (list != null) {
                val threads = ProjectGroups.parseThreads(list)
                val projectNotice = list.optString("projectsNotice").ifBlank {
                    if (!list.has("projects") && threads.any { it.projectId != null }) "请更新电脑服务，以显示 Desktop 的项目名称。" else ""
                }
                change { it.copy(threads = threads, projectNotice = projectNotice) }
                if (threads.none { it.id == state.value.selected }) {
                    val preferred = threads.firstOrNull { it.active } ?: threads.firstOrNull()
                    if (preferred != null) select(preferred.id, refreshNow = false)
                    else change { it.copy(selected = "", items = emptyList(), title = "暂无本机会话") }
                }
            }
            val id = state.value.selected
            if (id.isNotEmpty()) {
                val snapshot = withContext(Dispatchers.IO) { api.read(id) }
                if (state.value.selected == id && client === api) accept(snapshot, newest = true)
            } else withContext(Dispatchers.IO) { api.status() }
            if (api !== client) return@withLock false
            change { it.copy(connected = true, notice = if (!it.connected && it.error && it.pending == null) "连接已恢复" else it.notice, error = if (!it.connected && it.pending == null) false else it.error) }
            refreshUsage()
            refreshModels()
            true
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { if (client === api) report(e); false }
    }
    fun select(id: String, refreshNow: Boolean = true) {
        if (id == state.value.selected && turns.isNotEmpty()) return
        val thread = state.value.threads.find { it.id == id } ?: return
        turns.clear()
        loadedOlder = false
        change { it.copy(selected = id, title = thread.title, items = emptyList(), cursor = null, active = thread.active, draft = drafts[id].orEmpty(), modelChoice=modelChoices[choiceKey(id)] ?: ModelChoice()) }
        showMessages()
        try { persist() } catch (e: Exception) { report(e) }
        if (refreshNow) viewModelScope.launch { refresh() }
    }
    fun pauseReads() { client?.cancelReads() }
    fun refreshUsage(force:Boolean=false) {
        val api=client?:return
        if(usageLock.isLocked || (!force&&System.currentTimeMillis()-lastUsageCheck<60000))return
        viewModelScope.launch { usageLock.withLock {
            if(api!==client)return@withLock
            lastUsageCheck=System.currentTimeMillis()
            change{it.copy(usageRefreshing=true)}
            try {
                val usage=withContext(Dispatchers.IO){api.usage(force)}
                if(api===client)change{it.copy(usage=usage,usageNotice="")}
            } catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){if(api===client)change{it.copy(usageNotice=if(e is ApiException&&e.status==404)"请更新电脑服务以读取用量。"else "用量暂不可用，可稍后刷新；已有数据可能过时。")}}
            finally{change{it.copy(usageRefreshing=false)}}
        } }
    }
    suspend fun imageBitmap(image: ChatImage, full: Boolean): android.graphics.Bitmap = withContext(Dispatchers.IO) {
        val api=client?:throw java.io.IOException("请先连接电脑")
        val key=api.base+":"+api.credentials.token+":"+image.reference
        imageLock.acquire()
        try{
            if(!full)previews.get(key)?.let{return@withContext it}
            val bytes=api.imageBytes(image)
            val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
            android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            require(bounds.outWidth>0&&bounds.outHeight>0){"此图片格式暂不支持"}
            val options=android.graphics.BitmapFactory.Options().apply{inSampleSize=ImageSizing.sample(bounds.outWidth,bounds.outHeight,full)}
            val bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)?:throw java.io.IOException("图片无法解码")
            if(!full&&client===api)previews.put(key,bitmap)
            bitmap
        }catch(error:OutOfMemoryError){throw java.io.IOException("图片较大，手机内存不足，请在电脑查看")}
        finally{imageLock.release()}
    }
    fun reconnect(showMessage: Boolean = true) {
        if (!state.value.paired) return
        val old = client ?: return
        try {
            old.cancelReads(); savedCredentials = old.credentials
            client = LanClient(state.value.endpoint, cert, savedCredentials); refreshCount = 0
            if(showMessage)change{it.copy(connected=false,notice="正在重新连接电脑…",error=false)}
            viewModelScope.launch { refresh() }
        } catch(e:Exception) { report(e) }
    }
    fun refreshNow() { reconnect() }
    fun chooseModel(choice: ModelChoice) {
        if (state.value.sending || state.value.selected.isBlank()) return
        if (choice.model != null) {
            val option = state.value.models.firstOrNull { it.id == choice.model } ?: return
            if (choice.thinking != null && choice.thinking !in option.efforts) return
        }
        modelChoices[choiceKey()] = choice
        while(modelChoices.size>64)modelChoices.remove(modelChoices.keys.first())
        change { it.copy(modelChoice=choice) }
        try { persist() } catch(e:Exception) { report(e) }
    }
    fun refreshModels(force: Boolean = false) {
        val api = client ?: return
        if(modelsLock.isLocked || (!force && System.currentTimeMillis()-lastModelsCheck<60000))return
        viewModelScope.launch { modelsLock.withLock {
            lastModelsCheck=System.currentTimeMillis()
            try {
                val options=withContext(Dispatchers.IO) { api.models(force) }
                if(api===client)change { it.copy(models=options,modelNotice=if(options.isEmpty())"桌面未提供可用模型，请使用跟随桌面。"else "") }
            } catch(e:kotlinx.coroutines.CancellationException) { throw e }
            catch(e:Exception) { if(api===client)change { it.copy(modelNotice=if(e is ApiException&&e.status==404)"请更新电脑服务以选择模型。"else "模型列表暂不可用，可稍后刷新。") } }
        } }
    }
    fun showNotice(text: String, error: Boolean = false) { change { it.copy(notice=text,error=error) } }
    fun voiceResult(thread: String, text: String) {
        if(thread.isBlank()||text.isBlank())return
        val combined = listOf(drafts[thread].orEmpty(),text.trim()).filter{it.isNotBlank()}.joinToString("\n")
        if(combined.length>16000){showNotice("语音文字超过消息长度限制，请缩短后再试。",true);return}
        drafts[thread]=combined
        change { it.copy(draft=if(it.selected==thread)combined else it.draft,notice="语音已转成文字，请确认后发送。",error=false) }
        try{persist()}catch(e:Exception){report(e)}
    }
    private fun accept(snapshot: JSONObject, newest: Boolean) {
        val incoming = snapshot.optJSONArray("turns") ?: JSONArray()
        for (i in 0 until incoming.length()) { val t = incoming.getJSONObject(i); turns[t.getString("id")] = t }
        val thread = snapshot.optJSONObject("thread")
        val desktop = desktopItems()
        val reconciled = outgoing.reconcile(messageScope(), state.value.selected, desktop)
        val items = outgoing.merge(messageScope(), state.value.selected, desktop)
        if (!newest) loadedOlder = true
        change { it.copy(items = items, title = thread?.optString("title", it.title) ?: it.title,
            activityLabel = if(newest) ActivityLabels.from(snapshot) else it.activityLabel,
            active = isActiveStatus(thread?.opt("status")), cursor = if (newest && loadedOlder) it.cursor else snapshot.optJSONObject("page")?.optString("nextCursor")?.takeIf { c -> c.isNotBlank() && c != "null" }) }
        if (reconciled) persist()
    }
    fun loadOlder() {
        val s = state.value; val cursor = s.cursor ?: return; val api = client ?: return
        if (s.loadingOlder) return
        change { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            try { val snapshot = withContext(Dispatchers.IO) { api.read(s.selected, cursor) }
                if (state.value.selected == s.selected && client === api) accept(snapshot, newest = false)
            } catch (e: Exception) { report(e) }
            finally { change { it.copy(loadingOlder = false) } }
        }
    }
    fun draft(value: String) {
        if (value.length > 16000) return
        val id = state.value.selected; if (id.isEmpty()) return
        drafts[id] = value
        while (drafts.size > 20) drafts.remove(drafts.keys.first())
        change { it.copy(draft = value) }
        try { persist() } catch (e: Exception) { report(e) }
    }
    fun send() {
        val s = state.value; val api = client ?: return
        if (s.sending || s.selected.isEmpty() || s.draft.isBlank() || s.pending != null) return
        if (s.modelChoice.model != null && s.models.none { it.id==s.modelChoice.model && (s.modelChoice.thinking==null || s.modelChoice.thinking in it.efforts) }) {
            showNotice("所选模型已不可用，请刷新模型列表或选择跟随桌面。",true); return
        }
        val pending = PendingSend(s.selected, s.draft.trim(), UUID.randomUUID().toString(), s.modelChoice)
        try { outgoing.add(pending.request, messageScope(), pending.thread, pending.prompt, desktopItems()) }
        catch (e: Exception) { showNotice(e.message ?: "无法保存消息", true); return }
        change { it.copy(sending = true, pending = pending, notice = "正在提交到桌面会话…", error = false) }
        showMessages()
        viewModelScope.launch {
            try {
                persist()
                val result = withContext(Dispatchers.IO) { api.send(pending.thread, pending.prompt, pending.request, pending.choice) }
                check(result.optString("state") == "sent") { "发送结果待确认" }
                outgoing.status(pending.request, "已提交 · 等待桌面同步")
                drafts.remove(pending.thread)
                change { it.copy(pending = null, draft = if (it.selected == pending.thread) "" else it.draft, notice = "已提交到桌面会话", error = false) }
                showMessages()
                persist(); refresh()
            } catch (e: Exception) {
                if (e is ApiException && e.status in listOf(400, 401, 403, 404, 429) && e.receiptState == null) {
                    outgoing.remove(pending.request)
                    change { it.copy(pending = null) }
                } else outgoing.status(pending.request, "发送结果待核对")
                showMessages()
                report(e, sending = true)
                try { persist() } catch (_: Exception) { }
            } finally { change { it.copy(sending = false) } }
        }
    }
    fun acknowledgePending() {
        if (state.value.sending) return
        state.value.pending?.let { outgoing.status(it.request, "本地记录 · 已手动核对") }
        change { it.copy(pending = null, notice = "已确认最近发送记录，可以继续输入。", error = false) }
        showMessages()
        try { persist() } catch (e: Exception) { report(e) }
    }
    fun disconnect() {
        if (state.value.sending) return
        val previous = client
        client = null; savedCredentials = Credentials(); turns.clear(); previews.evictAll();lastUsageCheck=0L;lastModelsCheck=0L
        change { it.copy(paired = false, connected = false, usage=null,usageNotice="",models=emptyList(),modelChoice=ModelChoice(),modelNotice="",threads = emptyList(), items = emptyList(), notice = "已断开，请重新配对。", error = false) }
        try { persist() } catch (e: Exception) { report(e) }
        viewModelScope.launch { try { withContext(Dispatchers.IO) { previous?.logout() } } catch (_: Exception) { } }
    }
    fun report(error: Exception, sending: Boolean = false) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        if (error is ApiException && error.status == 401) {
            client = null; savedCredentials = Credentials()
            change { it.copy(paired = false) }
            try { persist() } catch (_: Exception) { }
        }
        val text = if (error is SSLException) "证书验证失败。请检查电脑地址及证书指纹，需要时导入新的电脑证书。"
            else if(error is java.io.IOException) "暂时无法连接电脑，将自动重试。请确认同一 Wi-Fi，或点击右上角重新连接。"
            else error.message ?: "连接失败，请检查电脑服务和同一 Wi-Fi。"
        change { it.copy(connected = if (error is ApiException && error.status in listOf(400, 403, 404, 429)) it.connected else false,
            notice = text + if (sending && it.pending != null) "\n发送结果待确认，请先核对会话中的消息。" else "", error = true) }
    }
    companion object {
        fun isActiveStatus(value: Any?): Boolean = (if (value is JSONObject) value.optString("type") else value?.toString()) in listOf("active", "inProgress", "running")
        fun renderTurn(turn: JSONObject): List<ChatItem> {
            val result = mutableListOf<ChatItem>()
            val items = turn.optJSONArray("items") ?: JSONArray()
            val records = mutableListOf<Pair<String, String>>()
            fun flushRecords() {
                if (records.isEmpty()) return
                val text = records.joinToString("\n\n") { it.second }
                val display = if (text.length > 60000) text.take(60000) + "\n[记录较长，请在电脑查看完整输出]" else text
                result += ChatItem(records.first().first, "执行记录 · ${records.size} 项", display, true)
                records.clear()
            }
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i); val type = item.optString("type")
                val key = turn.optString("id") + ":" + item.optString("id", i.toString())
                when (type) {
                    "functionCallOutput" -> {
                        MessageContent.forwarded(item)?.let { text ->
                            flushRecords()
                            result += ChatItem(key, "你", text)
                        }
                    }
                    "userMessage" -> {
                        flushRecords()
                        val content = item.optJSONArray("content") ?: JSONArray()
                        val attachments = (0 until content.length()).mapNotNull { j -> val p=content.getJSONObject(j)
                            val ref=p.optString("imageId").takeIf{it.matches(Regex("[a-f0-9]{64}"))}?.let{"/api/images/$it"}
                                ?: (p.optString("url").ifBlank{p.optString("imageUrl")}).takeIf{it.startsWith("https://")}
                            if(p.optString("type") in listOf("localImage","image","inputImage") && ref!=null)ChatImage(ref,p.optString("name","图片"))else null
                        }
                        val text = (0 until content.length()).map { j -> val p = content.getJSONObject(j); when (p.optString("type")) { "text" -> p.optString("text"); "image","localImage","inputImage" -> if(p.has("imageId")||p.optString("url").startsWith("https://"))"" else "[图片暂不可用]"; else -> "[附件]" } }.filter{it.isNotBlank()}.joinToString("\n")
                        val visible = MessageContent.user(text)
                        result += ChatItem(key, "你", visible.text.ifBlank { if(attachments.isEmpty())"[附件]"else "" }, images=attachments)
                        visible.context?.takeIf{it.isNotBlank()}?.let { result += ChatItem(key+":context", "会话上下文", it, true) }
                    }
                    "agentMessage" -> {
                        flushRecords()
                        result += ChatItem(key, if (item.optString("phase") == "commentary") "Codex · 进度" else "Codex", item.optString("text"))
                    }
                    "commandExecution", "fileChange", "mcpToolCall", "dynamicToolCall", "webSearch" -> {
                        val label = when (type) { "commandExecution" -> "执行命令"; "fileChange" -> "修改文件"; "webSearch" -> "搜索资料"; else -> "调用工具" }
                        val status = when (item.optString("status")) { "completed" -> "已完成"; "failed" -> "失败"; "interrupted" -> "已中断"; else -> "执行中" }
                        records += key to (label + " · " + status + "\n" + item.toString(2).take(20000))
                    }
                }
            }
            flushRecords()
            when (turn.optString("status")) {
                "completed" -> result += ChatItem(turn.optString("id") + ":end", "状态", "本轮完成")
                "interrupted" -> result += ChatItem(turn.optString("id") + ":end", "状态", "本轮已中断")
                "failed" -> result += ChatItem(turn.optString("id") + ":end", "状态", turn.optJSONObject("error")?.optString("message", "本轮执行失败") ?: "本轮执行失败")
            }
            return result.map { it.copy(occurredAt = turn.optLong("startedAt")) }
        }
    }
}
