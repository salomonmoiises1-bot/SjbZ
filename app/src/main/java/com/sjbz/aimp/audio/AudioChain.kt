package com.sjbz.aimp.audio
import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.cos
import kotlin.math.sin

class AudioChain(val context: Context, val engine: ATS2835PEngine) {
    private var exoPlayer: ExoPlayer? = null
    var vuMeterLeft = 0f; private set
    var vuMeterRight = 0f; private set
    fun bindPlayer(p: ExoPlayer) { exoPlayer = p }
    fun attachAudioSession(id: Int) = engine.attachAudioSession(id)
    fun setBands(b: FloatArray) = engine.setBands(b)
    fun applyBands(b: FloatArray) = engine.setBands(b)
    fun applyPlaybackParameters() {}
    fun applyStereoBalance(b: Float) { engine.balance = b }
    fun startFadeIn(cb: (() -> Unit)? = null) { exoPlayer?.volume=1f; cb?.invoke() }
    fun startFadeOut(cb: () -> Unit) { exoPlayer?.volume=0f; cb() }
    fun updateVUMeters(playing: Boolean, frac: Float) {
        if(!playing){ vuMeterLeft*=0.85f; vuMeterRight*=0.85f; return }
        vuMeterLeft = (0.55f+0.35f*sin(frac*60f)).coerceIn(0.1f,0.98f)
        vuMeterRight = (0.52f+0.38f*cos(frac*62f)).coerceIn(0.1f,0.98f)
    }
    fun release() = engine.release()
}
