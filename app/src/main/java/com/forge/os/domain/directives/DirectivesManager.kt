package com.forge.os.domain.directives

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectivesManager @Inject constructor(
    private val repository: DirectivesRepository
) {
    /**
     * Build the prompt section for all active directives relevant to the current scope.
     */
    fun buildRulesPrompt(scope: String? = null): String {
        val active = repository.getAll().filter { it.enabled }
        if (active.isEmpty()) return ""

        val global = active.filter { it.scope == "global" }
        val scoped = if (scope != null) active.filter { it.scope == scope } else emptyList()
        
        val effective = (global + scoped).distinctBy { it.content.lowercase().trim() }
        if (effective.isEmpty()) return ""

        return buildString {
            appendLine("── AGENT DIRECTIVES (MANDATORY RULES) ──")
            effective.forEachIndexed { index, directive ->
                appendLine("${index + 1}. ${directive.content}")
            }
            appendLine("These directives take precedence over general system instructions.")
        }
    }

    fun addRule(content: String, category: String = "behavior", scope: String = "global") {
        repository.add(AgentDirective(content = content, category = category, scope = scope))
        Timber.i("Directive added: $content ($scope)")
    }

    fun listRules(): List<AgentDirective> {
        return repository.getAll()
    }

    fun deleteRule(id: String) {
        repository.remove(id)
    }

    fun toggleRule(id: String, enabled: Boolean) {
        repository.toggle(id, enabled)
    }
}
