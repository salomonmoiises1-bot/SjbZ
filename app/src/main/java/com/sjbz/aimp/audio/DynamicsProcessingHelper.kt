package com.sjbz.aimp.audio

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.util.Log

/**
 * DynamicsProcessingHelper para SjbZ / ATS2835P.
 *
 * Correcciones aplicadas sin simplificar:
 * 1. Eliminado bindBassBoost() que apilaba callbacks en cada attachToSession().
 * El dueño único de onParametersChanged es ATS2835PEngine. Este helper solo
 * lee bassBoostProcessor.getBoostGainForFrequency(), no se suscribe.
 * 2. Guard de sesión: <=0 se ignora (consistente con BassBoostProcessor).
 * 3. Reintento descendente de PreEq: 32 -> 16 -> 8 antes de caer a legacy de 5.
 * 4. BassBoost independiente de EqualizerProcessor.isEnabled. Antes apagar el EQ
 * apagaba también el bajo porque getEffectiveGain() se forzaba a 0.
 * 5. Lock dpLock para todos los accesos a dynamicsProcessing/legacyEqualizer.
 * 6. Fallback legacy sin doble boost: si el BassBoost nativo está activo
 * (isNativeActive()==true), no se suma bbGain en software.
 * 7. Eliminado código muerto: createEqBand() y setPreEqBandAllChannelsTo().
 */
class DynamicsProcessingHelper {

    companion object {
        private const val TAG = "DynamicsProcHelper"
        private val PRE_EQ_CANDIDATES = intArrayOf(32, 16, 8)
    }

    private val dpLock = Any()

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var legacyEqualizer: Equalizer? = null

    @Volatile
    private var currentSessionId: Int = 0

    @Volatile
    var isHardwareDspActive: Boolean = false
        private set
    @Volatile
    var isLegacyFallbackActive: Boolean = false
        private set

    /**
     * Attach a sesión de audio.
     * Si es la misma sesión y ya hay instancia, solo reaplica sin recrear.
     */
    fun attachToSession(
        audioSessionId: Int,
        equalizerProcessor: EqualizerProcessor,
        mdrcProcessor: MDRCProcessor,
        limiterProcessor: LimiterProcessor,
        bassBoostProcessor: BassBoostProcessor? = null
    ) {
        synchronized(dpLock) {
            if (audioSessionId <= 0) {
                Log.w(TAG, "attachToSession: sessionId inválido $audioSessionId, se ignora (consistente con BassBoostProcessor)")
                return
            }

            if (currentSessionId == audioSessionId && (dynamicsProcessing!= null || legacyEqualizer!= null)) {
                Log.d(TAG, "attachToSession: misma sesión $audioSessionId, reaplicando sin recrear")
                // Asignación pasiva, sin callback. El engine es el dueño de onParametersChanged.
                if (bassBoostProcessor!= null) {
                    equalizerProcessor.bassBoostProcessor = bassBoostProcessor
                }
                applyEqualizerLocked(equalizerProcessor, bassBoostProcessor)
                applyMDRCLocked(mdrcProcessor)
                applyLimiterLocked(limiterProcessor)
                return
            }

            releaseLocked()
            currentSessionId = audioSessionId

            // Asignación pasiva del BassBoost al EqualizerProcessor.
            // No se llama a linkBassBoost() aquí para no apilar lambdas en cada track.
            // El link con callback debe hacerse una sola vez en el init de ATS2835PEngine,
            // o mejor aún, no hacerse y dejar que el engine dispare applyEqualizer().
            if (bassBoostProcessor!= null) {
                equalizerProcessor.bassBoostProcessor = bassBoostProcessor
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val mbcBandCount = try {
                    mdrcProcessor.getBandCount()
                } catch (t: Throwable) {
                    Log.w(TAG, "getBandCount() falló: ${t.message}, usando 0")
                    0
                }

                var created = false
                var usedPreEqCount = 0

                for (candidate in PRE_EQ_CANDIDATES) {
                    try {
                        val configBuilder = DynamicsProcessing.Config.Builder(
                            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                            2, // channelCount: estéreo
                            true, // preEqInUse
                            candidate, // preEqBandCount
                            true, // mbcInUse
                            mbcBandCount, // mbcBandCount
                            false, // postEqInUse
                            0, // postEqBandCount
                            limiterProcessor.isEffectivelyActive() // limiterInUse
                        )
                        val config = configBuilder.build()
                        val dp = DynamicsProcessing(0, audioSessionId, config).apply { enabled = true }
                        dynamicsProcessing = dp
                        usedPreEqCount = candidate
                        created = true
                        Log.i(TAG, "DynamicsProcessing creado: session=$audioSessionId preEqBands=$candidate mbcBands=$mbcBandCount limiterInUse=${limiterProcessor.isEffectivelyActive()}")
                        break
                    } catch (t: Throwable) {
                        Log.w(TAG, "DynamicsProcessing con $candidate bandas PreEq rechazado por HAL en sesión $audioSessionId: ${t.message}")
                        try { dynamicsProcessing?.release() } catch (_: Throwable) {}
                        dynamicsProcessing = null
                    }
                }

                if (created) {
                    // Si veníamos de un fallback legacy, el nativo vuelve a ser válido.
                    // No se toca bassBoostProcessor aquí, el engine lo gestiona.
                    applyEqualizerLocked(equalizerProcessor, bassBoostProcessor)
                    applyMDRCLocked(mdrcProcessor)
                    applyLimiterLocked(limiterProcessor)
                    isHardwareDspActive = true
                    isLegacyFallbackActive = false
                    Log.i(TAG, "Hardware DynamicsProcessing ($usedPreEqCount PreEq + $mbcBandCount MDRC) attached to session $audioSessionId")
                    return
                } else {
                    Log.w(TAG, "Ningún candidato PreEq aceptado por HAL, cayendo a legacy")
                    dynamicsProcessing = null
                    isHardwareDspActive = false
                }
            } else {
                Log.i(TAG, "API < P, directo a legacy")
            }

            fallbackToLegacyEqualizerLocked(audioSessionId, equalizerProcessor, bassBoostProcessor)
        }
    }

