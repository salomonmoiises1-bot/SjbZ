package com.sb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun OutputScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val c by viewModel.config.collectAsState()
    Column(modifier.fillMaxSize().background(Color(0xFF020617)).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("SALIDA", color=Color.White, style=MaterialTheme.typography.headlineSmall)
        Text("Ganancia global, protección y espacialidad", color=Color(0xFF94A3B8), fontSize=12.sp)
        DspSlider("Pre-Gain",c.pregainDb,-24f..12f,"%.1f dB",false,viewModel::setPregain)
        DspSlider("Bass Boost",c.bassBoostStrength,0f..1f,"%.0f %%",true){viewModel.setBassBoost(it)}
        Text("TONE",color=Color(0xFF94A3B8),fontSize=11.sp)
        DspSlider("Graves",c.bassToneDb,-12f..12f,"%.1f dB",false,viewModel::setBassTone)
        DspSlider("Medios",c.midToneDb,-12f..12f,"%.1f dB",false,viewModel::setMidTone)
        DspSlider("Agudos",c.trebleToneDb,-12f..12f,"%.1f dB",false,viewModel::setTrebleTone)
        Divider(color=Color(0xFF1E293B))
        DspSwitch("AutoGain",c.autoGainEnabled,viewModel::setAutoGain)
        DspSlider("Objetivo AutoGain",c.autoGainTargetRmsDb,-30f..-6f,"%.1f dB",false,viewModel::setAutoGainTarget)
        DspSwitch("Limiter",c.limiterEnabled,viewModel::setLimiter)
        DspSlider("Threshold",c.limiterThresholdDb,-30f..0f,"%.1f dB",false,viewModel::setLimiterThreshold)
        DspSlider("Ratio",c.limiterRatio,1f..20f,"%.1f : 1",false,viewModel::setLimiterRatio)
        DspSlider("Attack",c.limiterAttackMs,0.1f..20f,"%.1f ms",false,viewModel::setLimiterAttack)
        DspSlider("Release",c.limiterReleaseMs,5f..300f,"%.0f ms",false,viewModel::setLimiterRelease)
        DspSlider("Headroom",c.headroomDb,-12f..0f,"%.1f dB",false,viewModel::setHeadroom)
        Divider(color=Color(0xFF1E293B))
        DspSlider("Spatial / Virtualizer",c.virtualizerStrength,0f..1f,"%.0f %%",true){viewModel.setVirtualizer(it)}
        DspSlider("Master Gain",c.masterGainDb,-24f..12f,"%.1f dB",false,viewModel::setMasterGain)
        DspSlider("Balance",c.balance,-1f..1f,"%.2f",false,viewModel::setBalance)
    }
}
@Composable private fun DspSwitch(title:String,checked:Boolean,on:(Boolean)->Unit)=Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Text(title,color=Color.White);Switch(checked=checked,onCheckedChange=on)}
@Composable private fun DspSlider(title:String,value:Float,range:ClosedFloatingPointRange<Float>,fmt:String,onPercent:Boolean=false,on:(Float)->Unit){Column{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(title,color=Color(0xFFCBD5E1));Text(fmt.format(if(onPercent)value*100 else value),color=Color(0xFF67E8F9),fontSize=12.sp)};Slider(value=value.coerceIn(range.start,range.endInclusive),onValueChange=on,valueRange=range)}}
