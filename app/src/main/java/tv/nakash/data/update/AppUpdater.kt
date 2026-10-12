package tv.nakash.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.nakash.BuildConfig
import tv.nakash.data.remote.AuthInterceptor
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The latest release, as the sync server publishes it (GET /v1/app). */
@Serializable
data class AppRelease(val versionCode: Int = 0, val versionName: String = "", val url: String = "", val notes: String = "", val mandatory: Boolean = false)

sealed interface UpdateState {
    data object None : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    /** Android needs "install unknown apps" for NakashTV first (once per device). */
    data class NeedsPermission(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val percent: Int) : UpdateState
    data class Installing(val release: AppRelease) : UpdateState
    data class Failed(val release: AppRelease, val message: String) : UpdateState
}

/**
 * In-app updates. The sync server says which release is the latest (we set it on every release); the app asks on start
 * and every 6 hours, offers it, downloads the APK and hands it to Android's installer, which asks the user to confirm.
 * Same signing key as before, so it updates in place and keeps the login, profiles and history.
 */
@Singleton
class AppUpdater @Inject constructor(okHttp: OkHttpClient, @ApplicationContext private val ctx: Context) {
    private val client = okHttp.newBuilder().apply { interceptors().removeAll { it is AuthInterceptor } }
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val base = BuildConfig.SYNC_URL.trimEnd('/')
    private val prefs = ctx.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var loop: Job? = null
    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state
    /** True while the player is on screen: the prompt waits until playback is left. */
    val holdPrompt = MutableStateFlow(false)

    fun start() {
        if (base.isBlank() || loop?.isActive == true) return
        loop = scope.launch { while (isActive) { runCatching { check() }; delay(6 * 60 * 60_000L) } }
    }

    private suspend fun check() {
        if (_state.value !is UpdateState.None) return
        val channel = if (BuildConfig.DEBUG) "debug" else "stable"
        val body = client.newCall(Request.Builder().url("$base/v1/app?channel=$channel").build()).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return
        val r = json.decodeFromString(AppRelease.serializer(), body)
        if (r.versionCode <= BuildConfig.VERSION_CODE || r.url.isBlank()) return
        if (!r.mandatory && System.currentTimeMillis() < prefs.getLong("snooze_${r.versionCode}", 0)) return
        _state.value = UpdateState.Available(r)
    }

    /** "אחר כך": asked again tomorrow (a mandatory release cannot be put off). */
    fun later() {
        val r = releaseOf(_state.value) ?: return
        if (r.mandatory) return
        prefs.edit().putLong("snooze_${r.versionCode}", System.currentTimeMillis() + 24 * 60 * 60_000L).apply()
        _state.value = UpdateState.None
    }

    fun install() {
        val r = releaseOf(_state.value) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(r); return
        }
        scope.launch {
            try {
                val apk = download(r)
                _state.value = UpdateState.Installing(r)
                withContext(Dispatchers.IO) { commit(apk) }
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(r, "ההורדה נכשלה. אפשר לנסות שוב.")
            }
        }
    }

    /** Opens Android's "install unknown apps" switch for NakashTV. */
    fun openPermission() {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(i) }.isFailure)
            runCatching { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    internal fun installResult(status: Int, message: String?) {
        val r = releaseOf(_state.value) ?: return
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.None
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Available(r)   // the user pressed cancel
            else -> UpdateState.Failed(r, "ההתקנה לא הצליחה" + (message?.let { " ($it)" } ?: "") + ".")
        }
    }

    private suspend fun download(r: AppRelease): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, "NakashTV-${r.versionCode}.apk")
        client.newCall(Request.Builder().url(r.url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("http ${resp.code}")
            val body = resp.body ?: error("no body")
            val total = body.contentLength()
            body.byteStream().use { input -> file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L; var last = -1
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (pct != last) { last = pct; _state.value = UpdateState.Downloading(r, pct) }
                }
            } }
        }
        file
    }

    private fun commit(apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(ctx.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("NakashTV.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallResultReceiver::class.java).setPackage(ctx.packageName), flags)
            session.commit(pi.intentSender)
        }
    }

    private fun releaseOf(s: UpdateState): AppRelease? = when (s) {
        is UpdateState.Available -> s.release; is UpdateState.NeedsPermission -> s.release; is UpdateState.Downloading -> s.release
        is UpdateState.Installing -> s.release; is UpdateState.Failed -> s.release; UpdateState.None -> null
    }

    companion object { @Volatile internal var instance: AppUpdater? = null }
    init { instance = this }
}

/** Android's answer to the install: show its confirmation screen, or report the result. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
        }
        AppUpdater.instance?.installResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
