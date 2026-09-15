package com.sjbz.aimp.audio

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Coordinates the full audio pipeline:
 * ExoPlayer -> ATS2835P DSP Engine -> Crossfade -> Stereo Balance / Pitch / Speed -> Stereo VU Meters
 */
class AudioChain(private val context: Context, val engine: ATS2835PEngine) {

    private var exoPlayer: ExoPlayer? = null
    private var crossfadeAnimator: ValueAnimator? = null

    // Real-time VU meter values (0.0 to 1.0)
    var vuMeterLeft: Float = 0.0f
        private set
    var vuMeterRight: Float = 0.0f
        private set

    // PATCH: volúmenes por canal calculados por applyStereoBalance.
    // Antes se calculaban leftVol/rightVol y se descartaban (dead code),
    // el balance no hacía nada. Se exponen para el DSP / UI.
    var leftChannelVolume: Float = 1.0f
        private set
    var rightChannelVolume: Float = 1.0f
        private set

    fun bindPlayer(player: ExoPlayer) {
        // PATCH: cancela fade anterior ligado al player viejo
        crossfadeAnimator?.cancel()
        this.exoPlayer = player
        applyPlaybackParameters()
        applyStereoBalance(engine.balance)
    }

    fun attachAudioSession(audioSessionId: Int) {
        engine.attachAudioSession(audioSessionId)
    }

    fun applyPlaybackParameters() {
        val player = exoPlayer?: return
        val pitch = engine.pitch.coerceIn(0.5f, 2.0f)
        val speed = engine.speed.coerceIn(0.5f, 2.0f)
        try {
            player.playbackParameters = PlaybackParameters(speed, pitch)
        } catch (_: Exception) {}
    }

    fun setPlaybackParameters(speed: Float, pitch: Float = 1.0f) {
        // PATCH: coerce aquí también, antes se guardaba sin clamp en engine
        // y applyPlaybackParameters lo corregía solo para el player, quedando
        // engine.speed/pitch con valores fuera de rango.
        engine.speed = speed.coerceIn(0.5f, 2.0f)
        engine.pitch = pitch.coerceIn(0.5f, 2.0f)
        applyPlaybackParameters()
    }

    fun applyStereoBalance(balance: Float) {
        val b = balance.coerceIn(-1.0f, 1.0f)
        engine.balance = b

        // Constant power panning law: angle 0..PI/2
        val angle = (b + 1.0f) * (Math.PI.toFloat() / 4.0f)
        leftChannelVolume = cos(angle).coerceIn(0f, 1f)
        rightChannelVolume = sin(angle).coerceIn(0f, 1f)

        // PATCH: ExoPlayer no tiene volumen por canal en la API pública; se deja
        // el volumen maestro en 1.0 y los factores por canal quedan disponibles
        // para el DSP (engine) y los VU meters. Antes el cálculo se tiraba.
        val player = exoPlayer?: return
        try {
            player.volume = 1.0f
            // El balance real se aplica en el DSP via engine.balance (usado en updateVUMeters
            // y en el chain nativo). Aquí no se finge con player.volume.
        } catch (_: Exception) {}
    }

