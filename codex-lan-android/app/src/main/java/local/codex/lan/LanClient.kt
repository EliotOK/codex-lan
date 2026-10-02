package local.codex.lan

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

data class Credentials(val token: String = "", val csrf: String = "")
class ApiException(val status: Int, val receiptState: String?, message: String) : Exception(message)

/** A single HTTPS origin, an app-local trust store and explicit session credentials. */
class LanClient(endpoint: String, certificate: ByteArray, initial: Credentials = Credentials()) {
    val base = normalizeEndpoint(endpoint)
    @Volatile var credentials = initial
        private set
    private val http: OkHttpClient
    private val reader: OkHttpClient
    init {
        val cert = parseCertificate(certificate)
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("desktop", cert) }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        val trust = managers.trustManagers.filterIsInstance<X509TrustManager>().single()
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        http = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(70, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build()
        reader = http.newBuilder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS)
            .callTimeout(25,TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    }
    fun cancelReads() {
        val calls = (reader.dispatcher.runningCalls()+reader.dispatcher.queuedCalls()).filter { it.request().method=="GET" }
        cleanup.execute { calls.forEach { it.cancel() }; reader.connectionPool.evictAll() }
    }
    fun pair(code: String): Credentials {
        require(Regex("[0-9]{8}").matches(code)) { "请输入 8 位配对码" }
        val (data, cookie) = execute("/api/pair", JSONObject().put("code", code), authenticated = false)
        val token = Regex("(?:^|;\\s*)codex_lan=([a-f0-9]{64})(?:;|$)").find(cookie.orEmpty())?.groupValues?.get(1)
            ?: throw IllegalStateException("电脑未返回有效会话")
        val csrf = data.getString("csrf")
        credentials = Credentials(token, csrf)
        return credentials
    }
    fun session(): JSONObject = execute("/api/session").first.also {
        credentials = credentials.copy(csrf = it.getString("csrf"))
    }
    fun status() = execute("/api/status").first
    fun threads() = execute("/api/threads").first
    fun usage(force:Boolean=false) = UsageInfo.parse(execute("/api/usage"+if(force)"?refresh=1"else "").first)
    fun read(id: String, cursor: String? = null): JSONObject {
        require(Regex("[a-fA-F0-9]{8}-(?:[a-fA-F0-9]{4}-){3}[a-fA-F0-9]{12}").matches(id)) { "会话编号无效" }
        val query = cursor?.let { "?cursor=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
        return execute("/api/threads/$id$query").first
    }
    fun send(id: String, prompt: String, requestId: String): JSONObject {
        require(prompt.isNotBlank() && prompt.length <= 16000) { "消息需要在 1 至 16000 字之间" }
        require(Regex("[a-fA-F0-9]{8}-(?:[a-fA-F0-9]{4}-){3}[a-fA-F0-9]{12}").matches(id))
        return execute("/api/threads/$id/messages", JSONObject().put("prompt", prompt).put("requestId", requestId)).first
    }
    fun logout() = execute("/api/logout", JSONObject()).first
    fun imageBytes(image: ChatImage): ByteArray {
        val local = image.reference.startsWith("/api/images/")
        val request = Request.Builder()
        val transport: OkHttpClient
        if(local){
            require(Regex("/api/images/[a-f0-9]{64}").matches(image.reference)){"图片地址无效"}
            require(Regex("[a-f0-9]{64}").matches(credentials.token)){"请先配对电脑"}
            request.url(base+image.reference).header("Cookie","codex_lan=${credentials.token}")
            transport=reader
        }else{
            val uri=URI(image.reference)
            require(uri.scheme=="https"&&uri.userInfo==null&&!uri.host.isNullOrBlank()){ "图片暂不支持此地址" }
            request.url(image.reference);transport=publicImages
        }
        transport.newCall(request.build()).execute().use { response ->
            if(!response.isSuccessful){
                val message=if(local)try{JSONObject(response.body?.string().orEmpty()).optString("error","图片读取失败")}catch(_:Exception){"图片读取失败"}else "图片服务器返回 HTTP ${response.code}"
                throw java.io.IOException(message)
            }
            val body=response.body?:throw java.io.IOException("图片内容为空")
            require(body.contentLength()<=MAX_IMAGE_BYTES){"图片超过 20 MB，请在电脑查看"}
            val output=java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val chunk=ByteArray(8192)
                while(true){val size=input.read(chunk);if(size<0)break;require(output.size()+size<=MAX_IMAGE_BYTES){"图片超过 20 MB，请在电脑查看"};output.write(chunk,0,size)}
            }
            return output.toByteArray()
        }
    }
    private fun execute(path: String, body: JSONObject? = null, authenticated: Boolean = true): Pair<JSONObject, String?> {
        val current = credentials
        val request = Request.Builder().url(base + path).header("Accept", "application/json")
        if (authenticated) {
            require(Regex("[a-f0-9]{64}").matches(current.token)) { "请先配对电脑" }
            request.header("Cookie", "codex_lan=${current.token}")
            if (body != null) request.header("X-CSRF-Token", current.csrf)
        }
        if (body != null) request.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        (if(body==null)reader else http).newCall(request.build()).execute().use { response ->
            val data = try { JSONObject(response.body?.string().orEmpty()) }
            catch (_: Exception) { throw ApiException(response.code, null, "电脑返回了无效响应（${response.code}）") }
            if (!response.isSuccessful) throw ApiException(response.code, data.optString("state").ifBlank { null }, data.optString("error", "连接失败"))
            return data to response.header("Set-Cookie")
        }
    }
    companion object {
        const val MAX_IMAGE_BYTES=20*1024*1024
        private val publicImages=OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS)
            .callTimeout(30,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
        private val cleanup = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task,"lan-connection-cleanup").apply { isDaemon=true }
        }
        fun normalizeEndpoint(value: String): String {
            val uri = try { URI(value.trim()) } catch (_: Exception) { throw IllegalArgumentException("地址格式无效") }
            require(uri.scheme == "https" && uri.userInfo == null && uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")) { "请输入 HTTPS 电脑地址，例如 https://192.168.1.220:8787" }
            val parts = uri.host?.split('.')?.map { it.toIntOrNull() }
            require(parts != null && parts.size == 4 && parts.all { it != null && it in 0..255 }) { "请输入电脑的局域网 IPv4 地址" }
            require(parts[0] == 10 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1]!! in 16..31) || parts == listOf(127, 0, 0, 1)) { "仅支持局域网地址或本机隧道" }
            require(uri.port == -1 || uri.port in 1..65535) { "端口无效" }
            return "https://${uri.host}" + if (uri.port == -1) "" else ":${uri.port}"
        }
        fun parseCertificate(bytes: ByteArray): X509Certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(bytes.inputStream()) as X509Certificate
        fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(parseCertificate(bytes).encoded).joinToString(":") { "%02X".format(it) }
    }
}
