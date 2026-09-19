package tv.nakash.player

import android.content.Context
import android.graphics.Bitmap
import android.view.SurfaceView
import android.view.TextureView
import android.view.PixelCopy
import android.os.Handler
import android.os.Looper
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.update
import android.media.MediaMetadataRetriever
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import tv.nakash.BuildConfig

/** Single, conflated background decoder. Extracts only the requested neighborhood, never an entire film. */
@Singleton
class ThumbnailGenerator @Inject constructor(@ApplicationContext ctx:Context) {
    private val root=File(ctx.cacheDir,"thumbs").apply {mkdirs()}
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private data class Target(val key:String,val url:String,val seconds:Long,val duration:Long)
    private val requests=Channel<Target?>(Channel.CONFLATED)
    @Volatile private var latest:Target?=null
    val revision=MutableStateFlow(0)
    val unavailable=MutableStateFlow<String?>(null)
    fun dirFor(key:String)=File(root,key.replace(':','_')).apply {mkdirs()}
    fun frameFile(key:String,seconds:Long)=File(dirFor(key),"${seconds.coerceAtLeast(0)/STEP_SEC}.jpg")
    fun existing(key:String,seconds:Long)=frameFile(key,seconds).takeIf {it.isFile && it.length()>0}
    fun request(key:String,url:String,seconds:Long,duration:Long) {
        if(key.isBlank() || duration<=0) return
        val target=Target(key,url,seconds.coerceIn(0,duration-1)/STEP_SEC*STEP_SEC,duration)
        if(target==latest) return
        latest=target;requests.trySend(target)
    }
    private var capturePending=false
    /** Reuse the already decoded video surface: no download, seek or second decoder. Call on main. */
    fun capture(view:PlayerView,key:String,seconds:Long) {
        if(capturePending || key.isBlank() || existing(key,seconds)!=null || !view.isAttachedToWindow) return
        val surface=view.videoSurfaceView ?: return
        if(surface.width<=0 || surface.height<=0) return
        capturePending=true
        val bitmap=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888)
        fun finish(success:Boolean) {
            if(!success) {bitmap.recycle();capturePending=false;return}
            scope.launch {
                try {
                    val file=frameFile(key,seconds)
                    val temporary=File(file.parentFile,file.name+".capture.tmp")
                    temporary.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,78,it)}
                    if(temporary.renameTo(file)) {revision.update {it+1};if(BuildConfig.DEBUG) android.util.Log.i("NakashThumbs","surface_cached second=$seconds")}
                } catch(_:Exception) { /* A cache write must never interrupt playback. */ } finally {bitmap.recycle();withContext(Dispatchers.Main) {capturePending=false}}
            }
        }
        try {
            when(surface) {
                is SurfaceView -> if(surface.holder.surface.isValid) PixelCopy.request(surface,bitmap,{finish(it==PixelCopy.SUCCESS)},Handler(Looper.getMainLooper())) else finish(false)
                is TextureView -> {finish(surface.isAvailable && surface.getBitmap(bitmap)!=null)}
                else -> finish(false)
            }
        } catch(_:Exception) {finish(false)}
    }
    fun stop() {latest=null;requests.trySend(null)}
    init {
        scope.launch {
            var retriever:MediaMetadataRetriever?=null
            var source:String?=null
            try {
                for(target in requests) {
                    if(target==null) {runCatching {retriever?.release()};retriever=null;source=null;continue}
                    try {
                        if(source!=target.url) {
                            runCatching {retriever?.release()};retriever=null;source=null
                            val next=MediaMetadataRetriever();retriever=next
                            next.setDataSource(target.url,mapOf("User-Agent" to BuildConfig.USER_AGENT))
                            source=target.url;unavailable.value=null
                        }
                        for(offset in listOf(0,1,-1,2,-2,3,-3)) {
                            if(latest!=target) break
                            val second=(target.seconds+offset*STEP_SEC).coerceIn(0,target.duration-1)/STEP_SEC*STEP_SEC
                            if(existing(target.key,second)!=null) continue
                            val bitmap=retriever?.getScaledFrameAtTime(second*1_000_000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,320,180) ?: continue
                            try {
                                val file=frameFile(target.key,second)
                                val temp=File(file.parentFile,file.name+".tmp")
                                temp.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,78,it)}
                                if(temp.renameTo(file)) {revision.update {it+1};if(BuildConfig.DEBUG) android.util.Log.i("NakashThumbs","frame_ready second=$second")}
                            } finally {bitmap.recycle()}
                        }
                        evictIfNeeded()
                    } catch(cancel:CancellationException) {throw cancel}
                    catch(error:Exception) {if(BuildConfig.DEBUG) android.util.Log.i("NakashThumbs","extraction_unavailable type=${error.javaClass.simpleName}");unavailable.value=target.key;runCatching {retriever?.release()};retriever=null;source=null}
                }
            } finally {runCatching {retriever?.release()}}
        }
    }
    fun evictIfNeeded(maxBytes:Long=128_000_000) {
        val files=root.walk().filter {it.isFile}.sortedBy {it.lastModified()}.toList()
        var bytes=files.sumOf {it.length()}
        for(file in files) {if(bytes<=maxBytes) break;val size=file.length();if(file.delete()) bytes-=size}
    }
    companion object {const val STEP_SEC=10L}
}
