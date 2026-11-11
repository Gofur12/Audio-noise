@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.presetaudio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun HomeScreen(vm: PresetViewModel = viewModel()) {
    val s by vm.ui.collectAsState()
    var showAdvanced by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audio Preset") },
                actions = { TextButton(onClick = vm::reset) { Text("Reset") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            StatusBar(s)
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(s.presets, key = { it.id }) { p ->
                    PresetCard(p, selected = p.id == s.activeId) { vm.select(p) }
                }
            }
            OutlinedButton(
                onClick = { showAdvanced = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Advanced / Customize") }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showAdvanced) AdvancedSheet(s, vm) { showAdvanced = false }
}

@Composable
private fun StatusBar(s: UiState) {
    val title = s.presets.firstOrNull { it.id == s.activeId }?.name ?: "Custom"
    val sub = when (val a = s.applyState) {
        ApplyState.Idle -> "Siap"
        ApplyState.Applying -> "Menerapkan…"
        is ApplyState.Applied ->
            if (a.skipped.isEmpty()) "✓ Diterapkan"
            else "✓ Diterapkan · tidak didukung: ${a.skipped.joinToString()}"
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Aktif: $title", style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PresetCard(p: Preset, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) scheme.primaryContainer else scheme.surfaceVariant
        ),
        border = if (selected) BorderStroke(2.dp, scheme.primary) else null
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (selected) Text("●", color = scheme.primary)
            }
            Spacer(Modifier.height(4.dp))
            Text(p.desc, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AdvancedSheet(s: UiState, vm: PresetViewModel, onDismiss: () -> Unit) {
    var saving by remember { mutableStateOf(false) }
    val c = s.config
    val k = s.caps

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                if (s.activeId == null) "Custom" else "Advanced",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(8.dp))

            Labeled("Bass", c.bass, -6f..6f, k.equalizer) { v -> vm.edit { it.copy(bass = v) } }
            Labeled("Treble", c.treble, -6f..6f, k.equalizer) { v -> vm.edit { it.copy(treble = v) } }
            Labeled("Vocal", c.vocal, -6f..6f, k.equalizer) { v -> vm.edit { it.copy(vocal = v) } }
            Labeled("Clarity", c.clarity, -6f..6f, k.equalizer) { v -> vm.edit { it.copy(clarity = v) } }

            Text("Equalizer", style = MaterialTheme.typography.titleSmall)
            c.eq.forEachIndexed { i, v ->
                Labeled(hzLabel(Curve.REF_HZ[i]), v, -12f..12f, k.equalizer) { nv ->
                    vm.edit { cfg -> cfg.copy(eq = cfg.eq.toMutableList().also { l -> l[i] = nv }) }
                }
            }

            Labeled("Stereo width", c.stereoWidth, 0f..1f, k.stereoWidth) { v -> vm.edit { it.copy(stereoWidth = v) } }
            Labeled("Compressor", c.compressor, 0f..1f, k.dynamics) { v -> vm.edit { it.copy(compressor = v) } }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Limiter" + if (!k.dynamics) " (tidak didukung)" else "",
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = c.limiterOn,
                    onCheckedChange = { on -> vm.edit { it.copy(limiterOn = on) } },
                    enabled = k.dynamics
                )
            }

            Spacer(Modifier.height(12.dp))
            Button(onClick = { saving = true }, modifier = Modifier.fillMaxWidth()) { Text("Save as Preset") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (saving) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Simpan sebagai preset") },
            text = {
                OutlinedTextField(name, { name = it }, label = { Text("Nama preset") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = { vm.saveAsPreset(name); saving = false; onDismiss() }) { Text("Simpan") }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Batal") } }
        )
    }
}

@Composable
private fun Labeled(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label + if (!enabled) " (tidak didukung)" else "")
            Text("%.1f".format(value))
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled)
    }
}

private fun hzLabel(hz: Float) =
    if (hz >= 1000f) "${(hz / 1000f).toString().removeSuffix(".0")} kHz" else "${hz.toInt()} Hz"
