package com.sb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.sb.dsp.ConstantQGraphicEq
import com.sb.dsp.DspConfig

@Composable
fun EqualizerScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val config by viewModel.config.collectAsState()
    val scroll = rememberScrollState()
    val frequencies = when(config.eqMode){DspConfig.EqMode.BANDS_10->ConstantQGraphicEq.FREQUENCIES_10;DspConfig.EqMode.BANDS_20->ConstantQGraphicEq.FREQUENCIES_20;DspConfig.EqMode.BANDS_32->ConstantQGraphicEq.FREQUENCIES_32}
    val gains = config.activeGains()
    Column(modifier.fillMaxSize().background(Color(0xFF020617)).padding(14.dp)) {
        Text("ECUALIZADOR", color=Color.White, style=MaterialTheme.typography.headlineSmall)
        Text("Bancos lógicos 10 / 20 / 32 • adaptación al número físico del DP", color=Color(0xFF94A3B8), fontSize=12.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.padding(top=10.dp)) {
            DspConfig.EqMode.values().forEach { mode ->
                FilterChip(selected=config.eqMode==mode,onClick={viewModel.setEqMode(mode)},label={Text(mode.bandCount.toString())})
            }
            Spacer(Modifier.weight(1f)); Switch(checked=config.eqEnabled,onCheckedChange=viewModel::setEqEnabled)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            frequencies.forEachIndexed { i,freq ->
                var value by remember(config.eqMode, gains.getOrElse(i){0f}) { mutableFloatStateOf(gains.getOrElse(i){0f}) }
                Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.width(36.dp)) {
                    Text("${value.roundToInt()}",color=Color(0xFFCBD5E1),fontSize=9.sp)
                    Box(Modifier.height(220.dp).width(36.dp),contentAlignment=Alignment.Center){Slider(value=value,onValueChange={value=it;viewModel.setEqBand(i,it)},valueRange=-12f..12f,modifier=Modifier.width(220.dp).rotate(-90f))}
                    Text(formatFreq(freq),color=Color(0xFF94A3B8),fontSize=8.sp,maxLines=1)
                }
            }
        }
        Spacer(Modifier.height(8.dp)); Text("Respuesta lógica ±12 dB • backend físico consultado en tiempo de ejecución",color=Color(0xFF64748B),fontSize=10.sp)
    }
}
private fun formatFreq(f:Float)=when{f>=1000f->if(f%1000f==0f)"${(f/1000f).toInt()}k" else "${"%.1f".format(f/1000f)}k";else->f.toInt().toString()}
