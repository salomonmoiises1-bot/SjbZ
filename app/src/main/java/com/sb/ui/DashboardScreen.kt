package com.sb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sb.ui.components.SignalChainBar
import com.sb.ui.components.VisualizerMeter

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val config by viewModel.config.collectAsState()
    val currentPreset by viewModel.currentPreset.collectAsState()
    val peakDb by viewModel.peakDb.collectAsState()
    val rmsDb by viewModel.rmsDb.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF020617))
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // DSP Status & Master Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "SB AUDIO DSP", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color.White)
                Text(text = "Target SDK 35 • Android DynamicsProcessing • Session 0", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B))
            }
            Button(
                onClick = { viewModel.toggleMaster() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (config.masterEnabled) Color(0xFF06B6D4) else Color(0xFF334155),
                    contentColor = if (config.masterEnabled) Color(0xFF020617) else Color(0xFFE2E8F0)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(text = if (config.masterEnabled) "DSP ACTIVO" else "BYPASS", fontWeight = FontWeight.Bold)
            }
        }

        // Cadena DSP visual
        SignalChainBar(config = config, onNavigate = onNavigate)

        val presets = viewModel.availablePresets()
        if (presets.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("PRESETS", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.weight(1f))
                presets.take(3).forEach { name -> OutlinedButton(onClick = { viewModel.loadPreset(name) }) { Text(name, fontSize = 10.sp) } }
            }
        }

        // Medidor de señal & espectro
        VisualizerMeter(peakDb = peakDb, rmsDb = rmsDb, isDspActive = config.masterEnabled && viewModel.dspBackendActive)

        // Resumen EQ32
        Button(
            onClick = { onNavigate("EQ32") },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
        ) {
            Text(text = "ABRIR ECUALIZADOR (32 BANDAS) →", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
        }
    }
}