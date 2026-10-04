package com.sbz.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.sb.dsp.service.SbDspForegroundService

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SbDspForegroundService.startService(this)

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF020617)
            ) {
                SbzApp(viewModel = viewModel)
            }
        }
    }
}