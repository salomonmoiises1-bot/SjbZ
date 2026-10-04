package com.sb.ui

import android.app.Application
import android.media.AudioManager
import androidx.lifecycle.AndroidViewModel
import com.sb.dsp.DspConfig
import com.sb.dsp.DspConfigStore
import com.sb.dsp.service.SbDspForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.sb.dsp.DspEngine

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _config = MutableStateFlow(loadRuntimeConfig(app))
    val config: StateFlow<DspConfig> = _config.asStateFlow()
    private val presets = com.sb.dsp.PresetRepository(app)
    private val _currentPreset = MutableStateFlow("Plano SB")
    val currentPreset: StateFlow<String> = _currentPreset.asStateFlow()
    private val _peakDb = MutableStateFlow(-60f)
    val peakDb: StateFlow<Float> = _peakDb.asStateFlow()
    private val _rmsDb = MutableStateFlow(-60f)
    val rmsDb: StateFlow<Float> = _rmsDb.asStateFlow()
    val dspBackendActive: Boolean get() = DspEngine.lastBackendActive
    val dynamicsBackendAvailable: Boolean get() = DspEngine.lastDynamicsAvailable

    private fun loadRuntimeConfig(app: Application): DspConfig {
        val base = DspConfigStore.load(app)
        val audioManager = app.getSystemService(AudioManager::class.java)
        val rate = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            ?.takeIf { it > 0 }?.coerceIn(8000, 192000) ?: base.sampleRate
        return base.copy(sampleRate = rate).validate()
    }

    init {
        SbDspForegroundService.startService(app)
        viewModelScope.launch { while (true) { _peakDb.value = DspEngine.lastPeakDb; _rmsDb.value = DspEngine.lastRmsDb; delay(200) } }
    }

    private fun update(transform: (DspConfig) -> DspConfig) {
        val next = transform(_config.value).validate()
        _config.value = next
        DspConfigStore.save(getApplication(), next)
        SbDspForegroundService.updateConfig(getApplication(), next)
    }

    fun availablePresets(): List<String> = presets.names()
    fun savePreset(name: String) { presets.save(name, _config.value); _currentPreset.value = name }
    fun loadPreset(name: String) = update { presets.load(name, it).also { _currentPreset.value = name } }

    fun toggleMaster() = update { it.copy(masterEnabled = !it.masterEnabled) }
    fun setEqEnabled(v: Boolean) = update { it.copy(eqEnabled = v) }
    fun setEqMode(mode: DspConfig.EqMode) = update { it.copy(eqMode = mode) }
    fun setEqBand(index: Int, db: Float) = update {
        when (it.eqMode) {
            DspConfig.EqMode.BANDS_10 -> it.copy(gains10BandDb = it.gains10BandDb.copyOf().also { g -> if (index in g.indices) g[index] = db })
            DspConfig.EqMode.BANDS_20 -> it.copy(gains20BandDb = it.gains20BandDb.copyOf().also { g -> if (index in g.indices) g[index] = db })
            DspConfig.EqMode.BANDS_32 -> it.copy(gains32BandDb = it.gains32BandDb.copyOf().also { g -> if (index in g.indices) g[index] = db })
        }
    }
    fun setPregain(db: Float) = update { it.copy(pregainEnabled = true, pregainDb = db) }
    fun setBassBoost(v: Float) = update { it.copy(bassBoostEnabled = v > .01f, bassBoostStrength = v) }
    fun setBassBoostFrequency(v: Float) = update { it.copy(bassBoostFrequencyHz = v) }
    fun setTone(bass: Float, mid: Float, treble: Float) = update { it.copy(toneEnabled = true, bassToneDb = bass, midToneDb = mid, trebleToneDb = treble) }
    fun setBassTone(v: Float) = update { it.copy(toneEnabled = true, bassToneDb = v) }
    fun setMidTone(v: Float) = update { it.copy(toneEnabled = true, midToneDb = v) }
    fun setTrebleTone(v: Float) = update { it.copy(toneEnabled = true, trebleToneDb = v) }
    fun setMdrcEnabled(v: Boolean) = update { it.copy(mdrcEnabled = v) }
    fun setMdrcLow(v: Float) = update { it.copy(mdrcLowCrossoverHz = v) }
    fun setMdrcMid(v: Float) = update { it.copy(mdrcMidCrossoverHz = v) }
    fun setMdrcHigh(v: Float) = update { it.copy(mdrcHighCrossoverHz = v) }
    fun setMdrcLowBand(v: DspConfig.BandCompressorConfig) = update { it.copy(mdrcLowBand = v) }
    fun setMdrcMidBand(v: DspConfig.BandCompressorConfig) = update { it.copy(mdrcMidBand = v) }
    fun setMdrcHighBand(v: DspConfig.BandCompressorConfig) = update { it.copy(mdrcHighBand = v) }
    fun setMdrcUltraBand(v: DspConfig.BandCompressorConfig) = update { it.copy(mdrcUltraBand = v) }
    fun setAutoGain(v: Boolean) = update { it.copy(autoGainEnabled = v) }
    fun setAutoGainTarget(v: Float) = update { it.copy(autoGainTargetRmsDb = v) }
    fun setLimiter(v: Boolean) = update { it.copy(limiterEnabled = v) }
    fun setLimiterThreshold(v: Float) = update { it.copy(limiterThresholdDb = v) }
    fun setLimiterRatio(v: Float) = update { it.copy(limiterRatio = v) }
    fun setLimiterAttack(v: Float) = update { it.copy(limiterAttackMs = v) }
    fun setLimiterRelease(v: Float) = update { it.copy(limiterReleaseMs = v) }
    fun setHeadroom(v: Float) = update { it.copy(headroomDb = v) }
    fun setVirtualizer(v: Float) = update { it.copy(virtualizerEnabled = v > .01f, virtualizerStrength = v) }
    fun setMasterGain(db: Float) = update { it.copy(masterGainDb = db) }
    fun setBalance(v: Float) = update { it.copy(balance = v) }
}
