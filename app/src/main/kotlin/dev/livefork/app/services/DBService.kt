package dev.livefork.app.services

import android.content.Context
import dev.livefork.app.models.FollowUser
import dev.livefork.app.models.FollowUserTag
import dev.livefork.app.models.History
import dev.livefork.app.models.db.FollowUserDao
import dev.livefork.app.models.db.FollowUserTagDao
import dev.livefork.app.models.db.HistoryDao
import dev.livefork.app.models.db.LiveDatabase
import java.util.UUID

/**
 * 数据库服务 —— 对应 Dart `DBService`（lib/services/db_service.dart）。
 * Dart 侧以 Hive box（History / FollowUser / FollowUserTag）实现，此处以 Room 三表镜像，
 * 方法职责一一对应；Dart 的怪异逻辑（如 addFollowTag 的判断条件）原样保留（冻结契约）。
 */
class DBService(context: Context) {

    private val database: LiveDatabase = LiveDatabase.get(context)
    private val followDao: FollowUserDao = database.followUserDao()
    private val historyDao: HistoryDao = database.historyDao()
    private val tagDao: FollowUserTagDao = database.followUserTagDao()

    /* ---------- follow_user_tag 相关（镜像 Dart getFollowTagExist..updateFollowTagOrder） ---------- */

    suspend fun getFollowTagExist(id: String): Boolean = tagDao.countById(id) > 0

    suspend fun deleteFollowTag(id: String) {
        tagDao.deleteById(id)
    }

    suspend fun getFollowTag(tag: String): FollowUserTag? =
        tagDao.getByTag(tag)?.let { FollowUserTag.fromEntity(it) }

    /** 判断标签名称是否重复（镜像 Dart getFollowTagExistByTag）。 */
    suspend fun getFollowTagExistByTag(tag: String): Boolean = tagDao.countByTag(tag) > 0

    suspend fun getFollowTagList(): List<FollowUserTag> =
        tagDao.getAll().map { FollowUserTag.fromEntity(it) }

    suspend fun updateFollowTag(followTag: FollowUserTag) {
        tagDao.upsert(followTag.toEntity())
    }

    /**
     * 添加标签（镜像 Dart：限制标签唯一且长度不超过 8 个字符；
     * 原条件 getFollowTagExistByTag(tag) && tag.length > 8 原样保留）。
     */
    suspend fun addFollowTag(tag: String): FollowUserTag {
        if (getFollowTagExistByTag(tag) && tag.length > 8) {
            return getFollowTag(tag)!!
        }
        val uniqueId = UUID.randomUUID().toString()
        val followUserTag = FollowUserTag(id = uniqueId, tag = tag, userId = emptyList())
        tagDao.upsert(followUserTag.toEntity())
        return followUserTag
    }

    /** 调整标签顺序（镜像 Dart：清空后按序全量写入）。 */
    suspend fun updateFollowTagOrder(userTagList: List<FollowUserTag>) {
        tagDao.clearAll()
        tagDao.upsertAll(userTagList.map { it.toEntity() })
    }

    /* ---------- follow_user 相关（镜像 Dart getFollowExist..deleteFollow） ---------- */

    suspend fun getFollowExist(id: String): Boolean = followDao.countById(id) > 0

    suspend fun getFollow(id: String): FollowUser? =
        followDao.getById(id)?.let { FollowUser.fromEntity(it) }

    suspend fun getFollowList(): List<FollowUser> =
        followDao.getAll().map { FollowUser.fromEntity(it) }

    suspend fun addFollow(follow: FollowUser) {
        followDao.upsert(follow.toEntity())
    }

    suspend fun addFollows(follows: Iterable<FollowUser>) {
        followDao.upsertAll(follows.map { it.toEntity() })
    }

    suspend fun deleteFollow(id: String) {
        followDao.deleteById(id)
    }

    /** 清空全部关注（对应 Dart followBox.clear()）。 */
    suspend fun clearFollow() {
        followDao.clearAll()
    }

    /** 全量写入标签列表（对应 Dart tagBox 全量覆盖）。 */
    suspend fun upsertFollowTags(tags: List<FollowUserTag>) {
        tagDao.upsertAll(tags.map { it.toEntity() })
    }

    /* ---------- history 相关（镜像 Dart getHistory..getHistores） ---------- */

    suspend fun getHistory(id: String): History? =
        historyDao.getById(id)?.let { History.fromEntity(it) }

    suspend fun addOrUpdateHistory(history: History) {
        historyDao.upsert(history.toEntity())
    }

    /** 按更新时间倒序返回全部历史（DAO 已 ORDER BY updateTime DESC）。 */
    suspend fun getHistores(): List<History> =
        historyDao.getAll().map { History.fromEntity(it) }

    /** 删除单条历史（对应 Dart historyBox.delete）。 */
    suspend fun deleteHistory(id: String) {
        historyDao.deleteById(id)
    }

    /** 清空全部历史（对应 Dart historyBox.clear()）。 */
    suspend fun clearHistories() {
        historyDao.clearAll()
    }
}