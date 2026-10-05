package com.sb.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PresetManagerCard(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val current by viewModel.currentPreset.collectAsState()
    val names = viewModel.availablePresets()
    var expanded by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("PRESETS DEL MOTOR", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(current, maxLines = 1)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    names.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset) },
                            onClick = {
                                viewModel.loadPreset(preset)
                                expanded = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { name = ""; showSave = true }) { Text("Guardar") }
        }

        Text(
            "El preset guarda toda la configuración del motor: EQ 10/20/32, Pre-Gain, Bass Boost, Tone, MDRC, Limiter, AutoGain, Master, Balance y Spatial/Virtualizer.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )

        if (!viewModel.isFactoryPreset(current)) {
            TextButton(onClick = { viewModel.deletePreset(current) }) {
                Text("Eliminar preset personalizado")
            }
        }
    }

    if (showSave) {
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Guardar configuración completa") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre del preset") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.trim().isNotEmpty(),
                    onClick = {
                        viewModel.savePreset(name)
                        showSave = false
                    }
                ) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancelar") } }
        )
    }
}
