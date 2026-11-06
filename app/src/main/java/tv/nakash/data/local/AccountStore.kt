package tv.nakash.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class Account(val baseUrl: String, val username: String, val password: String) {
    /** Preserve an explicit scheme and port. Bare hostnames default to HTTPS. */
    companion object {
        fun normalizeBaseUrl(input: String): String {
            var s = input.trim()
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            return s.trimEnd('/') + "/"
        }
    }
}

/** Credentials live in EncryptedSharedPreferences, never in Room. */
@Singleton
class AccountStore @Inject constructor(@ApplicationContext ctx: Context) {
    private val prefs = SecurePrefs.open(ctx, "account")
    private val _account = MutableStateFlow(runCatching { read() }.getOrNull())
    val account: StateFlow<Account?> = _account
    fun current(): Account? = _account.value
    fun save(a: Account) { prefs.edit().putString("base", a.baseUrl).putString("user", a.username).putString("pass", a.password).apply(); _account.value = a }
    fun clear() { prefs.edit().clear().apply(); _account.value = null }
    private fun read(): Account? {
        val b = prefs.getString("base", null) ?: return null
        return Account(b, prefs.getString("user", "") ?: "", prefs.getString("pass", "") ?: "")
    }
}

/**
 * EncryptedSharedPreferences that never takes the app down. The Android Keystore is unreliable on some TV boxes
 * (KeyStoreException / AEADBadTagException / InvalidProtocolBufferException at start-up, on every start), and this
 * store is created before the first screen, so a failure here crashed every launch. On failure the broken file and
 * key are thrown away and made again (the user signs in once more); if the Keystore still refuses, the login is kept
 * in the app's private storage instead (sandboxed, and allowBackup is off).
 */
internal object SecurePrefs {
    private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"

    fun open(ctx: Context, name: String): SharedPreferences {
        // A device that already fell back keeps using that file (a later Keystore success must not sign it out).
        val plain = ctx.getSharedPreferences("${name}_plain", Context.MODE_PRIVATE)
        if (plain.all.isNotEmpty()) return plain
        return runCatching { encrypted(ctx, name) }.recoverCatching {
            ctx.deleteSharedPreferences(name)
            runCatching { java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(MASTER_KEY_ALIAS) }
            encrypted(ctx, name)
        }.getOrElse { plain }
    }

    private fun encrypted(ctx: Context, name: String): SharedPreferences {
        val p = EncryptedSharedPreferences.create(
            ctx, name, MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        p.getString("base", null) // a file written under a lost key fails on the first read, not on create
        return p
    }
}
