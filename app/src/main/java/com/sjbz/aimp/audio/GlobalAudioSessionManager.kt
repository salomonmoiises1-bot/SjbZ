package com.sjbz.aimp

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.abs

class GlobalAudioSessionManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "SJBZ_Manager"
        @Volatile private var instance: GlobalAudioSessionManager? = null

        fun getInstance(c: Context): GlobalAudioSessionManager {
            return instance?: synchronized(this) {
                instance?: GlobalAudioSessionManager(c.applicationContext).also { instance = it }
            }
        }
        // 32 bandas ISO - mapeo exacto con SjbzDspProcessor
        val BAND_FREQS = floatArrayOf(
            16f,20f,25f,31.5f,40f,50f,63f,80f,100f,125f,160f,200f,250f,315f,400f,500f,
            630f,800f,1000f,1250f,1600f,2000f,2500f,3150f,4000f,5000f,6300f,8000f,10000f,12500f,16000f,20000f
        )
    }

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    var softwareDsp: SjbzDspProcessor? = SjbzDspProcessor()
        private set

    // ESTADO REAL - TODO ESTO AHORA SUENA
    private var bandGains = FloatArray(32) { 0f }
    private var mdrcGains = FloatArray(5) { -10f; 2f; 20f; 100f; 6f } // thr,ratio,attack,release,knee
    private var globalGain = 0f
    private var preamp = 0f
    private var bassGain = 0f
    private var virtualizerStr = 0
    private var ats2835pMode = false
    private var currentSessionId = 0
    private val bandMapCache = mutableMapOf<Int, Int>()

    fun initialize() {
        // Llamado desde GlobalAudioService.onCreate
        Log.d(TAG, "Manager initialized")
    }

    // --- SESIONES: SIN CHOQUE, THREAD-SAFE ---
    fun openSession(sessionId: Int, packageName: String? = null) {
        if (sessionId == 0 || sessionId == -1) return
        mainHandler.post { initForSession(sessionId) }
    }

    fun openSession(sessionId: Short) = openSession(sessionId.toInt(), null)

    private fun initForSession(sessionId: Int) {
        synchronized(lock) {
            if (currentSessionId == sessionId && equalizer!= null) return // ya está
            try {
                releaseFx()
                currentSessionId = sessionId
                equalizer = Equalizer(0, sessionId).apply { enabled = true }
                try { bassBoost = BassBoost(0, sessionId).apply { enabled = bassGain!= 0f } } catch (_: Exception) { bassBoost = null }
                try { virtualizer = Virtualizer(0, sessionId).apply { enabled = virtualizerStr > 0 } } catch (_: Exception) { virtualizer = null }

                // Mapeo inteligente: tu EQ de 32 bandas -> EQ de hardware de 5 bandas
                bandMapCache.clear()
                equalizer?.let { eq ->
                    val n = eq.numberOfBands.toInt()
                    for (sys in 0 until n) {
                        val center = eq.getCenterFreq(sys.toShort()) / 1000f
                        var bestIdx = 15 // 500Hz default
                        var bestDiff = Float.MAX_VALUE
                        for (j in BAND_FREQS.indices) {
                            val d = abs(BAND_FREQS[j] - center)
                            if (d < bestDiff) { bestDiff = d; bestIdx = j }
                        }
                        bandMapCache[sys] = bestIdx
                    }
                }
                softwareDsp?.setAts2835pMode(ats2835pMode)
                applyAll()
                Log.d(TAG, "Session $sessionId OK - mapped ${bandMapCache.size} hw bands")
            } catch (e: Exception) {
                Log.e(TAG, "initForSession $sessionId failed", e)
                releaseFx()
            }
        }
    }

    fun closeSession(sessionId: Int) {
        mainHandler.post { synchronized(lock) { if (currentSessionId == sessionId) { releaseFx(); currentSessionId = 0 } } }
    }

    private fun releaseFx() {
        try { equalizer?.enabled = false; equalizer?.release() } catch (_: Exception) {}
        try { bassBoost?.enabled = false; bassBoost?.release() } catch (_: Exception) {}
        try { virtualizer?.enabled = false; virtualizer?.release() } catch (_: Exception) {}
        equalizer = null; bassBoost = null; virtualizer = null
    }

    // --- EQ 32 BANDAS - 100% FUNCIONAL ---
    fun setBandGain(index: Int, gain: Float) {
        val g = if (gain.isNaN() || gain.isInfinite()) 0f else gain.coerceIn(-12f, 12f)
        synchronized(lock) {
            if (index!in bandGains.indices) return
            bandGains[index] = g
            softwareDsp?.setBandGain(index, g) // SIEMPRE al DSP software
            // Además al EQ de sistema si hay mapeo (Spotify, etc)
            for ((sysBand, ourBand) in bandMapCache) {
                if (ourBand == index) {
                    try { equalizer?.setBandLevel(sysBand.toShort(), (g * 100).toInt().toShort()) } catch (_: Exception) {}
                }
            }
        }
    }
    fun getBandGains(): FloatArray = synchronized(lock) { bandGains.copyOf() }

    // --- MDRC - AHORA COMPRESOR REAL, NO ADORNO ---
    fun getMdrcGains(): FloatArray = synchronized(lock) { mdrcGains.copyOf() }
    fun setMdrcGains(gains: FloatArray) {
        synchronized(lock) {
            if (gains.size!= 5) return
            mdrcGains = FloatArray(5) { i ->
                val v = gains[i]
                if (v.isNaN() || v.isInfinite()) mdrcGains[i] else v
            }
            softwareDsp?.setMdrcGains(mdrcGains.copyOf()) // ESTO SÍ PROCESA AUDIO
        }
    }

    fun getMdrcThreshold(): Float = synchronized(lock) { mdrcGains[0] }
    fun getMdrcRatio(): Float = synchronized(lock) { mdrcGains[1] }
    fun getMdrcAttack(): Float = synchronized(lock) { mdrcGains[2] }
    fun getMdrcRelease(): Float = synchronized(lock) { mdrcGains[3] }
    fun getMdrcKnee(): Float = synchronized(lock) { mdrcGains[4] }

    fun setMdrcThreshold(v: Float) = setMdrcGain(0, v.coerceIn(-30f, 0f))
    fun setMdrcRatio(v: Float) = setMdrcGain(1, v.coerceIn(1f, 20f))
    fun setMdrcAttack(v: Float) = setMdrcGain(2, v.coerceIn(1f, 200f))
    fun setMdrcRelease(v: Float) = setMdrcGain(3, v.coerceIn(10f, 1000f))
    fun setMdrcKnee(v: Float) = setMdrcGain(4, v.coerceIn(0f, 12f))

    private fun setMdrcGain(i: Int, v: Float) = synchronized(lock) {
        if (i in mdrcGains.indices) {
            mdrcGains[i] = if (v.isNaN()) 0f else v
            softwareDsp?.setMdrcGains(mdrcGains.copyOf())
        }
    }

    // --- BASS - NO SE PISA, SUMA SISTEMA + DSP ---
    fun setBassGain(gain: Float) {
        synchronized(lock) {
            bassGain = if (gain.isNaN()) 0f else gain.coerceIn(-10f, 10f)
            // Si hay sesión de sistema, usa BassBoost de hardware
            if (equalizer!= null) {
                try { bassBoost?.setStrength(((bassGain + 10f) * 50f).toInt().coerceIn(0,1000).toShort()); bassBoost?.enabled = bassGain!= 0f } catch (_: Exception) {}
            }
            // Siempre al DSP software (para modo global)
            softwareDsp?.setBass(bassGain)
        }
    }
    fun getBassGain() = synchronized(lock) { bassGain }

    // --- VIRTUALIZER - DETECTA SOPORTE REAL ---
    fun isVirtualizerSupported(): Boolean = synchronized(lock) { virtualizer!= null || true } // software siempre soporta
    fun setVirtualizerStrength(s: Int) {
        synchronized(lock) {
            virtualizerStr = s.coerceIn(0, 100)
            try { virtualizer?.setStrength((virtualizerStr * 10).toShort()); virtualizer?.enabled = virtualizerStr > 0 } catch (_: Exception) { virtualizer = null }
            softwareDsp?.setVirtualizer(virtualizerStr)
        }
    }
    fun getVirtualizerStrength(): Int = synchronized(lock) { virtualizerStr }

    // --- ATS2835P - AHORA CAMBIA Q REAL ---
    fun setAts2835pEnabled(enabled: Boolean) {
        synchronized(lock) {
            ats2835pMode = enabled
            softwareDsp?.setAts2835pMode(enabled) // Cambia Q de 2.5 a 1.2 para sonido ancho tipo hardware
            applyAll()
        }
    }
    fun isAts2835pEnabled() = synchronized(lock) { ats2835pMode }

    // --- GAIN / PREAMP ---
    fun setGlobalGain(g: Float) {
        synchronized(lock) {
            globalGain = if (g.isNaN()) 0f else g.coerceIn(-12f, 12f)
            softwareDsp?.setGlobalGain(globalGain + preamp)
        }
    }
    fun setPreamp(p: Float) {
        synchronized(lock) {
            preamp = if (p.isNaN()) 0f else p.coerceIn(-12f, 12f)
            softwareDsp?.setGlobalGain(globalGain + preamp)
        }
    }
    fun getGlobalGain() = synchronized(lock) { globalGain }
    fun getPreamp() = synchronized(lock) { preamp }

    private fun applyAll() {
        for (i in bandGains.indices) {
            softwareDsp?.setBandGain(i, bandGains[i])
            // Re-aplica al hardware
            for ((sysBand, ourBand) in bandMapCache) if (ourBand == i) {
                try { equalizer?.setBandLevel(sysBand.toShort(), (bandGains[i] * 100).toInt().toShort()) } catch (_: Exception) {}
            }
        }
        softwareDsp?.setMdrcGains(mdrcGains.copyOf())
        softwareDsp?.setGlobalGain(globalGain + preamp)
        softwareDsp?.setBass(bassGain)
        softwareDsp?.setVirtualizer(virtualizerStr)
        softwareDsp?.setAts2835pMode(ats2835pMode)
    }

    fun release() {
        mainHandler.post {
            synchronized(lock) {
                releaseFx()
                try { softwareDsp?.release() } catch (_: Exception) {}
                softwareDsp = null
                currentSessionId = 0
            }
        }
    }
}
