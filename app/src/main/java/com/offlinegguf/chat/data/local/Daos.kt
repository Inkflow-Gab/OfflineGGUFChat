package com.offlinegguf.chat.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Streaming writes go through [MessageDao.setContent] and [MessageDao.finish],
 * which touch only the columns that changed. An earlier version of this app
 * rewrote the whole row for every token, which turned a long answer into O(n²)
 * disk I/O and visibly stuttered the UI.
 */
@Dao
abstract class ConversationDao {

    @Query("SELECT * FROM conversations WHERE archived = 0 ORDER BY pinned DESC, updatedAt DESC")
    abstract fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE archived = 1 ORDER BY updatedAt DESC")
    abstract fun observeArchived(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    abstract fun observeById(id: Long): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    abstract suspend fun findById(id: Long): ConversationEntity?

    @Query("SELECT id FROM conversations ORDER BY updatedAt DESC LIMIT 1")
    abstract suspend fun mostRecentId(): Long?

    @Query("SELECT COUNT(*) FROM conversations WHERE archived = 0")
    abstract fun observeCount(): Flow<Int>

    /** Total likes across every conversation — shown on the history screen. */
    @Query("SELECT COALESCE(SUM(likeCount), 0) FROM conversations")
    abstract fun observeTotalLikes(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(conversation: ConversationEntity): Long

    @Update
    abstract suspend fun update(conversation: ConversationEntity)

    @Delete
    abstract suspend fun delete(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    abstract suspend fun deleteById(id: Long)

    @Query("UPDATE conversations SET updatedAt = :timestamp WHERE id = :id")
    abstract suspend fun touch(id: Long, timestamp: Long)

    @Query("UPDATE conversations SET title = :title, updatedAt = :timestamp WHERE id = :id")
    abstract suspend fun rename(id: Long, title: String, timestamp: Long)

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    abstract suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE conversations SET archived = :archived WHERE id = :id")
    abstract suspend fun setArchived(id: Long, archived: Boolean)

    @Query(
        "UPDATE conversations SET modelPath = :path, modelName = :name, updatedAt = :timestamp " +
            "WHERE id = :id",
    )
    abstract suspend fun setModel(id: Long, path: String?, name: String?, timestamp: Long)

    @Query(
        "UPDATE conversations SET systemPrompt = :systemPrompt, skillId = :skillId, " +
            "updatedAt = :timestamp WHERE id = :id",
    )
    abstract suspend fun setPrompt(id: Long, systemPrompt: String, skillId: String?, timestamp: Long)

    @Query(
        "UPDATE conversations SET messageCount = :messages, likeCount = :likes, " +
            "tokenCount = :tokens WHERE id = :id",
    )
    abstract suspend fun setStats(id: Long, messages: Int, likes: Int, tokens: Int)

    @Query("DELETE FROM conversations WHERE archived = 1")
    abstract suspend fun deleteArchived(): Int
}

@Dao
abstract class MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    abstract fun observeFor(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    abstract suspend fun listFor(conversationId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    abstract suspend fun findById(id: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE id = :id")
    abstract fun observeById(id: Long): Flow<MessageEntity?>

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId " +
            "ORDER BY createdAt DESC, id DESC LIMIT 1",
    )
    abstract suspend fun lastIn(conversationId: Long): MessageEntity?

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND role = :role " +
            "ORDER BY createdAt DESC, id DESC LIMIT 1",
    )
    abstract suspend fun lastWithRole(conversationId: Long, role: String): MessageEntity?

    @Insert
    abstract suspend fun insert(message: MessageEntity): Long

    /** Append streamed text without rewriting the rest of the row. */
    @Query("UPDATE messages SET content = :content WHERE id = :id")
    abstract suspend fun setContent(id: Long, content: String)

    @Query("UPDATE messages SET thinkingText = :thinking WHERE id = :id")
    abstract suspend fun setThinking(id: Long, thinking: String?)

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    abstract suspend fun setStatus(id: Long, status: String)

    @Query(
        "UPDATE messages SET status = :status, promptTokens = :promptTokens, " +
            "tokensGenerated = :tokensGenerated, microseconds = :microseconds, " +
            "truncated = :truncated, cancelled = :cancelled, errorText = :errorText WHERE id = :id",
    )
    abstract suspend fun finish(
        id: Long,
        status: String,
        promptTokens: Int,
        tokensGenerated: Int,
        microseconds: Long,
        truncated: Boolean,
        cancelled: Boolean,
        errorText: String?,
    )

    /** The like toggle. `likeCount` follows `liked` so both stay consistent. */
    @Query(
        "UPDATE messages SET liked = :liked, " +
            "likeCount = CASE WHEN :liked THEN 1 ELSE 0 END WHERE id = :id",
    )
    abstract suspend fun setLiked(id: Long, liked: Boolean)

    @Query("UPDATE messages SET flagged = :flagged WHERE id = :id")
    abstract suspend fun setFlagged(id: Long, flagged: Boolean)

    @Query("SELECT COALESCE(SUM(likeCount), 0) FROM messages WHERE conversationId = :conversationId")
    abstract suspend fun likesIn(conversationId: Long): Int

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    abstract suspend fun countIn(conversationId: Long): Int

    @Query("SELECT COALESCE(SUM(tokensGenerated), 0) FROM messages WHERE conversationId = :conversationId")
    abstract suspend fun tokensIn(conversationId: Long): Int

    @Query("SELECT COALESCE(SUM(likeCount), 0) FROM messages")
    abstract suspend fun totalLikes(): Int

    /** "Delete from here": drops this message and everything after it. */
    @Query(
        "DELETE FROM messages WHERE conversationId = :conversationId AND " +
            "(createdAt > :timestamp OR (createdAt = :timestamp AND id >= :id))",
    )
    abstract suspend fun deleteFrom(conversationId: Long, timestamp: Long, id: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    abstract suspend fun deleteById(id: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    abstract suspend fun clear(conversationId: Long)

    @Transaction
    open suspend fun recomputeStats(conversationId: Long): Triple<Int, Int, Int> {
        val count = countIn(conversationId)
        val likes = likesIn(conversationId)
        val tokens = tokensIn(conversationId)
        return Triple(count, likes, tokens)
    }
}

@Dao
abstract class ModelDao {

    @Query("SELECT * FROM models ORDER BY lastUsedAt DESC, addedAt DESC")
    abstract fun observeAll(): Flow<List<LocalModelEntity>>

    @Query("SELECT * FROM models WHERE valid = 1 ORDER BY lastUsedAt DESC, addedAt DESC")
    abstract fun observeLoadable(): Flow<List<LocalModelEntity>>

    @Query("SELECT * FROM models WHERE path = :path")
    abstract fun observeByPath(path: String): Flow<LocalModelEntity?>

    @Query("SELECT * FROM models WHERE path = :path")
    abstract suspend fun findByPath(path: String): LocalModelEntity?

    /** One-shot snapshot; used by the directory scan to drop rows whose file is gone. */
    @Query("SELECT * FROM models")
    abstract suspend fun listAll(): List<LocalModelEntity>

    @Query("SELECT COUNT(*) FROM models")
    abstract fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(model: LocalModelEntity)

    @Update
    abstract suspend fun update(model: LocalModelEntity)

    @Query("DELETE FROM models WHERE path = :path")
    abstract suspend fun deleteByPath(path: String)

    @Query("UPDATE models SET lastUsedAt = :timestamp, useCount = useCount + 1 WHERE path = :path")
    abstract suspend fun markUsed(path: String, timestamp: Long)

    @Query(
        "UPDATE models SET nCtx = :nCtx, threads = :threads, batch = :batch, " +
            "gpuLayers = :gpuLayers, useMmap = :useMmap, kvType = :kvType WHERE path = :path",
    )
    abstract suspend fun updateEngineSettings(
        path: String,
        nCtx: Int,
        threads: Int,
        batch: Int,
        gpuLayers: Int,
        useMmap: Boolean,
        kvType: String,
    )

    @Query(
        "UPDATE models SET temperature = :temperature, topP = :topP, topK = :topK, " +
            "minP = :minP, repeatPenalty = :repeatPenalty, repeatLastN = :repeatLastN, " +
            "maxTokens = :maxTokens, seed = :seed WHERE path = :path",
    )
    abstract suspend fun updateSampling(
        path: String,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        repeatLastN: Int,
        maxTokens: Int,
        seed: Int,
    )

    @Query("UPDATE models SET displayName = :name WHERE path = :path")
    abstract suspend fun rename(path: String, name: String)
}

@Dao
abstract class SkillDao {

    @Query("SELECT * FROM skills ORDER BY builtin DESC, sortOrder ASC, name ASC")
    abstract fun observeAll(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills WHERE slug = :slug")
    abstract suspend fun findBySlug(slug: String): SkillEntity?

    @Query("SELECT * FROM skills WHERE slug = :slug")
    abstract fun observeBySlug(slug: String): Flow<SkillEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(skill: SkillEntity): Long

    @Update
    abstract suspend fun update(skill: SkillEntity)

    @Query("DELETE FROM skills WHERE id = :id AND builtin = 0")
    abstract suspend fun deleteById(id: Long)

    @Query("DELETE FROM skills WHERE slug = :slug AND builtin = 0")
    abstract suspend fun deleteBySlug(slug: String)

    @Query("UPDATE skills SET useCount = useCount + 1 WHERE slug = :slug")
    abstract suspend fun incrementUse(slug: String)

    @Query("SELECT COUNT(*) FROM skills WHERE builtin = 1")
    abstract suspend fun builtinCount(): Int

    /** Seeds built-ins once; user edits to a built-in are never overwritten. */
    @Transaction
    open suspend fun seedBuiltins(skills: List<SkillEntity>) {
        for (skill in skills) {
            val existing = findBySlug(skill.slug)
            if (existing == null) upsert(skill)
        }
    }
}
