package com.offlinegguf.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.offlinegguf.chat.AppContainer
import com.offlinegguf.chat.data.local.SkillEntity
import com.offlinegguf.chat.data.repository.SkillRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SkillsUiState(
    val skills: List<SkillEntity> = emptyList(),
    val draft: SkillEntity? = null,
    val isNew: Boolean = false,
    val grammarError: String? = null,
    val error: String? = null,
    val notice: String? = null,
    val busy: Boolean = false,
) {
    val dirty: Boolean
        get() {
            val draft = draft ?: return false
            val original = skills.firstOrNull { it.slug == draft.slug }
            return original == null ||
                original.name != draft.name ||
                original.description != draft.description ||
                original.systemPrompt != draft.systemPrompt ||
                original.userTemplate != draft.userTemplate ||
                original.grammar != draft.grammar ||
                original.temperature != draft.temperature ||
                original.topP != draft.topP ||
                original.maxTokens != draft.maxTokens
        }
}

/**
 * Skills are reusable prompt presets: a system prompt, an optional user-message
 * template, optional sampling overrides and an optional GBNF grammar that forces
 * the output shape (JSON, a single fenced code block, …).
 *
 * Editing a built-in never touches the seed data in code — [resetBuiltins]
 * restores it, and user edits survive because the seeder only inserts missing
 * slugs.
 */
class SkillsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SkillsUiState())
    val state: StateFlow<SkillsUiState> = _state.asStateFlow()

    private val skills = container.skillRepository

    init {
        viewModelScope.launch { skills.seedBuiltins() }
        viewModelScope.launch {
            skills.observeAll().collect { rows ->
                _state.update { it.copy(skills = rows) }
            }
        }
    }

    fun startNew() {
        _state.update {
            it.copy(
                draft = SkillEntity(slug = "", name = "", description = ""),
                isNew = true,
                grammarError = null,
                error = null,
            )
        }
    }

    fun startEdit(skill: SkillEntity) {
        _state.update { it.copy(draft = skill, isNew = false, grammarError = null, error = null) }
    }

    fun cancelEdit() = _state.update { it.copy(draft = null, grammarError = null) }

    private fun mutate(change: SkillEntity.() -> SkillEntity) = _state.update { current ->
        val draft = current.draft ?: return@update current
        current.copy(draft = draft.change())
    }

    fun setSlug(value: String) = mutate {
        copy(slug = value.trim().lowercase().replace(' ', '-').replace(Regex("[^a-z0-9\\-]"), ""))
    }

    fun setName(value: String) = mutate { copy(name = value) }

    fun setDescription(value: String) = mutate { copy(description = value) }

    fun setSystemPrompt(value: String) = mutate { copy(systemPrompt = value) }

    fun setUserTemplate(value: String) = mutate { copy(userTemplate = value) }

    fun setGrammar(value: String) = mutate {
        copy(grammar = value.ifBlank { null })
    }

    fun setTemperature(value: Float?) = mutate { copy(temperature = value) }

    fun setTopP(value: Float?) = mutate { copy(topP = value) }

    fun setMaxTokens(value: Int?) = mutate { copy(maxTokens = value) }

    fun clearSamplingOverrides() = mutate { copy(temperature = null, topP = null, maxTokens = null) }

    /** Validates the grammar before saving so the failure is explainable. */
    fun validateGrammar() {
        val grammar = _state.value.draft?.grammar
        _state.update { it.copy(grammarError = SkillRepository.validateGrammar(grammar)) }
    }

    fun save() {
        val draft = _state.value.draft ?: return
        val slug = draft.slug.ifBlank { draft.name.trim().lowercase().replace(' ', '-') }
        if (slug.isBlank()) {
            _state.update { it.copy(error = "A skill needs a name") }
            return
        }
        val name = draft.name.ifBlank { slug }
        val existing = _state.value.skills.firstOrNull { it.slug == slug }
        if (_state.value.isNew && existing != null) {
            _state.update { it.copy(error = "A skill with the slug \"$slug\" already exists") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val grammarError = skills.save(
                draft.copy(
                    slug = slug,
                    name = name,
                    id = existing?.id ?: 0,
                    builtin = existing?.builtin ?: false,
                    sortOrder = existing?.sortOrder ?: (_state.value.skills.size + 1),
                ),
            )
            _state.update {
                it.copy(
                    busy = false,
                    grammarError = grammarError,
                    error = if (grammarError == null) null else "Grammar rejected — the skill was not saved",
                    draft = if (grammarError == null) null else it.draft,
                    isNew = if (grammarError == null) false else it.isNew,
                    notice = if (grammarError == null) "Saved \"$name\"" else null,
                )
            }
        }
    }

    fun delete(slug: String) {
        viewModelScope.launch {
            skills.delete(slug)
            _state.update { it.copy(notice = "Deleted \"$slug\"", draft = null) }
        }
    }

    fun resetBuiltins() {
        viewModelScope.launch {
            skills.resetBuiltins()
            _state.update { it.copy(notice = "Built-in skills restored") }
        }
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SkillsViewModel(container) }
        }
    }
}
