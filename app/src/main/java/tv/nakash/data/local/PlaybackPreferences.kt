package tv.nakash.data.local

import android.content.Context
import android.view.KeyEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Actions a physical remote button can trigger inside the player. */
enum class RemoteAction(val label: String) {
    NONE("ללא פעולה"),
    PLAY_PAUSE("ניגון / השהיה"),
    TRACKS("כתוביות"),
    ASPECT("יחס תמונה"),
    FAVORITE("הוסף / הסר ערוץ מהמועדפים"),
    SOURCE("החלפת מקור (ראשי / גיבוי)"),
    MINI_EPG("לוח שידורים מקוצר"),
    START_OVER("התוכנית מההתחלה (ארכיון)"),
    MENU("תפריט הנגן"),
    CONTROLS("הצגה / הסתרה של פקדי הנגן"),
}

/**
 * Any physical key can be bound, not just the color buttons: the user presses the button in Settings ("learn"),
 * we store its keycode with a readable name. Navigation keys (D-pad, OK, Back, Home) are never bindable.
 */
data class RemoteBinding(val keyCode: Int, val name: String, val action: RemoteAction)

data class PlaybackPreferencesState(
    val seekSeconds: Int = 10,
    val liveOkPauses: Boolean = false,
    val bindings: List<RemoteBinding> = emptyList(),
    val aspectMode: Int = 0,
) {
    val actions: Map<Int, RemoteAction> get() = bindings.associate { it.keyCode to it.action }
    fun action(key: Int): RemoteAction = actions[key] ?: RemoteAction.NONE
    fun isBound(key: Int) = actions.containsKey(key)
}

@Singleton
class PlaybackPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("playback_preferences", Context.MODE_PRIVATE)
    val state = MutableStateFlow(read())

    private fun read(): PlaybackPreferencesState {
        val keys = prefs.getStringSet("bound_keys", null)?.mapNotNull { it.toIntOrNull() }?.sorted()
            ?: DEFAULTS.map { it.keyCode } // first run: defaults
        val bindings = keys.map { k ->
            val def = DEFAULTS.firstOrNull { it.keyCode == k }
            RemoteBinding(
                k,
                prefs.getString("key_name_$k", null) ?: def?.name ?: keyName(k),
                runCatching { RemoteAction.valueOf(prefs.getString("key_$k", null) ?: def?.action?.name ?: "NONE") }.getOrDefault(RemoteAction.NONE),
            )
        }
        return PlaybackPreferencesState(
            prefs.getInt("seek_seconds", 10).takeIf { it in SEEK_OPTIONS } ?: 10,
            prefs.getBoolean("live_ok_pauses", false),
            bindings,
            prefs.getInt("aspect_mode", 0).takeIf { it in listOf(0, 3, 4) } ?: 0,
        )
    }

    fun setSeek(seconds: Int) { require(seconds in SEEK_OPTIONS); prefs.edit().putInt("seek_seconds", seconds).apply(); state.value = read() }
    fun setLiveOk(pauses: Boolean) { prefs.edit().putBoolean("live_ok_pauses", pauses).apply(); state.value = read() }
    fun setAspect(mode: Int) { require(mode in listOf(0, 3, 4)); prefs.edit().putInt("aspect_mode", mode).apply(); state.value = read() }

    /** Bind (or rebind) a physical key. Returns false for keys that must stay reserved for navigation. */
    fun bind(keyCode: Int, action: RemoteAction, name: String = keyName(keyCode)): Boolean {
        if (keyCode in RESERVED) return false
        val keys = (prefs.getStringSet("bound_keys", null) ?: DEFAULTS.map { it.keyCode.toString() }.toSet()).toMutableSet()
        keys += keyCode.toString()
        prefs.edit().putStringSet("bound_keys", keys).putString("key_$keyCode", action.name).putString("key_name_$keyCode", name).apply()
        state.value = read(); return true
    }
    fun unbind(keyCode: Int) {
        val keys = (prefs.getStringSet("bound_keys", null) ?: DEFAULTS.map { it.keyCode.toString() }.toSet()).toMutableSet()
        keys -= keyCode.toString()
        prefs.edit().putStringSet("bound_keys", keys).remove("key_$keyCode").remove("key_name_$keyCode").apply()
        state.value = read()
    }
    fun reset() {
        val e = prefs.edit().remove("seek_seconds").remove("live_ok_pauses").remove("bound_keys")
        prefs.all.keys.filter { it.startsWith("key_") }.forEach { e.remove(it) }
        e.apply(); state.value = read()
    }

    companion object {
        val SEEK_OPTIONS = listOf(5, 10, 20, 30, 60)
        val RESERVED = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER,
        ) + (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9)
        /** Sensible defaults for buttons most Android TV remotes have; the user can rebind or remove them. */
        val DEFAULTS = listOf(
            RemoteBinding(KeyEvent.KEYCODE_MENU, "תפריט", RemoteAction.MENU),
            RemoteBinding(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "Play/Pause", RemoteAction.PLAY_PAUSE),
            RemoteBinding(KeyEvent.KEYCODE_CAPTIONS, "כתוביות", RemoteAction.TRACKS),
            RemoteBinding(KeyEvent.KEYCODE_GUIDE, "Guide", RemoteAction.MINI_EPG),
            RemoteBinding(KeyEvent.KEYCODE_BOOKMARK, "מועדפים", RemoteAction.FAVORITE),
        )
        /** "KEYCODE_PROG_RED" → "Prog Red"; unknown → "מקש 187". */
        fun keyName(keyCode: Int): String {
            val raw = KeyEvent.keyCodeToString(keyCode)
            if (!raw.startsWith("KEYCODE_")) return "מקש $keyCode"
            return raw.removePrefix("KEYCODE_").lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        }
    }
}
