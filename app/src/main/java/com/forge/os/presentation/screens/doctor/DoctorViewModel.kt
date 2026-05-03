package com.forge.os.presentation.screens.doctor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.doctor.DoctorReport
import com.forge.os.domain.doctor.DoctorService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DoctorViewModel @Inject constructor(
    private val service: DoctorService,
) : ViewModel() {

    private val _report = MutableStateFlow<DoctorReport?>(null)
    val report: StateFlow<DoctorReport?> = _report

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    init { runChecks() }

    fun runChecks() {
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            _report.value = service.runChecks()
            _busy.value = false
        }
    }

    fun fix(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            service.fix(id)
            _report.value = service.runChecks()
            _busy.value = false
        }
    }
}
