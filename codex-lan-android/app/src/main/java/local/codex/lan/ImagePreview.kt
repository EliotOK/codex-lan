package local.codex.lan

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private data class ImageLoad(val bitmap:Bitmap?=null,val error:String?=null)
@Composable private fun load(image:ChatImage,full:Boolean,retry:Int,loader:suspend(ChatImage,Boolean)->Bitmap):ImageLoad {
    val state by produceState(ImageLoad(),image,full,retry){
        value=ImageLoad()
        try{value=ImageLoad(bitmap=loader(image,full))}
        catch(error:kotlinx.coroutines.CancellationException){throw error}
        catch(error:Exception){value=ImageLoad(error=error.message?:"图片加载失败")}
    }
    return state
}
@Composable fun ImagePreview(image:ChatImage,loader:suspend(ChatImage,Boolean)->Bitmap) {
    var retry by remember(image){mutableIntStateOf(0)}
    var expanded by rememberSaveable(image.reference){mutableStateOf(false)}
    val result=load(image,false,retry,loader)
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)){
        if(result.bitmap!=null){
            Image(result.bitmap.asImageBitmap(),"图片：${image.name}",contentScale=ContentScale.Fit,
                modifier=Modifier.fillMaxWidth().heightIn(min=100.dp,max=280.dp).background(Color(0xFF0B100D)).clickable{expanded=true}.testTag("image-preview"))
            Text("点击查看大图 · ${image.name}",fontSize=11.sp,color=MaterialTheme.colorScheme.primary)
        }else if(result.error!=null){
            Text(result.error,fontSize=12.sp,color=MaterialTheme.colorScheme.error)
            TextButton(onClick={retry++}){Text("重试图片")}
        }else Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Text("正在加载图片…",fontSize=12.sp)
        }
    }
    if(expanded)ImageViewer(image,loader){expanded=false}
}
@Composable private fun ImageViewer(image:ChatImage,loader:suspend(ChatImage,Boolean)->Bitmap,close:()->Unit){
    var retry by remember{mutableIntStateOf(0)}
    val result=load(image,true,retry,loader)
    var scale by remember{mutableFloatStateOf(1f)}
    var offset by remember{mutableStateOf(Offset.Zero)}
    var size by remember{mutableStateOf(IntSize.Zero)}
    val transform=rememberTransformableState{zoom,pan,_->
        scale=(scale*zoom).coerceIn(1f,6f)
        val next=offset+pan
        offset=if(scale<=1f)Offset.Zero else Offset(next.x.coerceIn(-size.width*(scale-1)/2,size.width*(scale-1)/2),next.y.coerceIn(-size.height*(scale-1)/2,size.height*(scale-1)/2))
    }
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()){
            Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){
                TextButton(onClick=close){Text("关闭图片")}
                Text(image.name,Modifier.weight(1f),color=Color.White,fontSize=12.sp,maxLines=1)
                TextButton(onClick={scale=1f;offset=Offset.Zero}){Text("重置缩放")}
            }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged{size=it}
                .testTag("image-viewer").transformable(transform).pointerInput(Unit){detectTapGestures(onDoubleTap={scale=if(scale>1f)1f else 2.5f;offset=Offset.Zero})},contentAlignment=Alignment.Center){
                if(result.bitmap!=null)Image(result.bitmap.asImageBitmap(),"大图：${image.name}",contentScale=ContentScale.Fit,
                    modifier=Modifier.fillMaxSize().graphicsLayer{scaleX=scale;scaleY=scale;translationX=offset.x;translationY=offset.y})
                else if(result.error!=null)Column(horizontalAlignment=Alignment.CenterHorizontally){Text(result.error,color=Color.White);TextButton(onClick={retry++}){Text("重试图片")}}
                else CircularProgressIndicator()
            }
            Text("双指缩放 · 双击放大 · ${(scale*100).toInt()}%",Modifier.padding(16.dp).testTag("image-zoom"),color=Color.White,fontSize=12.sp)
        }
    }
}
