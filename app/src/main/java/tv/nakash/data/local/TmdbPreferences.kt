package tv.nakash.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The TMDB API key: built into the APK from the build machine's local.properties (so nobody types it on a TV), or
 * typed in Settings to override it. Without either, the app simply shows no TMDB extras.
 */
@Singleton
class TmdbPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("tmdb", Context.MODE_PRIVATE)
    private val builtIn = tv.nakash.BuildConfig.TMDB_KEY.takeIf { it.isNotBlank() }
    /** A key typed in Settings wins; otherwise the one built into the APK. */
    private val _key = MutableStateFlow(prefs.getString(KEY, null)?.takeIf { it.isNotBlank() } ?: builtIn)
    val key: StateFlow<String?> = _key
    val hasBuiltIn get() = builtIn != null
    fun set(value: String?) {
        val v = value?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit().apply { if (v == null) remove(KEY) else putString(KEY, v) }.apply()
        _key.value = v ?: builtIn
    }
    private companion object { const val KEY = "api_key" }
}
