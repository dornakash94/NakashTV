package tv.nakash
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tv.nakash.data.local.AccountStore
import tv.nakash.data.local.NakashDb
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SmokeAccess { fun account(): AccountStore; fun database(): NakashDb }
