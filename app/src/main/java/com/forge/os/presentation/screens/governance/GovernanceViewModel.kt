package com.forge.os.presentation.screens.governance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.governance.AuthorizedCaller
import com.forge.os.domain.governance.CallerRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GovernanceViewModel @Inject constructor(
    private val registry: CallerRegistry
) : ViewModel() {

    val authorizedCallers: StateFlow<List<AuthorizedCaller>> = registry.callers

    fun grantPermission(packageName: String, permission: String) {
        viewModelScope.launch {
            registry.grant(packageName, permission)
        }
    }

    fun revokeCaller(packageName: String) {
        viewModelScope.launch {
            registry.revoke(packageName)
        }
    }
}
