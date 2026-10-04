package com.sbz.ui

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
fun SbzApp(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    var selectedScreen by remember { mutableStateOf("INICIO") }

    val navItems = listOf(
        NavigationItem("INICIO", "Inicio", Icons.Default.Dashboard),
        NavigationItem("EQ32", "EQ32", Icons.Default.GraphicEq),
        NavigationItem("MDRC", "MDRC", Icons.Default.Compress),
        NavigationItem("SALIDA", "Salida", Icons.Default.VolumeUp),
        NavigationItem("AJUSTES", "Ajustes", Icons.Default.Settings)
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color(0xFF020617),
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