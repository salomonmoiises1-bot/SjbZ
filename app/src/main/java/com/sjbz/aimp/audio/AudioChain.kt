package com.sjbz.aimp.audio

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

    fun bindPlayer(player: ExoPlayer) {
        this.exoPlayer = player
        applyPlaybackParameters()
    }

    fun attachAudioSession(audioSessionId: Int) {
        engine.attachAudioSession(audioSessionId)
    }

    fun applyPlaybackParameters() {
        val player = exoPlayer ?: return
        val pitch = engine.pitch.coerceIn(0.5f, 2.0f)
        val speed = engine.speed.coerceIn(0.5f, 2.0f)
        player.playbackParameters = PlaybackParameters(speed, pitch)
    }

    fun applyStereoBalance(balance: Float) {
        engine.balance = balance.coerceIn(-1.0f, 1.0f)
        val player = exoPlayer ?: return

        // Constant power panning law
        val angle = (engine.balance + 1.0f) * (Math.PI.toFloat() / 4.0f) // 0 to PI/2
        val leftVol = cos(angle)
        val rightVol = sin(angle)

        // On ExoPlayer, standard volume is scalar; stereo balance is configured on audio track or channel volume
        player.volume = 1.0f
    }

    /**
     * Executes crossfade volume fade-in ramp over configured crossfade duration.
     */
    fun startFadeIn(onComplete: (() -> Unit)? = null) {
        val player = exoPlayer ?: return
        val durationMs = (engine.crossfadeSeconds * 1000L).coerceIn(500L, 10000L)

        crossfadeAnimator?.cancel()
        player.volume = 0.0f

        crossfadeAnimator = ValueAnimator.ofFloat(0.0f, 1.0f).apply {
            duration = durationMs
            addUpdateListener { animator ->
                val vol = animator.animatedValue as Float
                player.volume = vol
            }
            start()
        }
    }

    /**
     * Executes crossfade volume fade-out ramp over configured crossfade duration.
     */
    fun startFadeOut(onComplete: () -> Unit) {
        val player = exoPlayer ?: run {
            onComplete()
            return
        }
        val durationMs = (engine.crossfadeSeconds * 1000L).coerceIn(500L, 10000L)

        crossfadeAnimator?.cancel()
        crossfadeAnimator = ValueAnimator.ofFloat(player.volume, 0.0f).apply {
            duration = durationMs
            addUpdateListener { animator ->
                val vol = animator.animatedValue as Float
                player.volume = vol
                if (vol <= 0.01f) {
                    onComplete()
                }
            }
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

        val balanceFactorL = (1.0f - engine.balance).coerceIn(0f, 2f) / 2.0f
        val balanceFactorR = (1.0f + engine.balance).coerceIn(0f, 2f) / 2.0f

        vuMeterLeft = (baseLeft * balanceFactorL).coerceIn(0.0f, 1.0f)
        vuMeterRight = (baseRight * balanceFactorR).coerceIn(0.0f, 1.0f)
    }

    fun release() {
        crossfadeAnimator?.cancel()
        engine.release()
    }
}
