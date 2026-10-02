package local.codex.lan

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

interface UpdateSource {
    fun latest():UpdateRelease
    fun download(release:UpdateRelease,target:File,progress:(Int)->Unit)
    fun cancel()
}
class UpdateTransport(private val http:OkHttpClient=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(180,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()):UpdateSource {
    @Volatile private var active:Call?=null
    override fun cancel(){active?.cancel()}
    private fun response(initial:String):Response {
        var url=initial
        repeat(6){
            require(UpdateRelease.allowedUrl(url)){"更新下载地址不受信任"}
            val call=http.newCall(Request.Builder().url(url).header("User-Agent","Codex-LAN-Android").header("Accept","application/vnd.github+json").build())
            active=call
            val response=call.execute()
            if(response.code in listOf(301,302,303,307,308)){
                val next=response.header("Location")?.let{response.request.url.resolve(it)?.toString()};response.close()
                url=next?:throw IOException("更新地址重定向无效")
            }else{
                if(!response.isSuccessful){val code=response.code;response.close();throw IOException(if(code==403||code==429)"GitHub 暂时限制检查频率，请稍后再试"else "更新服务器返回 HTTP $code")}
                return response
            }
        }
        throw IOException("更新下载重定向次数过多")
    }
    override fun latest():UpdateRelease=response("https://api.github.com/repos/EliotOK/codex-lan/releases/latest").use { response ->
        val input=response.body?.byteStream()?:throw IOException("更新信息为空")
        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){val count=input.read(buffer);if(count<0)break;require(output.size()+count<=1024*1024){"更新信息过大"};output.write(buffer,0,count)}
        val bytes=output.toByteArray()
        UpdateRelease.parse(JSONObject(String(bytes,Charsets.UTF_8)))
    }
    override fun download(release:UpdateRelease,target:File,progress:(Int)->Unit) {
        val partial=File(target.parentFile,target.name+".part")
        target.parentFile?.mkdirs();partial.delete();target.delete()
        try {
            response(release.apkUrl).use { response ->
                val body=response.body?:throw IOException("更新文件为空")
                require(body.contentLength()==-1L||body.contentLength()==release.size){"更新文件大小不匹配"}
                val digest=MessageDigest.getInstance("SHA-256");var total=0L
                body.byteStream().use { input -> partial.outputStream().use { output ->
                    val buffer=ByteArray(65536);var last=-1
                    while(true){val count=input.read(buffer);if(count<0)break;total+=count
                        require(total<=release.size&&total<=UpdateRelease.MAX_APK_BYTES){"更新文件超过声明大小"}
                        digest.update(buffer,0,count);output.write(buffer,0,count)
                        val value=(total*100/release.size).toInt();if(value!=last){progress(value);last=value}
                    }
                }}
                require(total==release.size){"下载不完整，请重试"}
                require(digest.digest().joinToString(""){"%02x".format(it)}==release.sha256){"更新文件校验失败，请重新下载"}
                check(partial.renameTo(target)){"无法保存更新文件"}
            }
        }finally{partial.delete();active=null}
    }
}