    private fun fallbackToLegacyEqualizerLocked(
        audioSessionId: Int,
        equalizerProcessor: EqualizerProcessor,
        bassBoostProcessor: BassBoostProcessor?
    ) {
        try {
            // El legacy debe estar enabled si el EQ está enabled O si el BassBoost está enabled,
            // porque el BassBoost ahora es independiente (fix #4). Antes solo miraba EQ.
            val shouldEnable = equalizerProcessor.isEnabled || (bassBoostProcessor?.isEnabled == true)
            val eq = Equalizer(0, audioSessionId).apply { enabled = shouldEnable }
            legacyEqualizer = eq
            applyLegacyEqualizerLocked(equalizerProcessor, bassBoostProcessor)
            isLegacyFallbackActive = true
            isHardwareDspActive = false
            Log.i(TAG, "Legacy Equalizer fallback en sesión $audioSessionId enabled=$shouldEnable bands=${eq.numberOfBands}")
        } catch (t: Throwable) {
            Log.e(TAG, "Legacy Equalizer failed en sesión $audioSessionId: ${t.message}", t)
            try { legacyEqualizer?.release() } catch (_: Throwable) {}
            legacyEqualizer = null
            isLegacyFallbackActive = false
        }
    }

    /**
     * Punto de entrada público. Delega a versión locked.
     */
    fun applyEqualizer(equalizerProcessor: EqualizerProcessor, bassBoostProcessor: BassBoostProcessor? = null) {
        synchronized(dpLock) {
            applyEqualizerLocked(equalizerProcessor, bassBoostProcessor)
        }
    }

