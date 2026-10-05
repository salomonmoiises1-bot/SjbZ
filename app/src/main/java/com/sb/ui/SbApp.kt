package com.sb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SbApp(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    var selectedScreen by remember { mutableStateOf("INICIO") }

    val config by viewModel.config.collectAsState()

    val navItems = listOf(
        NavigationItem("INICIO", "Inicio", Icons.Default.Dashboard),
        NavigationItem("EQ32", "EQ32", Icons.Default.GraphicEq),
        NavigationItem("MDRC", "MDRC", Icons.Default.Tune),
        NavigationItem("SALIDA", "Salida", Icons.Default.VolumeUp),
        NavigationItem("AJUSTES", "Ajustes", Icons.Default.Settings)
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color(0xFF020617),
        topBar = {
            Surface(color = Color(0xFF0B1220), tonalElevation = 4.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("MOTOR DSP", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(if (config.masterEnabled) "PROCESAMIENTO ACTIVO" else "BYPASS / MOTOR DESACTIVADO",
                            color = if (config.masterEnabled) Color(0xFF67E8F9) else Color(0xFFF59E0B), fontSize = 9.sp)
                    }
                    Switch(checked = config.masterEnabled, onCheckedChange = { viewModel.toggleMaster() })
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0F172A),
                tonalElevation = 8.dp,
                modifier = Modifier.height(64.dp)
            ) {
                navItems.forEach { item ->
                    val isSelected = selectedScreen == item.key
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selectedScreen = item.key },
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                modifier = Modifier.size(20.dp),
                                tint = if (isSelected) Color(0xFF22D3EE) else Color(0xFF64748B)
                            )
                        },
                        label = {
                            Text(
                                text = item.label,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color(0xFF22D3EE) else Color(0xFF64748B)
                            )
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF020617))
        ) {
            when (selectedScreen) {
                "INICIO" -> DashboardScreen(
                    viewModel = viewModel,
                    onNavigate = { target -> selectedScreen = target }
                )
                "EQ32" -> EqualizerScreen(viewModel = viewModel)
                "MDRC" -> DynamicsScreen(viewModel = viewModel)
                "SALIDA" -> OutputScreen(viewModel = viewModel)
                "AJUSTES" -> SettingsScreen(viewModel = viewModel)
            }
        }
    }
}

private data class NavigationItem(val key: String, val label: String, val icon: ImageVector)