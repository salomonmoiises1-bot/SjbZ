package com.sjbz.aimp.model

data class MDRCBandConfig(
    var frequencyHz: Float,
    var gainDb: Float = 0f,
    var thresholdDb: Float = -20f,
    var ratio: Float = 2f,
    var attackMs: Float = 10f,
    var releaseMs: Float = 100f,
    var isEnabled: Boolean = true
)
