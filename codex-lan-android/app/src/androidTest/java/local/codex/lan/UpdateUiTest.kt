package local.codex.lan

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdateUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun readsAndDownloadsPublicGitHubRelease(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=UpdateTransport();val release=source.latest()
        val target=File(context.cacheDir,"updates/github-read-test.apk")
        try {
            var progress=0;source.download(release,target){progress=it}
            assertEquals(release.size,target.length());assertEquals(100,progress)
            val archive=context.packageManager.getPackageArchiveInfo(target.absolutePath,0)
            assertEquals(context.packageName,archive?.packageName)
            assertEquals(release.version,archive?.versionName)
        }finally{target.delete();source.cancel()}
    }
    @Test fun validatesSignedUpgradeAndHandsItToAndroidInstaller(){
        val instrumentation=InstrumentationRegistry.getInstrumentation();val app=instrumentation.targetContext.applicationContext as android.app.Application
        val path=requireNotNull(InstrumentationRegistry.getArguments().getString("updateApkPath")){"Pass a signed newer APK for installer integration"}
        val candidate=File(path);val hash=java.security.MessageDigest.getInstance("SHA-256").digest(candidate.readBytes()).joinToString(""){"%02x".format(it)}
        val candidateVersion=requireNotNull(app.packageManager.getPackageArchiveInfo(candidate.absolutePath,0)?.versionName){"Fixture must be an APK"}
        val release=UpdateRelease(candidateVersion,"安装链路验证","",candidate.length(),hash)
        val source=object:UpdateSource {
            override fun latest()=release
            override fun download(release:UpdateRelease,target:File,progress:(Int)->Unit){target.parentFile?.mkdirs();candidate.copyTo(target,overwrite=true);progress(100)}
            override fun cancel(){}
        }
        val vm=UpdateViewModel(app,source)
        try {
            compose.activity.setContent{MaterialTheme{UpdatePanel(vm){}}}
            compose.onNodeWithText("检查更新").performClick()
            compose.waitUntil(15000){vm.state.value.available}
            compose.onNodeWithText("下载更新").performClick()
            compose.waitUntil(15000){vm.state.value.ready!=null||vm.state.value.error.isNotBlank()}
            assertEquals("",vm.state.value.error)
            compose.onNodeWithText("下载及校验完成").assertIsDisplayed()
            assertTrue(app.packageManager.canRequestPackageInstalls())
            compose.onNodeWithText("安装更新").performClick()
            compose.waitUntil(15000){
                val root=instrumentation.uiAutomation.rootInActiveWindow
                root?.packageName?.toString()?.contains("packageinstaller")==true &&
                    (root.findAccessibilityNodeInfosByText("Update").isNotEmpty()||root.findAccessibilityNodeInfosByText("更新").isNotEmpty()) &&
                    (root.findAccessibilityNodeInfosByText("Cancel").isNotEmpty()||root.findAccessibilityNodeInfosByText("取消").isNotEmpty())
            }
            instrumentation.uiAutomation.waitForIdle(500,5000)
            val screenshot=instrumentation.uiAutomation.takeScreenshot()
            if(screenshot!=null){File(app.getExternalFilesDir(null),"qa-update-installer.png").outputStream().use{screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};screenshot.recycle()}
        }finally{
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            vm.state.value.ready?.delete()
        }
    }
    @Test fun presentsVersionNotesDownloadAndInstallerAction(){
        val release=UpdateRelease("0.5.0","改进同步体验","",1024,"0".repeat(64))
        var downloaded=false;var installed=false
        compose.activity.setContent{MaterialTheme{UpdateDialog(UpdateState(installed="0.4.0",checked=true,release=release),{}, {downloaded=true},{},{},{})}}
        compose.onNodeWithText("发现新版本 0.5.0").assertIsDisplayed()
        compose.onNodeWithText("改进同步体验").assertIsDisplayed()
        compose.onNodeWithText("下载更新").performClick();assertTrue(downloaded)
        compose.activity.setContent{MaterialTheme{UpdateDialog(UpdateState(installed="0.4.0",release=release,ready=File("fixture.apk")),{},{},{installed=true},{},{})}}
        compose.onNodeWithText("安装更新").performClick();assertTrue(installed)
    }
    @Test fun exposesOnlyUpdateCacheThroughContentUri(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"updates/provider-test.apk").apply{parentFile?.mkdirs();writeText("fixture")}
        try {
            val intent=ApkUpdates.installIntent(context,file)
            assertEquals(Intent.ACTION_VIEW,intent.action);assertEquals("content",intent.data?.scheme)
            assertEquals("application/vnd.android.package-archive",intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
            assertEquals("fixture",context.contentResolver.openInputStream(intent.data!!)!!.bufferedReader().use{it.readText()})
            assertThrows(IllegalArgumentException::class.java){ApkUpdates.installIntent(context,File(context.cacheDir,"connection.json"))}
        }finally{file.delete()}
    }
    @Test fun updateEntryWorksOnPairingScreenAndShowsCurrentVersion(){
        val vm=androidx.lifecycle.ViewModelProvider(compose.activity)[ChatViewModel::class.java]
        val store=SecureStore(InstrumentationRegistry.getInstrumentation().targetContext);val original=store.read()
        try {
            compose.runOnIdle{vm.disconnect()}
            compose.onNodeWithText("检查更新").performClick()
            compose.onNodeWithText("应用更新").assertIsDisplayed()
            compose.onNodeWithText("当前版本 ${ApkUpdates.installed(compose.activity).versionName}").assertIsDisplayed()
        }finally{store.save(original)}
    }
}
