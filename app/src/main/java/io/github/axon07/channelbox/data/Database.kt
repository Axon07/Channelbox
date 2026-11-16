package io.github.axon07.channelbox.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "channels")
data class ChannelEntity(
    /** Stable key (the stream URL) so favourites survive a playlist refresh. */
    @PrimaryKey val id: String,
    val number: Int,
    val name: String,
    @ColumnInfo(name = "group_title") val groupTitle: String,
    val logoUrl: String?,
    val streamUrl: String,
    /** XMLTV channel id, lower-cased. Null when the guide has no match. */
    val epgId: String?,
    val catchupDays: Int = 0,
    /** Extra HTTP headers, "Name: value" per line (from #EXTVLCOPT / #KODIPROP / #EXTHTTP). */
    val headers: String? = null,
    val drmType: String? = null,
    val drmKey: String? = null,
    /** #KODIPROP manifest_type (mpd, hls…). */
    val mimeHint: String? = null,
    /** Catch-up mode and URL template (see [Catchup]). */
    val catchupType: String? = null,
    val catchupSource: String? = null,
)

object RecStatus {
    const val SCHEDULED = "scheduled"
    const val RECORDING = "recording"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    val ACTIVE = setOf(SCHEDULED, RECORDING)
}

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: String,
    val channelName: String,
    val title: String,
    // Copy of the stream details, so a recording still works if the playlist changes.
    val streamUrl: String,
    val headers: String?,
    val drmType: String?,
    val drmKey: String?,
    val mimeHint: String?,
    /** Planned start/stop, epoch millis. */
    val start: Long,
    val stop: Long,
    /** Start of the guide programme this recording is for (links it to the guide), if any. */
    val programStart: Long?,
    val filePath: String? = null,
    val status: String = RecStatus.SCHEDULED,
    val sizeBytes: Long = 0,
    val error: String? = null,
)

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY start DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings")
    suspend fun getAll(): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: Long): RecordingEntity?

    @Insert
    suspend fun insert(recording: RecordingEntity): Long

    @Update
    suspend fun update(recording: RecordingEntity)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: Long)
}

@Entity(tableName = "favorites")
data class FavoriteEntity(@PrimaryKey val channelId: String)

@Entity(tableName = "programs", indices = [Index(value = ["epgId", "start"])])
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val epgId: String,
    /** Epoch millis, UTC. */
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
)

@Dao
abstract class ChannelDao {
    @Query("SELECT * FROM channels ORDER BY number, name")
    abstract fun observeAll(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels ORDER BY number, name")
    abstract suspend fun getAll(): List<ChannelEntity>

    @Query("DELETE FROM channels")
    abstract suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(channels: List<ChannelEntity>)

    @Transaction
    open suspend fun replaceAll(channels: List<ChannelEntity>) {
        clear()
        insertAll(channels)
    }

    @Query("UPDATE channels SET epgId = :epgId WHERE id = :id")
    abstract suspend fun setEpgId(id: String, epgId: String)

    @Query("SELECT channelId FROM favorites")
    abstract fun observeFavorites(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE channelId = :id")
    abstract suspend fun removeFavorite(id: String)
}

@Dao
interface ProgramDao {
    @Query("SELECT * FROM programs WHERE stop > :from AND start < :to ORDER BY start")
    fun observeWindow(from: Long, to: Long): Flow<List<ProgramEntity>>

    /** Blocking on purpose: called from the streaming XMLTV parser on an IO thread. */
    @Insert
    fun insertBlocking(programs: List<ProgramEntity>)

    @Query("DELETE FROM programs")
    suspend fun clear()
}

@Database(
    entities = [ChannelEntity::class, FavoriteEntity::class, ProgramEntity::class, RecordingEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channels(): ChannelDao
    abstract fun programs(): ProgramDao
    abstract fun recordings(): RecordingDao

    companion object {
        /** v3: catch-up columns. Keeps favourites and recordings. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN catchupType TEXT")
                db.execSQL("ALTER TABLE channels ADD COLUMN catchupSource TEXT")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "channelbox.db")
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
    }
}
