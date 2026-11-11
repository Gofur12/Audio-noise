package com.example.presetaudio

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class UiState(
    val config: DspConfig = Presets.balanced.config,
    val activeId: String? = Presets.balanced.id, // null = Custom
    val presets: List<Preset> = Presets.all,
    val caps: Capabilities = Capabilities(),
    val applyState: ApplyState = ApplyState.Idle
)

class PresetViewModel(app: Application) : AndroidViewModel(app) {
    private val engine = DspEngine(sessionId = 0)
    private val prefs = app.getSharedPreferences("presets", Context.MODE_PRIVATE)

    private val _ui = MutableStateFlow(UiState(caps = engine.caps, presets = Presets.all + loadUser()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { engine.status.collect { s -> _ui.update { it.copy(applyState = s) } } }

        val cfg = prefs.getString("last", null)
            ?.let { runCatching { DspConfig.fromJson(JSONObject(it)) }.getOrNull() }
            ?: Presets.balanced.config
        val id = _ui.value.presets.firstOrNull { it.id == prefs.getString("last_id", null) }?.id
        _ui.update { it.copy(config = cfg, activeId = id) }
        engine.apply(cfg)
    }

    /** Satu tap = seluruh konfigurasi DSP langsung diterapkan. */
    fun select(p: Preset) {
        engine.apply(p.config)
        _ui.update { it.copy(config = p.config, activeId = p.id) }
        persist()
    }

    fun reset() = select(Presets.balanced)

    /** Perubahan manual dari Advanced. Otomatis jadi Custom kecuali cocok persis dengan sebuah preset. */
    fun edit(transform: (DspConfig) -> DspConfig) {
        val next = transform(_ui.value.config)
        engine.apply(next, smooth = false)
        _ui.update { s -> s.copy(config = next, activeId = s.presets.firstOrNull { it.config == next }?.id) }
        persist()
    }

    fun saveAsPreset(name: String) {
        val p = Preset("user_${System.currentTimeMillis()}", name.trim().ifEmpty { "Custom" },
            "Preset buatan sendiri", _ui.value.config, builtIn = false)
        _ui.update { it.copy(presets = it.presets + p) }
        prefs.edit().putString("user", JSONArray(_ui.value.presets.filter { !it.builtIn }.map { it.toJson() }).toString()).apply()
        select(p)
    }

    private fun persist() {
        val s = _ui.value
        prefs.edit().putString("last", s.config.toJson().toString()).putString("last_id", s.activeId).apply()
    }

    private fun loadUser(): List<Preset> = runCatching {
        val a = JSONArray(prefs.getString("user", "[]"))
        List(a.length()) { Preset.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    override fun onCleared() = engine.release()
}
