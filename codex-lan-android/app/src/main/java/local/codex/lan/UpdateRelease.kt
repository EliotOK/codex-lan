package local.codex.lan

import org.json.JSONObject
import java.net.URI

data class UpdateRelease(val version:String,val notes:String,val apkUrl:String,val size:Long,val sha256:String) {
    fun newerThan(installed:String)=compareVersions(version,installed)>0
    companion object {
        const val MAX_APK_BYTES=64L*1024*1024
        const val REPO="https://github.com/EliotOK/codex-lan"
        fun compareVersions(first:String,second:String):Int {
            fun parts(value:String):List<Long> {
                require(value.matches(Regex("\\d{1,9}\\.\\d{1,9}\\.\\d{1,9}"))){"版本号格式不支持"}
                return value.split('.').map(String::toLong)
            }
            val a=parts(first);val b=parts(second)
            for(i in 0..2)if(a[i]!=b[i])return a[i].compareTo(b[i])
            return 0
        }
        fun parse(data:JSONObject):UpdateRelease {
            require(!data.optBoolean("draft")&&!data.optBoolean("prerelease")){"更新尚未正式发布"}
            val tag=data.getString("tag_name");val version=tag.removePrefix("v")
            compareVersions(version,version)
            val assets=data.getJSONArray("assets")
            val asset=(0 until assets.length()).map{assets.getJSONObject(it)}.singleOrNull{it.optString("name")=="codex-lan-android.apk"&&it.optString("state")=="uploaded"}?:error("新版尚未提供 APK，请稍后检查")
            val size=asset.getLong("size");require(size in 1..MAX_APK_BYTES){"更新文件大小无效"}
            val url=asset.getString("browser_download_url")
            require(url=="$REPO/releases/download/$tag/codex-lan-android.apk"){"更新来源不匹配"}
            val digest=asset.optString("digest").removePrefix("sha256:")
            require(digest.matches(Regex("[a-f0-9]{64}"))){"更新缺少有效 SHA-256 校验值，请稍后重试"}
            return UpdateRelease(version,data.optString("body").take(12000),url,size,digest)
        }
        fun allowedUrl(value:String):Boolean=runCatching {
            val uri=URI(value)
            uri.scheme=="https"&&uri.userInfo==null&&uri.port in listOf(-1,443)&&uri.host in setOf("api.github.com","github.com","release-assets.githubusercontent.com","objects.githubusercontent.com")
        }.getOrDefault(false)
    }
}
