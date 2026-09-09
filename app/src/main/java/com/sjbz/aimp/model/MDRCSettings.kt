package com.sjbz.aimp.model

data class MDRCSettings(
    var isEnabled: Boolean = true,
    var preGainDb: Float = 0f,
    var postGainDb: Float = 0f,
    var bands: List<MDRCBandConfig> = emptyList(),
    var limiterThresholdDb: Float = -1f,
    var limiterReleaseMs: Int = 100
)
