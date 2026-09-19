package tv.nakash.data.local

import android.content.Context
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
    private val prefs = EncryptedSharedPreferences.create(
        ctx, "account", MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val _account = MutableStateFlow(read())
    val account: StateFlow<Account?> = _account
    fun current(): Account? = _account.value
    fun save(a: Account) { prefs.edit().putString("base", a.baseUrl).putString("user", a.username).putString("pass", a.password).apply(); _account.value = a }
    fun clear() { prefs.edit().clear().apply(); _account.value = null }
    private fun read(): Account? {
        val b = prefs.getString("base", null) ?: return null
        return Account(b, prefs.getString("user", "") ?: "", prefs.getString("pass", "") ?: "")
    }
}