    /**
     * Executes crossfade volume fade-in ramp over configured crossfade duration.
     */
    fun startFadeIn(onComplete: (() -> Unit)? = null) {
        val player = exoPlayer?: run {
            try { onComplete?.invoke() } catch (_: Exception) {}
            return
        }
        // PATCH: si crossfadeSeconds es 0, no hay fade; completa directo
        if (engine.crossfadeSeconds <= 0) {
            try { player.volume = 1.0f } catch (_: Exception) {}
            try { onComplete?.invoke() } catch (_: Exception) {}
            return
        }
        val durationMs = (engine.crossfadeSeconds * 1000L).coerceIn(500L, 10000L)

        crossfadeAnimator?.cancel()
        try { player.volume = 0.0f } catch (_: Exception) {}

        var completed = false
        fun fireOnce() {
            if (!completed) {
                completed = true
                try { onComplete?.invoke() } catch (_: Exception) {}
            }
        }

        crossfadeAnimator = ValueAnimator.ofFloat(0.0f, 1.0f).apply {
            duration = durationMs
            addUpdateListener { animator ->
                val vol = animator.animatedValue as Float
                try { player.volume = vol } catch (_: Exception) {}
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { fireOnce() }
                override fun onAnimationCancel(animation: Animator) { fireOnce() }
            })
            start()
        }
    }

    /**
     * Executes crossfade volume fade-out ramp over configured crossfade duration.
     */
    fun startFadeOut(onComplete: () -> Unit) {
        val player = exoPlayer?: run {
            try { onComplete() } catch (_: Exception) {}
            return
        }
        // PATCH: si crossfadeSeconds es 0, completa directo sin animación
        if (engine.crossfadeSeconds <= 0) {
            try { player.volume = 0.0f } catch (_: Exception) {}
            try { onComplete() } catch (_: Exception) {}
            return
        }
        val durationMs = (engine.crossfadeSeconds * 1000L).coerceIn(500L, 10000L)

        crossfadeAnimator?.cancel()
        val startVol = try { player.volume } catch (_: Exception) { 1.0f }

        // PATCH: antes onComplete se llamaba en cada frame con vol <= 0.01
        // (decenas de invocaciones). Ahora se dispara una sola vez al terminar.
        var completed = false
        fun fireOnce() {
            if (!completed) {
                completed = true
                try { onComplete() } catch (_: Exception) {}
            }
        }

        crossfadeAnimator = ValueAnimator.ofFloat(startVol, 0.0f).apply {
            duration = durationMs
            addUpdateListener { animator ->
                val vol = animator.animatedValue as Float
                try { player.volume = vol } catch (_: Exception) {}
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { fireOnce() }
                override fun onAnimationCancel(animation: Animator) { fireOnce() }
            })
            start()
        }
    }

    /**
     * Simulates or calculates stereo VU meter peak levels for visual presentation in AIMP VU meters.
     */
    fun updateVUMeters(isPlaying: Boolean, progressFraction: Float) {
        if (!isPlaying) {
            vuMeterLeft = (vuMeterLeft * 0.85f).coerceAtLeast(0.0f)
            vuMeterRight = (vuMeterRight * 0.85f).coerceAtLeast(0.0f)
            return
        }

        // Dynamic stereo response correlated with playback & preamp
        val baseLeft = (0.55f + 0.35f * sin(progressFraction * 60f)).coerceIn(0.1f, 0.98f)
        val baseRight = (0.52f + 0.38f * cos(progressFraction * 62f)).coerceIn(0.1f, 0.98f)

        // PATCH: antes (1-balance)/2 daba 0.5 al centro y atenuaba los VU a la mitad
        // siempre. Ahora usa la misma ley de potencia constante del balance real:
        // al centro ambos factores son ~0.707, en extremos 1.0/0.0.
        val angle = (engine.balance.coerceIn(-1f, 1f) + 1.0f) * (Math.PI.toFloat() / 4.0f)
        val balanceFactorL = cos(angle).coerceIn(0f, 1f)
        val balanceFactorR = sin(angle).coerceIn(0f, 1f)
        // Normaliza para que al centro no se vea a mitad de escala:
        // divide por 0.7071 (cos(PI/4)) de modo que centro = 1.0
        val norm = 0.70710678f
        val normL = (balanceFactorL / norm).coerceIn(0f, 1f)
        val normR = (balanceFactorR / norm).coerceIn(0f, 1f)

        // PATCH: suavizado para que no salte entre frames (antes era valor crudo
        // del sin/cos y el VU temblaba)
        val smooth = 0.35f
        vuMeterLeft = (vuMeterLeft * (1f - smooth) + (baseLeft * normL).coerceIn(0f, 1f) * smooth).coerceIn(0.0f, 1.0f)
        vuMeterRight = (vuMeterRight * (1f - smooth) + (baseRight * normR).coerceIn(0f, 1f) * smooth).coerceIn(0.0f, 1.0f)
    }

    fun release() {
        // PATCH: AudioChain no es dueña de engine (inyectado por constructor).
        // Antes llamaba engine.release() y si PlaybackService también lo liberaba
        // había doble release. Ahora solo cancela su animator y desvincula el player.
        try { crossfadeAnimator?.cancel() } catch (_: Exception) {}
        crossfadeAnimator = null
        exoPlayer = null
    }
}
