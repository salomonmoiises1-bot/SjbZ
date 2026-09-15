package com.sjbz.aimp.audio

import android.util.Log

/**
 * LimiterProcessor para SjbZ / ATS2835P.
 *
 * Controla el limitador del DynamicsProcessing (post-gain stage).
 * Incluye compensación automática de headroom vía postGainDb,
 * seteada por ATS2835PEngine.updateBassBoost().
 */
class LimiterProcessor {

    companion object {
        private const val TAG = "LimiterProcessor"

        const val MIN_THRESHOLD_DB = -12.0f
        const val MAX_THRESHOLD_DB = 0.0f
        const val MIN_RATIO = 1.0f
        const val MAX_RATIO = 20.0f
        const val MIN_ATTACK_MS = 0.5f
        const val MAX_ATTACK_MS = 100.0f
        const val MIN_RELEASE_MS = 10.0f
        const val MAX_RELEASE_MS = 1000.0f
        const val MIN_POST_GAIN_DB = -12.0f
        const val MAX_POST_GAIN_DB = 12.0f
    }

    var onParametersChanged: (() -> Unit)? = null

    var isEnabled: Boolean = true
        set(value) {
            val changed = field != value
            field = value
            if (!changed) return
            onParametersChanged?.invoke()
        }

    // PATCH: flag para bypass en Bluetooth A2DP donde el limiter del HAL
    // mete latencia extra. Antes isEffectivelyActive() no existía y el
    // DynamicsProcessingHelper asumía activo siempre que isEnabled=true.
    var isBypassedForBluetooth: Boolean = false
        set(value) {
            val changed = field != value
            field = value
            if (!changed) return
            Log.d(TAG, "isBypassedForBluetooth=$value")
            onParametersChanged?.invoke()
        }

    var thresholdDb: Float = -3.0f
        set(value) {
            val coerced = value.coerceIn(MIN_THRESHOLD_DB, MAX_THRESHOLD_DB)
            val changed = field != coerced
            field = coerced
            if (!changed) return
            onParametersChanged?.invoke()
        }

    var ratio: Float = 10.0f
        set(value) {
            val coerced = value.coerceIn(MIN_RATIO, MAX_RATIO)
            val changed = field != coerced
            field = coerced
            if (!changed) return
            onParametersChanged?.invoke()
        }

    var attackMs: Float = 2.0f
        set(value) {
            val coerced = value.coerceIn(MIN_ATTACK_MS, MAX_ATTACK_MS)
            val changed = field != coerced
            field = coerced
            if (!changed) return
            onParametersChanged?.invoke()
        }

    var releaseMs: Float = 100.0f
        set(value) {
            val coerced = value.coerceIn(MIN_RELEASE_MS, MAX_RELEASE_MS)
            val changed = field != coerced
            field = coerced
            if (!changed) return
            onParametersChanged?.invoke()
        }

    // PATCH: compensación automática de headroom.
    // ATS2835PEngine.updateBassBoost() setea aquí -bbDb*0.4f para evitar
    // clipseo cuando el BassBoost DSP mete +12dB en graves.
    // DynamicsProcessingHelper.applyLimiterLocked() lo lee vía limiter.postGainDb.
    // Antes no existía y el helper hacía catch(_:Throwable){0f} siempre.
    var postGainDb: Float = 0.0f
        set(value) {
            val coerced = value.coerceIn(MIN_POST_GAIN_DB, MAX_POST_GAIN_DB)
            val changed = field != coerced
            field = coerced
            if (!changed) return
            onParametersChanged?.invoke()
        }

    /**
     * PATCH: estado efectivo real.
     * DynamicsProcessingHelper lo usa para decidir limiterInUse en el Config.Builder
     * y para el flag enabled del DynamicsProcessing.Limiter.
     * Antes el helper llamaba a un método inexistente y caía en catch(false).
     */
    fun isEffectivelyActive(): Boolean {
        return isEnabled && !isBypassedForBluetooth
    }

    fun setAll(
        enabled: Boolean,
        thresholdDb: Float,
        ratio: Float,
        attackMs: Float,
        releaseMs: Float,
        postGainDb: Float = this.postGainDb
    ) {
        // Se setean los fields directo para disparar un solo callback al final
        // en vez de 6 callbacks seguidos que reaplican el DSP 6 veces.
        var changed = false

        val cThreshold = thresholdDb.coerceIn(MIN_THRESHOLD_DB, MAX_THRESHOLD_DB)
        if (this.thresholdDb != cThreshold) { this.thresholdDb = cThreshold; changed = true }

        val cRatio = ratio.coerceIn(MIN_RATIO, MAX_RATIO)
        if (this.ratio != cRatio) { this.ratio = cRatio; changed = true }

        val cAttack = attackMs.coerceIn(MIN_ATTACK_MS, MAX_ATTACK_MS)
        if (this.attackMs != cAttack) { this.attackMs = cAttack; changed = true }

        val cRelease = releaseMs.coerceIn(MIN_RELEASE_MS, MAX_RELEASE_MS)
        if (this.releaseMs != cRelease) { this.releaseMs = cRelease; changed = true }

        val cPost = postGainDb.coerceIn(MIN_POST_GAIN_DB, MAX_POST_GAIN_DB)
        if (this.postGainDb != cPost) { this.postGainDb = cPost; changed = true }

        if (this.isEnabled != enabled) { this.isEnabled = enabled; changed = true }

        // Los setters ya dispararon callbacks individuales; este es por compatibilidad
        // para llamadas batch que setean fields directo. No duplica si ya hubo cambios.
        if (changed) {
            try { onParametersChanged?.invoke() } catch (_: Throwable) {}
        }
    }

    fun toMap(): Map<String, Float> {
        return mapOf(
            "thresholdDb" to thresholdDb,
            "ratio" to ratio,
            "attackMs" to attackMs,
            "releaseMs" to releaseMs,
            "postGainDb" to postGainDb
        )
    }

    fun loadFromMap(map: Map<String, Float>) {
        map["thresholdDb"]?.let { thresholdDb = it }
        map["ratio"]?.let { ratio = it }
        map["attackMs"]?.let { attackMs = it }
        map["releaseMs"]?.let { releaseMs = it }
        map["postGainDb"]?.let { postGainDb = it }
    }
}
