package tv.nakash.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [CategoryEntity::class, ChannelEntity::class, ChannelSourceEntity::class, EpgEntity::class,
        MovieEntity::class, SeriesEntity::class, SeasonEntity::class, EpisodeEntity::class,
        WatchProgressEntity::class, FavoriteEntity::class],
    version = 2, exportSchema = false,
)
abstract class NakashDb : RoomDatabase() {
    abstract fun channels(): ChannelDao
    abstract fun epg(): EpgDao
    abstract fun vod(): VodDao
    abstract fun user(): UserDao
}

@Module
@InstallIn(SingletonComponent::class)
object DbModule {
    @Provides @Singleton fun db(@ApplicationContext ctx: Context): NakashDb =
        Room.databaseBuilder(ctx, NakashDb::class.java, "nakash.db").addMigrations(object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE categories_new (id INTEGER NOT NULL, kind TEXT NOT NULL, name TEXT NOT NULL, rawName TEXT NOT NULL, `order` INTEGER NOT NULL, pinned INTEGER NOT NULL, hidden INTEGER NOT NULL, PRIMARY KEY(id,kind))")
                db.execSQL("INSERT INTO categories_new SELECT * FROM categories")
                db.execSQL("DROP TABLE categories")
                db.execSQL("ALTER TABLE categories_new RENAME TO categories")
            }
        }).build()
    @Provides fun channelDao(db: NakashDb) = db.channels()
    @Provides fun epgDao(db: NakashDb) = db.epg()
    @Provides fun vodDao(db: NakashDb) = db.vod()
    @Provides fun userDao(db: NakashDb) = db.user()
}
