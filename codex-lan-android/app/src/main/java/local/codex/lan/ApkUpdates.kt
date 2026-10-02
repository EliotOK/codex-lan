package local.codex.lan

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

object ApkUpdates {
    @Suppress("DEPRECATION") private val flags get()=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION") fun installed(context:Context):PackageInfo=context.packageManager.getPackageInfo(context.packageName,flags)
    @Suppress("DEPRECATION") private fun code(info:PackageInfo)=if(Build.VERSION.SDK_INT>=28)info.longVersionCode else info.versionCode.toLong()
    @Suppress("DEPRECATION") private fun signatures(info:PackageInfo):Set<String> {
        val signatures=if(Build.VERSION.SDK_INT>=28)info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map{MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString(""){byte->"%02x".format(byte)}}.toSet()
    }
    @Suppress("DEPRECATION") fun verify(context:Context,file:File,release:UpdateRelease) {
        val candidate=context.packageManager.getPackageArchiveInfo(file.absolutePath,flags)?:error("下载的文件不是有效 APK")
        val current=installed(context)
        require(candidate.packageName==context.packageName){"更新应用包名不匹配"}
        require(candidate.versionName==release.version&&code(candidate)>code(current)){"更新版本不匹配或不是新版本"}
        val signer=signatures(current)
        require(signer.isNotEmpty()&&signatures(candidate)==signer){"更新签名与已安装应用不一致"}
    }
    fun installIntent(context:Context,file:File):Intent {
        val uri=FileProvider.getUriForFile(context,context.packageName+".updates",file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
