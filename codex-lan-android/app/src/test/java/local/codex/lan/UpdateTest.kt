package local.codex.lan

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class UpdateTest {
    private val bytes="signed APK fixture bytes".toByteArray()
    private val sha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun metadata()=JSONObject("""{"tag_name":"v0.4.0","body":"更新说明","assets":[{"name":"codex-lan-android.apk","state":"uploaded","size":${bytes.size},"digest":"sha256:$sha","browser_download_url":"https://github.com/EliotOK/codex-lan/releases/download/v0.4.0/codex-lan-android.apk"}]}""")
    @Test fun comparesNumericVersionsAndRejectsPreviewMetadata(){
        assertTrue(UpdateRelease.compareVersions("0.10.0","0.9.9")>0)
        assertTrue(UpdateRelease.parse(metadata()).newerThan("0.3.0"))
        assertFalse(UpdateRelease.parse(metadata()).newerThan("0.4.0"))
        assertThrows(IllegalArgumentException::class.java){UpdateRelease.parse(metadata().put("prerelease",true))}
        assertThrows(IllegalArgumentException::class.java){UpdateRelease.parse(metadata().put("tag_name","v0.4.0-beta"))}
    }
    @Test fun rejectsMissingChecksumWrongSourceAndOversizedApk(){
        fun changed(key:String,value:Any)=metadata().apply{getJSONArray("assets").getJSONObject(0).put(key,value)}
        assertThrows(IllegalArgumentException::class.java){UpdateRelease.parse(changed("digest",JSONObject.NULL))}
        assertThrows(IllegalArgumentException::class.java){UpdateRelease.parse(changed("browser_download_url","https://example.com/update.apk"))}
        assertThrows(IllegalArgumentException::class.java){UpdateRelease.parse(changed("size",UpdateRelease.MAX_APK_BYTES+1))}
        listOf("http://github.com/apk","https://user@github.com/apk","https://example.com/apk","https://github.com:444/apk").forEach{assertFalse(UpdateRelease.allowedUrl(it))}
        assertTrue(UpdateRelease.allowedUrl("https://release-assets.githubusercontent.com/file"))
    }
    private fun source(body:ByteArray=bytes,code:Int=200,location:String?=null):UpdateTransport {
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            assertNull(request.header("Cookie"));assertNull(request.header("Authorization"));assertNull(request.header("X-CSRF-Token"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body.toResponseBody()).apply{location?.let{header("Location",it)}}.build()
        }.build()
        return UpdateTransport(client)
    }
    @Test fun downloadsVerifiedBytesAndReportsProgress(){
        val folder=kotlin.io.path.createTempDirectory("update-test").toFile();val target=File(folder,"update.apk")
        try {val progress=mutableListOf<Int>();source().download(UpdateRelease.parse(metadata()),target){progress+=it};assertArrayEquals(bytes,target.readBytes());assertEquals(100,progress.last());assertFalse(File(folder,"update.apk.part").exists())}
        finally{target.delete();folder.delete()}
    }
    @Test fun rejectsCorruptOrTruncatedDownloadsAndRemovesPartialFile(){
        val folder=kotlin.io.path.createTempDirectory("update-test").toFile();val target=File(folder,"update.apk")
        try {
            val release=UpdateRelease.parse(metadata())
            assertThrows(IllegalArgumentException::class.java){source().download(release.copy(sha256="0".repeat(64)),target){}}
            assertFalse(target.exists());assertFalse(File(folder,"update.apk.part").exists())
            assertThrows(IllegalArgumentException::class.java){source(bytes.copyOf(bytes.size-1)).download(release,target){}}
            assertFalse(target.exists())
        }finally{target.delete();folder.delete()}
    }
    @Test fun refusesUnsafeRedirectAndReportsRateLimit(){
        val folder=kotlin.io.path.createTempDirectory("update-test").toFile();val target=File(folder,"update.apk")
        try {
            assertThrows(IllegalArgumentException::class.java){source(code=302,location="http://example.com/file").download(UpdateRelease.parse(metadata()),target){}}
            assertThrows(java.io.IOException::class.java){source(code=403).latest()}
        }finally{target.delete();folder.delete()}
    }
}
