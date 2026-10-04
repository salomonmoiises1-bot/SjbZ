package com.sb.dsp

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.sb.dsp.service.SbDspForegroundService

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        layout.addView(TextView(this).apply {
            text = "SB Audio DSP\nBackend externo: DynamicsProcessing\nBackend PCM: Oboe"
            textSize = 18f
        })
        layout.addView(Button(this).apply {
            text = "Activar DSP"
            setOnClickListener { SbDspForegroundService.startService(this@MainActivity, 0) }
        })
        layout.addView(Button(this).apply {
            text = "Detener DSP"
            setOnClickListener {
                stopService(Intent(this@MainActivity, SbDspForegroundService::class.java))
            }
        })
        setContentView(layout)

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf("android.permission.POST_NOTIFICATIONS"), 100)
        }
    }
}
