package com.sjbz.aimp
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class EqActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_eq)

        findViewById<Button>(R.id.btnHarman)?.setOnClickListener {
            Toast.makeText(this, "Harman Kardon aplicado", Toast.LENGTH_SHORT).show()
        }

        // FIX: MDRC no invertido + sin ANR
        for(i in 0..4){
            try{
                val seekId = resources.getIdentifier("seekMdrc$i","id",packageName)
                val txtId = resources.getIdentifier("tvMdrc$i","id",packageName)
                val seek = findViewById<SeekBar>(seekId)
                val txt = findViewById<TextView>(txtId)
                if(seek == null) continue
                seek.max = 240
                seek.progress = 120
                seek.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        val g = -12f + p * 0.1f // corregido
                        txt?.text = String.format("%+.1f dB", g)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?){}
                    override fun onStopTrackingTouch(sb: SeekBar?){
                        // aca aplicas al audio, solo al soltar
                    }
                })
            } catch(e: Exception){}
        }
    }
}
