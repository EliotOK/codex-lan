package local.codex.lan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class UpdateState(val installed:String="",val checked:Boolean=false,val checking:Boolean=false,
    val release:UpdateRelease?=null,val downloading:Boolean=false,val progress:Int=0,val ready:File?=null,val error:String="") {
    val available:Boolean get()=release?.let{runCatching{it.newerThan(installed)}.getOrDefault(false)}==true
}
class UpdateViewModel @JvmOverloads constructor(app:Application,private val source:UpdateSource=UpdateTransport()):AndroidViewModel(app) {
    private val mutable=MutableStateFlow(UpdateState(installed=ApkUpdates.installed(app).versionName.orEmpty()))
    val state=mutable.asStateFlow()
    private var job:Job?=null
    private fun change(block:(UpdateState)->UpdateState){mutable.value=block(mutable.value)}
    fun check(){
        if(job?.isActive==true)return
        change{it.copy(checking=true,error="")}
        job=viewModelScope.launch{
            try {val release=withContext(Dispatchers.IO){source.latest()};change{it.copy(release=release,checked=true,ready=if(it.release==release)it.ready else null)}}
            catch(e:CancellationException){throw e}
            catch(e:Exception){change{it.copy(error="检查失败：${e.message?:"无法连接 GitHub，请稍后重试"}")}}
            finally{change{it.copy(checking=false)}}
        }
    }
    fun download(){
        val release=state.value.release?:return
        if(job?.isActive==true||!state.value.available)return
        change{it.copy(downloading=true,ready=null,progress=0,error="")}
        job=viewModelScope.launch{
            val target=File(getApplication<Application>().cacheDir,"updates/codex-lan-${release.version}.apk")
            try {
                withContext(Dispatchers.IO){
                    val coroutine=kotlin.coroutines.coroutineContext
                    source.download(release,target){value->coroutine.ensureActive();change{it.copy(progress=value)}}
                    coroutine.ensureActive();ApkUpdates.verify(getApplication(),target,release)
                }
                change{it.copy(ready=target)}
            }catch(e:CancellationException){withContext(NonCancellable+Dispatchers.IO){target.delete()};throw e}
            catch(e:Exception){withContext(Dispatchers.IO){target.delete()};change{it.copy(error="更新失败：${e.message?:"请重新下载"}")}}
            finally{change{it.copy(downloading=false)}}
        }
    }
    fun cancel(){source.cancel();job?.cancel();change{it.copy(checking=false,downloading=false,error="已取消，可以重新检查或下载")}}
    fun error(message:String){change{it.copy(error=message)}}
    override fun onCleared(){source.cancel();super.onCleared()}
}
