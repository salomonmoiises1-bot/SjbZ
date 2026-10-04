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

@Composable
fun DynamicsScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val c by viewModel.config.collectAsState()
    Column(modifier.fillMaxSize().background(Color(0xFF020617)).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("MDRC / DYNAMICS", color=Color.White, style=MaterialTheme.typography.headlineSmall)
        Text("4 bandas MBC • crossovers 160 / 800 / 4000 Hz", color=Color(0xFF94A3B8), fontSize=12.sp)
        SwitchRow("MDRC", "DynamicsProcessing MBC real", c.mdrcEnabled, viewModel::setMdrcEnabled)
        DspSlider("Crossover 1", c.mdrcLowCrossoverHz, 40f..1000f, "%.0f Hz", viewModel::setMdrcLow)
        DspSlider("Crossover 2", c.mdrcMidCrossoverHz, 200f..3000f, "%.0f Hz", viewModel::setMdrcMid)
        DspSlider("Crossover 3", c.mdrcHighCrossoverHz, 1000f..18000f, "%.0f Hz", viewModel::setMdrcHigh)
        BandCard("LOW", c.mdrcLowBand)
        BandCard("LOW-MID", c.mdrcMidBand)
        BandCard("HIGH-MID", c.mdrcHighBand)
        BandCard("HIGH", c.mdrcUltraBand)
    }
}
@Composable private fun SwitchRow(title:String, sub:String, checked:Boolean, on:(Boolean)->Unit)=Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Column(Modifier.weight(1f)){Text(title,color=Color.White);Text(sub,color=Color(0xFF64748B),fontSize=11.sp)};Switch(checked=checked,onCheckedChange=on)}
@Composable private fun DspSlider(title:String,value:Float,range:ClosedFloatingPointRange<Float>,fmt:String,on:(Float)->Unit){Column{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(title,color=Color(0xFFCBD5E1));Text(fmt.format(value),color=Color(0xFF67E8F9),fontSize=12.sp)};Slider(value=value.coerceIn(range.start,range.endInclusive),onValueChange=on,valueRange=range)}}
@Composable private fun BandCard(name:String,b:DspConfig.BandCompressorConfig)=Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF0B1220))){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(name,color=Color.White);Text("${"%.1f".format(b.ratio)}:1",color=Color(0xFF67E8F9))};Text("Threshold ${"%.1f".format(b.thresholdDb)} dB • Attack ${"%.1f".format(b.attackMs)} ms • Release ${"%.0f".format(b.releaseMs)} ms",color=Color(0xFF94A3B8),fontSize=11.sp)}}
