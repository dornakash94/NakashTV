package tv.nakash.data.diag

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tv.nakash.BuildConfig
import tv.nakash.data.local.AccountStore
import tv.nakash.data.remote.AuthInterceptor
import tv.nakash.domain.DiagReport
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Error reports from the family's TVs, so a failure seen far away can be diagnosed afterwards: crashes and playback
 * errors are written to a file at once (a crash kills the process right after), then sent to the sync server as items of
 * kind "diag" under a per-install key. Nothing identifying leaves the TV: URLs and the provider login are removed, and
 * the account appears only as the first characters of the same hash the profile sync uses.
 *
 * Read them with: wrangler d1 execute nakashtv-sync --remote --command "SELECT data FROM items WHERE kind='diag' ORDER BY updated_at DESC LIMIT 20"
 */
@Singleton
class Diagnostics @Inject constructor(
    @ApplicationContext private val ctx: Context, okHttp: OkHttpClient, private val accounts: AccountStore,
) {
    private val client = okHttp.newBuilder().apply { interceptors().removeAll { it is AuthInterceptor } }
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val base = BuildConfig.SYNC_URL.trimEnd('/')
    private val dir = File(ctx.filesDir, "diag")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val recent = HashMap<String, Long>()
    private var dayStart = 0L
    private var today = 0

    private val installId: String by lazy {
        val p = ctx.getSharedPreferences("diag", Context.MODE_PRIVATE)
        p.getString("install", null) ?: UUID.randomUUID().toString().also { p.edit().putString("install", it).apply() }
    }

    /** Catches crashes for the whole process and sends what earlier runs left behind. Call once from Application. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { save(report("crash", e) { put("thread", thread.name) }) }
            previous?.uncaughtException(thread, e)
        }
        upload()
    }

    /** A playback error the user saw (or that the player recovered from), with what was playing. */
    fun playback(e: Throwable, code: String, extra: Map<String, String?>) {
        // The same failure on the same title once per 10 minutes, and at most 40 reports a day from a TV.
        val now = System.currentTimeMillis()
        synchronized(recent) {
            recent.entries.removeAll { now - it.value > 10 * 60_000 }
            if (recent.put("$code|${extra["title"]}", now) != null) return
            if (now - dayStart > 86_400_000) { dayStart = now; today = 0 }
            if (++today > 40) return
        }
        runCatching { save(report("playback", e) { put("code", code); extra.forEach { (k, v) -> if (v != null) put(k, DiagReport.redact(v, secrets())) } }) }
        upload()
    }

    /** Sends the saved reports (oldest first); each is deleted once the server has it. */
    fun upload() {
        if (base.isBlank()) return
        scope.launch {
            lock.withLock {
                val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
                for (batch in files.chunked(20)) {
                    val items = buildJsonArray {
                        batch.forEach { f ->
                            val data = runCatching { json.parseToJsonElement(f.readText()).jsonObject }.getOrNull() ?: return@forEach
                            add(buildJsonObject {
                                put("profile", "device"); put("kind", "diag"); put("key", f.nameWithoutExtension)
                                put("data", data); put("deleted", false); put("updatedAt", f.nameWithoutExtension.substringBefore('-').toLongOrNull() ?: System.currentTimeMillis())
                            })
                        }
                    }
                    val body = buildJsonObject { put("profiles", buildJsonArray { }); put("items", items) }
                    val ok = runCatching {
                        client.newCall(Request.Builder().url("$base/v1/state").header("Authorization", "Bearer ${sha("nakashtv-diag|$installId")}")
                            .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { it.isSuccessful }
                    }.getOrDefault(false)
                    if (!ok) return@withLock
                    batch.forEach { it.delete() }
                }
            }
        }
    }

    private fun report(type: String, e: Throwable, more: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject {
        val s = secrets()
        return buildJsonObject {
            put("t", type); put("at", System.currentTimeMillis())
            put("app", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.DEBUG) " debug" else ""}")
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}"); put("sdk", Build.VERSION.SDK_INT)
            put("install", installId.take(8))
            accounts.current()?.let { a -> put("account", sha("${a.baseUrl.lowercase()}|${a.username}|${a.password}").take(12)) }
            more()
            put("causes", buildJsonArray { DiagReport.causes(e, s).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("stack", buildJsonArray { DiagReport.stack(e).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }
    }

    /** Written synchronously: after a crash the process is gone a moment later. Keeps the newest 30. */
    private fun save(r: JsonObject) {
        dir.mkdirs()
        File(dir, "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}.json").writeText(r.toString())
        dir.listFiles()?.sortedByDescending { it.name }?.drop(30)?.forEach { it.delete() }
    }

    private fun secrets(): List<String> = accounts.current()?.let { a ->
        listOf(a.username, a.password, a.baseUrl.substringAfter("://").substringBefore('/').substringBefore(':'))
    }.orEmpty()

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
