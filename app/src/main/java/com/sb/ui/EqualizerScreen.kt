package com.sb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.sb.dsp.DspConfig

@Composable
fun EqualizerScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val config by viewModel.config.collectAsState()
    val eqPresets = viewModel.availableEqPresets()
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val frequencies = when (config.eqMode) {
        DspConfig.EqMode.BANDS_10 -> DspConfig.FREQUENCIES_10
        DspConfig.EqMode.BANDS_20 -> DspConfig.FREQUENCIES_20
        DspConfig.EqMode.BANDS_32 -> DspConfig.FREQUENCIES_32
    }
    val gains = config.activeGains()

    Column(modifier.fillMaxSize().background(Color(0xFF020617)).padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ECUALIZADOR", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Text("EQ lógico 10 / 20 / 32 bandas • curva aplicada al backend DSP",
                    color = Color(0xFF94A3B8), fontSize = 12.sp)
            }
            Switch(checked = config.eqEnabled, onCheckedChange = viewModel::setEqEnabled)
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DspConfig.EqMode.values().forEach { mode ->
                FilterChip(
                    selected = config.eqMode == mode,
                    onClick = { viewModel.setEqMode(mode) },
                    label = { Text(mode.bandCount.toString()) }
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { presetName = ""; showSaveDialog = true }, modifier = Modifier.weight(1f)) {
                Text("Guardar EQ")
            }
            if (eqPresets.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Cargar EQ")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        eqPresets.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = { viewModel.loadEqPreset(name); expanded = false }
                            )
                        }
                    }
                }
            }
        }

        if (eqPresets.isNotEmpty()) {
            Text(
                "Presets EQ: ${eqPresets.joinToString(" • ")}",
                color = Color(0xFF64748B), fontSize = 10.sp,
                modifier = Modifier.padding(top = 5.dp)
            )
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(scroll).padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            frequencies.forEachIndexed { i, freq ->
                var value by remember(config.eqMode, gains.getOrElse(i) { 0f }) {
                    mutableFloatStateOf(gains.getOrElse(i) { 0f })
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(36.dp)
                ) {
                    Text("${value.roundToInt()}", color = Color(0xFFCBD5E1), fontSize = 9.sp)
                    Box(Modifier.height(220.dp).width(36.dp), contentAlignment = Alignment.Center) {
                        Slider(
                            value = value,
                            onValueChange = {
                                value = it
                                viewModel.setEqBand(i, it)
                            },
                            valueRange = -12f..12f,
                            modifier = Modifier.width(220.dp).rotate(-90f)
                        )
                    }
                    Text(formatFreq(freq), color = Color(0xFF94A3B8), fontSize = 8.sp, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Respuesta lógica ±12 dB • 10 / 20 / 32 conservan su propia curva y preset",
            color = Color(0xFF64748B), fontSize = 10.sp
        )
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Guardar preset de EQ") },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text("Nombre") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )
            },
            confirmButton = {
                TextButton(
                    enabled = presetName.trim().isNotEmpty(),
                    onClick = {
                        viewModel.saveEqPreset(presetName.trim())
                        showSaveDialog = false
                    }
                ) { Text("GUARDAR") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("CANCELAR") }
            }
        )
    }
}

private fun formatFreq(f: Float) = when {
    f >= 1000f -> if (f % 1000f == 0f) "${(f / 1000f).toInt()}k" else "${"%.1f".format(f / 1000f)}k"
    else -> f.toInt().toString()
}
