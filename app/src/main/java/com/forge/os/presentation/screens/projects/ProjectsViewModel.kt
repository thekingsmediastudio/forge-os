package com.forge.os.presentation.screens.projects

import androidx.lifecycle.ViewModel
import com.forge.os.domain.projects.Project
import com.forge.os.domain.projects.ProjectScopeManager
import com.forge.os.domain.projects.ProjectsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class ProjectsUiState(
    val projects: List<Project> = emptyList(),
    val active: Project? = null,
    val message: String? = null,
)

@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val repository: ProjectsRepository,
    private val scope: ProjectScopeManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ProjectsUiState())
    val state: StateFlow<ProjectsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        scope.refresh()
        _state.value = _state.value.copy(
            projects = repository.list(),
            active = scope.active.value,
        )
    }

    fun create(name: String, description: String) {
        val p = repository.create(name, description)
        if (_state.value.active == null) scope.setActive(p)
        _state.value = _state.value.copy(message = "Created '${p.name}'")
        refresh()
    }

    fun activate(p: Project?) {
        scope.setActive(p)
        _state.value = _state.value.copy(message = if (p == null) "Cleared scope" else "Active: ${p.name}")
        refresh()
    }

    fun update(p: Project) { repository.save(p); refresh() }

    fun delete(slug: String) {
        if (_state.value.active?.slug == slug) scope.setActive(null)
        repository.delete(slug)
        _state.value = _state.value.copy(message = "Deleted $slug")
        refresh()
    }

    fun fileCount(slug: String): Int = repository.fileCount(slug)

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }
}
