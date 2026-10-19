package com.offlinegguf.chat.data.repository

import com.offlinegguf.chat.data.local.AppDatabase
import com.offlinegguf.chat.data.local.SkillEntity
import com.offlinegguf.chat.domain.skills.BuiltinSkills
import com.offlinegguf.chat.domain.skills.Gbnf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Skills: prompt + sampling + optional GBNF grammar presets.
 *
 * Built-ins are seeded once and never overwritten afterwards, so a user who
 * edits "Summarise" keeps their version across app updates.
 */
class SkillRepository(db: AppDatabase) {

    private val dao = db.skills()

    fun observeAll(): Flow<List<SkillEntity>> = dao.observeAll()

    fun observeBySlug(slug: String): Flow<SkillEntity?> = dao.observeBySlug(slug)

    suspend fun bySlug(slug: String?): SkillEntity? =
        if (slug.isNullOrBlank()) null else withContext(Dispatchers.IO) { dao.findBySlug(slug) }

    suspend fun seedBuiltins() = withContext(Dispatchers.IO) {
        dao.seedBuiltins(BuiltinSkills.all())
    }

    /**
     * Creates or updates a skill. Returns null on success, or the first grammar
     * problem when a GBNF body was supplied and does not validate.
     */
    suspend fun save(skill: SkillEntity): String? {
        val grammarError = validateGrammar(skill.grammar)
        if (grammarError != null) return grammarError
        return withContext(Dispatchers.IO) {
            val existing = dao.findBySlug(skill.slug)
            val id = if (existing != null) existing.id else 0L
            dao.upsert(skill.copy(id = id, updatedAt = System.currentTimeMillis()))
            null
        }
    }

    suspend fun delete(slug: String) = withContext(Dispatchers.IO) { dao.deleteBySlug(slug) }

    suspend fun markUsed(slug: String) = withContext(Dispatchers.IO) { dao.incrementUse(slug) }

    /** Restores every built-in to its shipped text. User skills are untouched. */
    suspend fun resetBuiltins() = withContext(Dispatchers.IO) {
        for (skill in BuiltinSkills.all()) {
            val existing = dao.findBySlug(skill.slug)
            if (existing != null) dao.update(skill.copy(id = existing.id, useCount = existing.useCount))
            else dao.upsert(skill)
        }
    }

    companion object {
        /** Null when the grammar is usable; otherwise the first problem, in plain words. */
        fun validateGrammar(grammar: String?): String? {
            if (grammar.isNullOrBlank()) return null
            val result = Gbnf.validate(grammar)
            return if (result.valid) null else result.errors.firstOrNull()
        }

        /** Short label for the skill chip in the input bar. */
        fun chipLabel(skill: SkillEntity?): String = skill?.name ?: "No skill"
    }
}
