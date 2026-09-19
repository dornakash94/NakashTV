package tv.nakash

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import tv.nakash.data.repo.SyncWorker
import javax.inject.Inject

@HiltAndroidApp
class NakashApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var okHttp: OkHttpClient

    override fun onCreate() { super.onCreate(); SyncWorker.schedule(this) }
    override val workManagerConfiguration get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /** Posters/logos go through the same OkHttp (fixed User-Agent), 512 MB disk cache. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttp })) }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(512L * 1024 * 1024).build() }
        .crossfade(200).build()
}
