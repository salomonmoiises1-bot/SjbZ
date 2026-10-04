package com.sb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sb.dsp.DspConfig
import kotlin.math.max

@Composable
fun DynamicsScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val c by viewModel.config.collectAsState()
    val maxCrossover = minOf(22000f, c.sampleRate * 0.49f).coerceAtLeast(80f)
    val lowMax = (c.mdrcMidCrossoverHz - 20f).coerceAtLeast(20f)
    val midMin = (c.mdrcLowCrossoverHz + 20f).coerceAtMost(maxCrossover - 40f)
    val midMax = (c.mdrcHighCrossoverHz - 20f).coerceAtLeast(midMin)
    val highMin = (c.mdrcMidCrossoverHz + 20f).coerceAtMost(maxCrossover - 20f)

    Column(
        modifier.fillMaxSize().background(Color(0xFF020617)).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("MDRC / DYNAMICS", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Text("4 bandas MBC • crossovers ${"%.0f".format(c.mdrcLowCrossoverHz)} / ${"%.0f".format(c.mdrcMidCrossoverHz)} / ${"%.0f".format(c.mdrcHighCrossoverHz)} Hz", color = Color(0xFF94A3B8), fontSize = 12.sp)
        SwitchRow("MDRC", "DynamicsProcessing MBC real", c.mdrcEnabled, viewModel::setMdrcEnabled)

        DspSlider("Crossover 1", c.mdrcLowCrossoverHz, 20f..lowMax, "%.0f Hz", { viewModel.setMdrcLow(it) })
        DspSlider("Crossover 2", c.mdrcMidCrossoverHz, midMin..midMax, "%.0f Hz", { viewModel.setMdrcMid(it) })
        DspSlider("Crossover 3", c.mdrcHighCrossoverHz, highMin..maxCrossover, "%.0f Hz", { viewModel.setMdrcHigh(it) })

        BandCard("LOW", c.mdrcLowBand) { viewModel.setMdrcLowBand(it) }
        BandCard("LOW-MID", c.mdrcMidBand) { viewModel.setMdrcMidBand(it) }
        BandCard("HIGH-MID", c.mdrcHighBand) { viewModel.setMdrcHighBand(it) }
        BandCard("HIGH", c.mdrcUltraBand) { viewModel.setMdrcUltraBand(it) }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, on: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) { Text(title, color = Color.White); Text(sub, color = Color(0xFF64748B), fontSize = 11.sp) }
        Switch(checked = checked, onCheckedChange = on)
    }

@Composable
private fun DspSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: String, on: (Float) -> Unit) {
    val safeStart = range.start
    val safeEnd = max(range.start + 0.01f, range.endInclusive)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color(0xFFCBD5E1))
            Text(fmt.format(value), color = Color(0xFF67E8F9), fontSize = 12.sp)
        }
        Slider(value = value.coerceIn(safeStart, safeEnd), onValueChange = on, valueRange = safeStart..safeEnd)
    }
}

@Composable
private fun BandCard(name: String, b: DspConfig.BandCompressorConfig, onChange: (DspConfig.BandCompressorConfig) -> Unit) =
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name, color = Color.White)
                Text("${"%.1f".format(b.ratio)}:1", color = Color(0xFF67E8F9))
            }
            BandSlider("Threshold", b.thresholdDb, -60f..0f, "%.1f dB") { onChange(b.copy(thresholdDb = it)) }
            BandSlider("Ratio", b.ratio, 1f..20f, "%.1f:1") { onChange(b.copy(ratio = it)) }
            BandSlider("Attack", b.attackMs, 0.1f..200f, "%.1f ms") { onChange(b.copy(attackMs = it)) }
            BandSlider("Release", b.releaseMs, 1f..1000f, "%.0f ms") { onChange(b.copy(releaseMs = it)) }
            BandSlider("Makeup", b.makeupGainDb, -12f..12f, "%.1f dB") { onChange(b.copy(makeupGainDb = it)) }
        }
    }

@Composable
private fun BandSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: String, on: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color(0xFF94A3B8), fontSize = 11.sp)
            Text(fmt.format(value), color = Color(0xFF67E8F9), fontSize = 11.sp)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = on, valueRange = range)
    }
}
