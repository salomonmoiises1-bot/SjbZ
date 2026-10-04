package com.sb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sb.dsp.DspConfig

@Composable
fun SignalChainBar(config: DspConfig, onNavigate: (String) -> Unit) {
    val stages = listOf(
        "PRE" to config.pregainEnabled,
        "BASS" to config.bassBoostEnabled,
        "TONE" to config.toneEnabled,
        "EQ32" to config.eqEnabled,
        "MDRC" to config.mdrcEnabled,
        "AG" to config.autoGainEnabled,
        "LIMIT" to config.headroomEnabled,
        "SPATIAL" to config.virtualizerEnabled
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("CADENA DSP", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            stages.forEach { (label, enabled) ->
                Box(
                    Modifier.weight(1f).height(42.dp)
                        .border(1.dp, if (enabled) Color(0xFF22D3EE) else Color(0xFF334155), RoundedCornerShape(8.dp))
                        .background(if (enabled) Color(0xFF083344) else Color(0xFF0F172A), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) { Text(label, color = if (enabled) Color(0xFF67E8F9) else Color(0xFF64748B), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onNavigate("EQ32") }, modifier = Modifier.weight(1f)) { Text("EQ32") }
            OutlinedButton(onClick = { onNavigate("MDRC") }, modifier = Modifier.weight(1f)) { Text("MDRC") }
            OutlinedButton(onClick = { onNavigate("SALIDA") }, modifier = Modifier.weight(1f)) { Text("SALIDA") }
        }
    }
}

@Composable
fun VisualizerMeter(peakDb: Float, rmsDb: Float, isDspActive: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("MONITOR DE SEÑAL", color = Color.White, fontWeight = FontWeight.Bold)
                Text(if (isDspActive) "ACTIVO" else "BYPASS", color = if (isDspActive) Color(0xFF22D3EE) else Color(0xFFF59E0B), fontSize = 11.sp)
            }
            Text("PEAK ${"%.1f".format(peakDb)} dBFS", color = Color(0xFFCBD5E1), fontSize = 12.sp)
            LinearProgressIndicator(progress = ((peakDb + 60f) / 60f).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
            Text("RMS ${"%.1f".format(rmsDb)} dBFS", color = Color(0xFF94A3B8), fontSize = 11.sp)
        }
    }
}
