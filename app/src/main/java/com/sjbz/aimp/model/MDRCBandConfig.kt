package com.sjbz.aimp.model
data class MDRCBandConfig(
    val name: String = "",
    val cutoffHz: Float = 1000f,
    val thresholdDb: Float = -20f,
    val ratio: Float = 2f,
    val attackMs: Float = 10f,
    val releaseMs: Float = 100f,
    val gainDb: Float = 0f,
    val kneeWidthDb: Float = 3.0f,
    val enabled: Boolean = true
)
