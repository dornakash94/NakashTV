package tv.nakash.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import tv.nakash.BuildConfig
import tv.nakash.data.local.AccountStore
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** The server rejects unknown User-Agents (bot detection). One fixed UA on every request: API, images, video. */
class UserAgentInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", BuildConfig.USER_AGENT).build())
}

/** Adds username/password to every player_api call from the stored account. */
class AuthInterceptor(private val currentAccount: () -> tv.nakash.data.local.Account?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val acc = currentAccount() ?: return chain.proceed(chain.request())
        val base = acc.baseUrl.toHttpUrlOrNull() ?: return chain.proceed(chain.request())
        val requested = chain.request().url
        val allowedPaths = setOf(base.encodedPath + "player_api.php", base.encodedPath + "xmltv.php")
        if (requested.scheme != base.scheme || requested.host != base.host ||
            requested.port != base.port || requested.encodedPath !in allowedPaths) {
            return chain.proceed(chain.request())
        }
        val url = chain.request().url.newBuilder()
            .setQueryParameter("username", acc.username)
            .setQueryParameter("password", acc.password)
            .build()
        return chain.proceed(chain.request().newBuilder().url(url).build())
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    @Provides @Singleton
    fun okHttp(account: AccountStore): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(UserAgentInterceptor())
        .addInterceptor(AuthInterceptor(account::current))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Retrofit is rebuilt when the account (base URL) changes; see ApiProvider. */
    @Provides @Singleton
    fun apiProvider(client: OkHttpClient, account: AccountStore) = ApiProvider(client, account)
}

class ApiProvider(private val client: OkHttpClient, private val account: AccountStore) {
    @Volatile private var cached: Pair<String, XtreamApi>? = null
    suspend fun authenticate(candidate: tv.nakash.data.local.Account): AuthResponse {
        val authClient = client.newBuilder().apply {
            interceptors().removeAll { it is AuthInterceptor }
            addInterceptor(AuthInterceptor { candidate })
        }.build()
        return Retrofit.Builder().baseUrl(candidate.baseUrl).client(authClient)
            .addConverterFactory(NetworkModule.json.asConverterFactory("application/json".toMediaType()))
            .build().create(XtreamApi::class.java).auth()
    }
    fun api(): XtreamApi {
        val base = account.current()?.baseUrl ?: error("Not signed in")
        cached?.let { if (it.first == base) return it.second }
        val api = Retrofit.Builder().baseUrl(base).client(client)
            .addConverterFactory(NetworkModule.json.asConverterFactory("application/json".toMediaType()))
            .build().create(XtreamApi::class.java)
        cached = base to api
        return api
    }
    fun urls(): StreamUrls { val a = account.current() ?: error("Not signed in"); return StreamUrls(a.baseUrl, a.username, a.password) }
}