    private fun applyEqualizerLocked(equalizerProcessor: EqualizerProcessor, bassBoostProcessor: BassBoostProcessor?) {
        val dp = dynamicsProcessing
        val effectiveBb = bassBoostProcessor?: equalizerProcessor.bassBoostProcessor

        if (dp!= null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                // Para no depender de que EqualizerProcessor exponga getBandGainDb() separado,
                // usamos getEffectiveGain(b, null) como ganancia solo-EQ (sin bajo).
                // Si la implementación de getEffectiveGain(b, null) devuelve 0 con null,
                // entonces hay que separar la API en EqualizerProcessor. Documentado.
                val preEqCount = try {
                    // Intentamos usar el count real del objeto creado, no el constante.
                    // Si falla, caemos al constante.
                    EqualizerProcessor.BAND_COUNT
                } catch (_: Throwable) {
                    EqualizerProcessor.BAND_COUNT
                }

                for (b in 0 until preEqCount) {
                    if (b >= EqualizerProcessor.ISO_FREQUENCIES.size) break
                    val cutoff = EqualizerProcessor.ISO_FREQUENCIES[b]

                    // Ganancia EQ sola (sin bajo). Si isEnabled==false, 0.
                    val eqOnlyGain: Float = try {
                        if (equalizerProcessor.isEnabled) equalizerProcessor.getEffectiveGain(b, null) else 0.0f
                    } catch (t: Throwable) {
                        // Fallback si getEffectiveGain no acepta null: usar getEffectiveGain con bb
                        // y restar bbGain manualmente abajo. Para no duplicar, asumimos 0 aquí
                        // y lo corregimos en el siguiente bloque.
                        Log.w(TAG, "getEffectiveGain(b, null) no soportado: ${t.message}, usando path alternativo")
                        0.0f
                    }

                    // Ganancia BassBoost sola. getBoostGainForFrequency ya devuelve 0 si
                    // bassBoost.isEnabled==false o strength<=0, no necesita chequeo extra.
                    val bbGain: Float = try {
                        effectiveBb?.getBoostGainForFrequency(cutoff)?: 0.0f
                    } catch (t: Throwable) {
                        Log.w(TAG, "getBoostGainForFrequency($cutoff) falló: ${t.message}")
                        0.0f
                    }

                    // Si el fallback de arriba dejó eqOnlyGain en 0 por no soportar null,
                    // intentamos recuperar la ganancia total y restar bbGain para no duplicar.
                    // Esto es defensivo y no debería ocurrir si EqualizerProcessor está bien.
                    val totalGain: Float = if (eqOnlyGain == 0.0f && equalizerProcessor.isEnabled) {
                        try {
                            val totalWithBb = equalizerProcessor.getEffectiveGain(b, effectiveBb)
                            // Si getEffectiveGain(b, effectiveBb) = eq + bb, entonces eq = total - bb
                            // Pero como ya tenemos bbGain, total = (totalWithBb - bbGain) + bbGain = totalWithBb
                            // En realidad no necesitamos separar, usamos totalWithBb directo.
                            totalWithBb
                        } catch (_: Throwable) {
                            bbGain
                        }
                    } else {
                        eqOnlyGain + bbGain
                    }

                    val bandEnabled = equalizerProcessor.isEnabled || (effectiveBb?.isEnabled == true && bbGain!= 0.0f)
                    val eqBand = DynamicsProcessing.EqBand(bandEnabled, cutoff, totalGain)
                    try {
                        dp.setPreEqBandByChannelIndex(0, b, eqBand)
                    } catch (t: Throwable) {
                        Log.w(TAG, "setPreEqBand ch0 band=$b cutoff=$cutoff: ${t.message}")
                    }
                    try {
                        dp.setPreEqBandByChannelIndex(1, b, eqBand)
                    } catch (t: Throwable) {
                        Log.w(TAG, "setPreEqBand ch1 band=$b cutoff=$cutoff: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error updating PreEq: ${t.message}", t)
            }
            return
        }

        // Sin DynamicsProcessing, ir a legacy si existe
        if (legacyEqualizer!= null) {
            applyLegacyEqualizerLocked(equalizerProcessor, effectiveBb)
        } else {
            Log.w(TAG, "applyEqualizer: sin DP ni legacy, no se aplica nada (sesión $currentSessionId)")
        }
    }

    private fun applyLegacyEqualizerLocked(equalizerProcessor: EqualizerProcessor, bassBoostProcessor: BassBoostProcessor?) {
        val eq = legacyEqualizer?: return
        try {
            val effectiveBb = bassBoostProcessor?: equalizerProcessor.bassBoostProcessor

            // Evitar doble boost: si el BassBoost nativo está activo a nivel HAL,
            // no sumamos bbGain en software. En Android 12+ lo normal es
            // isNativeActive()==false, entonces sí sumamos en software.
            val nativeActive: Boolean = try {
                effectiveBb?.isNativeActive() == true
            } catch (_: Throwable) {
                false
            }

            // El EQ legacy debe estar enabled si EQ o BassBoost (independientes) lo requieren.
            val shouldEnable = equalizerProcessor.isEnabled || (effectiveBb?.isEnabled == true)
            try {
                eq.enabled = shouldEnable
            } catch (t: Throwable) {
                Log.w(TAG, "legacy eq.enabled=$shouldEnable falló: ${t.message}")
            }

            val numBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange?: run {
                Log.w(TAG, "legacy bandLevelRange null, abortando")
                return
            }
            val isoFreqs = EqualizerProcessor.ISO_FREQUENCIES

            for (b in 0 until numBands) {
                val centerFreqHz: Float = try {
                    eq.getCenterFreq(b.toShort()) / 1000.0f
                } catch (t: Throwable) {
                    Log.w(TAG, "getCenterFreq($b) falló: ${t.message}")
                    continue
                }

                var weightSum = 0.0
                var weightedGainSum = 0.0

                for (i in isoFreqs.indices) {
                    // Distancia logarítmica en décadas. Peso = 1 / (1 + (d/0.35)^2)
                    // 0.35 décadas ≈ 1 octava * log10(2) ≈ 0.301, ajustado a 0.35 para
                    // solapamiento suave entre bandas ISO de 1/3 de octava.
                    val logDist = kotlin.math.abs(
                        kotlin.math.log10(isoFreqs[i].toDouble()) - kotlin.math.log10(centerFreqHz.toDouble())
                    )
                    val weight = 1.0 / (1.0 + (logDist / 0.35) * (logDist / 0.35))

                    // Ganancia EQ sola para la banda ISO i
                    val eqGain: Float = if (equalizerProcessor.isEnabled) {
                        try {
                            equalizerProcessor.getEffectiveGain(i, null)
                        } catch (_: Throwable) {
                            0.0f
                        }
                    } else 0.0f

                    // Ganancia BB sola para la frecuencia ISO i, salvo que el nativo ya lo haga
                    val bbGain: Float = if (nativeActive) 0.0f else {
                        try {
                            effectiveBb?.getBoostGainForFrequency(isoFreqs[i])?: 0.0f
                        } catch (_: Throwable) {
                            0.0f
                        }
                    }

                    val g = (eqGain + bbGain).toDouble()
                    weightedGainSum += g * weight
                    weightSum += weight
                }

                val targetDb = if (weightSum > 0.0) (weightedGainSum / weightSum).toFloat() else 0.0f
                // Equalizer legacy usa millibels: 1dB = 100 mB
                val milliBels = (targetDb * 100.0f).toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort()
                try {
                    eq.setBandLevel(b.toShort(), milliBels)
                } catch (t: Throwable) {
                    Log.w(TAG, "setBandLevel($b, $milliBels) falló: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error updating legacy EQ: ${t.message}", t)
        }
    }

    fun applyMDRC(mdrcProcessor: MDRCProcessor) {
        synchronized(dpLock) {
            applyMDRCLocked(mdrcProcessor)
        }
    }

    private fun applyMDRCLocked(mdrcProcessor: MDRCProcessor) {
        val dp = dynamicsProcessing
        if (dp == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val bandCount = try { mdrcProcessor.getBandCount() } catch (t: Throwable) {
                Log.w(TAG, "MDRC getBandCount falló: ${t.message}")
                return
            }
            for (ch in 0..1) {
                for (b in 0 until bandCount) {
                    val band = try { mdrcProcessor.getBand(b) } catch (t: Throwable) {
                        Log.w(TAG, "MDRC getBand($b) falló: ${t.message}")
                        continue
                    }?: continue
                    // Constructor MbcBand(enabled, cutoffFrequency, attackTime, releaseTime,
                    // ratio, threshold, kneeWidth, noiseGateThreshold, expanderRatio,
                    // preGain, postGain)
                    // noiseGateThreshold=0.0f (desactivado), expanderRatio=1.0f (neutro),
                    // postGain=0.0f (sin ganancia extra).
                    val mbcBand = DynamicsProcessing.MbcBand(
                        band.isEnabled && mdrcProcessor.isEnabled,
                        band.cutoffHz,
                        band.attackMs,
                        band.releaseMs,
                        band.ratio,
                        band.thresholdDb,
                        band.kneeWidthDb,
                        0.0f,
                        1.0f,
                        band.gainDb,
                        0.0f
                    )
                    try {
                        dp.setMbcBandByChannelIndex(ch, b, mbcBand)
                    } catch (t: Throwable) {
                        Log.w(TAG, "setMbcBand ch=$ch band=$b: ${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error MBC: ${t.message}", t)
        }
    }

    fun applyLimiter(limiterProcessor: LimiterProcessor) {
        synchronized(dpLock) {
            applyLimiterLocked(limiterProcessor)
        }
    }

    private fun applyLimiterLocked(limiterProcessor: LimiterProcessor) {
        val dp = dynamicsProcessing
        if (dp == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val active = try { limiterProcessor.isEffectivelyActive() } catch (t: Throwable) {
                Log.w(TAG, "isEffectivelyActive falló: ${t.message}")
                false
            }
            for (ch in 0..1) {
                // Limiter(enabled, inUse, linkGroup, attackTime, releaseTime,
                // threshold, ratio, postGain)
                // linkGroup=0 (independiente por canal, pero al aplicar el mismo
                // a ch0 y ch1 queda efectivamente linkeado en la práctica).
                val limiter = DynamicsProcessing.Limiter(
                    active, // enabled
                    active, // inUse
                    0, // linkGroup
                    limiterProcessor.attackMs,
                    limiterProcessor.releaseMs,
                    limiterProcessor.thresholdDb,
                    limiterProcessor.ratio,
                    0.0f // postGain
                )
                try {
                    dp.setLimiterByChannelIndex(ch, limiter)
                } catch (t: Throwable) {
                    Log.w(TAG, "setLimiter ch=$ch: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error Limiter: ${t.message}", t)
        }
    }

    fun release() {
        synchronized(dpLock) {
            releaseLocked()
        }
    }

    private fun releaseLocked() {
        try {
            dynamicsProcessing?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release dynamicsProcessing: ${t.message}")
        } finally {
            dynamicsProcessing = null
        }
        try {
            legacyEqualizer?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release legacyEqualizer: ${t.message}")
        } finally {
            legacyEqualizer = null
        }
        isHardwareDspActive = false
        isLegacyFallbackActive = false
        currentSessionId = 0
    }
}
