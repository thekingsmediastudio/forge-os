package com.forge.os.presentation.screens.cost

import androidx.lifecycle.ViewModel
import com.forge.os.data.api.CostMeter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class CostStatsViewModel @Inject constructor(
    private val costMeter: CostMeter,
) : ViewModel() {

    val snapshot: StateFlow<CostMeter.CostSnapshot> = costMeter.snapshot
    val prices: StateFlow<Map<String, CostMeter.PricePoint>> = costMeter.prices

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun setPrice(model: String, input: Double, output: Double) {
        if (model.isBlank()) { _message.value = "Model name required"; return }
        costMeter.setPrice(model.trim(), input, output)
        _message.value = "Saved $model"
    }

    fun removePrice(model: String) {
        costMeter.removePrice(model)
        _message.value = "Removed $model"
    }

    fun resetSession() {
        costMeter.resetSession(); _message.value = "Session reset"
    }

    fun resetLifetime() {
        costMeter.resetLifetime(); _message.value = "Lifetime totals cleared"
    }

    fun dismissMessage() { _message.value = null }
}
