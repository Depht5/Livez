package dev.livefork.app.models.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room 数据库 —— 对应 Dart 侧 Hive box（History / FollowUser / FollowUserTag）。
 * 数据库名 `live_db`；Hive 的三个 box 各镜像为一张表。
 */
@Database(
    entities = [FollowUserEntity::class, HistoryEntity::class, FollowUserTagEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(TagConverters::class)
abstract class LiveDatabase : RoomDatabase() {

    abstract fun followUserDao(): FollowUserDao

    abstract fun historyDao(): HistoryDao

    abstract fun followUserTagDao(): FollowUserTagDao

    companion object {
        @Volatile
        private var instance: LiveDatabase? = null

        fun get(context: Context): LiveDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LiveDatabase::class.java,
                    "live_db",
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}

/** 关注用户 DAO（对应 Hive followBox 操作）。 */
@Dao
interface FollowUserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: FollowUserEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<FollowUserEntity>)

    @Query("SELECT * FROM follow_user")
    suspend fun getAll(): List<FollowUserEntity>

    @Query("SELECT * FROM follow_user WHERE id = :id")
    suspend fun getById(id: String): FollowUserEntity?

    @Query("SELECT COUNT(*) FROM follow_user WHERE id = :id")
    suspend fun countById(id: String): Int

    @Query("DELETE FROM follow_user WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM follow_user")
    suspend fun clearAll()
}

/** 观看历史 DAO（对应 Hive historyBox 操作）。 */
@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: HistoryEntity)

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun getById(id: String): HistoryEntity?

    /** 按更新时间倒序（对应 Dart getHistores 排序）。 */
    @Query("SELECT * FROM history ORDER BY updateTime DESC")
    suspend fun getAll(): List<HistoryEntity>

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM history")
    suspend fun clearAll()
}

/** 关注标签 DAO（对应 Hive tagBox 操作）。 */
@Dao
interface FollowUserTagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: FollowUserTagEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<FollowUserTagEntity>)

    @Query("SELECT * FROM follow_user_tag")
    suspend fun getAll(): List<FollowUserTagEntity>

    @Query("SELECT * FROM follow_user_tag WHERE id = :id")
    suspend fun getById(id: String): FollowUserTagEntity?

    @Query("SELECT * FROM follow_user_tag WHERE tag = :tag LIMIT 1")
    suspend fun getByTag(tag: String): FollowUserTagEntity?

    @Query("SELECT COUNT(*) FROM follow_user_tag WHERE id = :id")
    suspend fun countById(id: String): Int

    @Query("SELECT COUNT(*) FROM follow_user_tag WHERE tag = :tag")
    suspend fun countByTag(tag: String): Int

    @Query("DELETE FROM follow_user_tag WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM follow_user_tag")
    suspend fun clearAll()
}