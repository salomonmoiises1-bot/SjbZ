package com.sb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(Color(0xFF020617)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("AJUSTES", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Text("Configuración del motor SB", color = Color(0xFF94A3B8), fontSize = 12.sp)
        Info("Motor", "SB Audio DSP / DynamicsProcessing")
        Info("Sample rate", "Detectada del sistema al iniciar")
        Info("Canales", "Estéreo del backend de sesión 0")
        Info("Ecualizador", "10 / 20 / 32 bandas lógicas → bandas físicas disponibles")
        Info("Procesamiento", "Pre-Gain → Bass → Tone/EQ → MDRC → Limiter → Virtualizer → salida")
        Info("Backend", "DynamicsProcessing / AudioEffect session 0")
    }
}
@Composable private fun Info(title:String,value:String)=Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF0B1220))){Column(Modifier.fillMaxWidth().padding(14.dp)){Text(title,color=Color(0xFF64748B),fontSize=10.sp);Text(value,color=Color.White,fontSize=14.sp)}}
