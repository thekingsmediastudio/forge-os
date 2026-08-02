package com.forge.os.presentation.screens.onboarding

import androidx.lifecycle.ViewModel
import com.forge.os.domain.permissions.PermissionItem
import com.forge.os.domain.permissions.PermissionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class PermissionOnboardingViewModel @Inject constructor(
    private val permissionManager: PermissionManager
) : ViewModel() {

    private val _permissions = MutableStateFlow<List<PermissionItem>>(emptyList())
    val permissions: StateFlow<List<PermissionItem>> = _permissions.asStateFlow()

    private val _allRequiredGranted = MutableStateFlow(false)
    val allRequiredGranted: StateFlow<Boolean> = _allRequiredGranted.asStateFlow()

    init {
        refreshPermissions()
    }

    fun refreshPermissions() {
        permissionManager.refreshPermissionStates()
        _permissions.value = permissionManager.getPermissionsWithStates()
        _allRequiredGranted.value = permissionManager.areRequiredPermissionsGranted()
    }

    fun getPermissionsToRequest(): Array<String> {
        return permissionManager.getPermissionsToRequest()
    }

    fun areRequiredPermissionsGranted(): Boolean {
        return permissionManager.areRequiredPermissionsGranted()
    }

    fun markComplete() {
        permissionManager.markPermissionsRequested()
    }
}
