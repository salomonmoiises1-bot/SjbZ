package com.sjbz.aimp.model

import java.io.Serializable

data class AppProfile(
    val id: String,
    val packageName: String,
    val appName: String,
    val presetName: String = "Personalizado",
    val globalGainDb: Float = 0.0f,
    val bandGains: List<Float> = List(32) { 0.0f },
    val bandQs: List<Float> = List(32) { 1.414f },
    val bassBoostDb: Float = 0.0f,
    val bassFreqHz: Float = 85.0f,
    val virtualizerStrength: Int = 0,
    val limiterEnabled: Boolean = true,
    val limiterThresholdDb: Float = -0.5f,
    val autoGainEnabled: Boolean = true,
    val autoGainTargetLufs: Float = -14.0f,
    val mdrcEnabled: Boolean = true,
    val mdrcGains: List<Float> = listOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f),
    val ats2835pEmuEnabled: Boolean = false
) : Serializable {

    companion object {
        const val PACKAGE_GLOBAL = "global"
        const val PACKAGE_SPOTIFY = "com.spotify.music"
        const val PACKAGE_YOUTUBE = "com.google.android.youtube"
        const val PACKAGE_YT_MUSIC = "com.google.android.apps.youtube.music"
        const val PACKAGE_CHROME = "com.android.chrome"

        fun createDefaultProfiles(): List<AppProfile> {
            return listOf(
                AppProfile(
                    id = "prof_global",
                    packageName = PACKAGE_GLOBAL,
                    appName = "Sistema Global",
                    presetName = "Plano Hi-Fi",
                    globalGainDb = 0.0f,
                    bandGains = List(32) { 0.0f },
                    bandQs = List(32) { 1.414f },
                    limiterEnabled = true,
                    autoGainEnabled = true
                ),
                AppProfile(
                    id = "prof_spotify",
                    packageName = PACKAGE_SPOTIFY,
                    appName = "Spotify",
                    presetName = "Audiophile V-Curve",
                    globalGainDb = 1.0f,
                    bandGains = listOf(
                        4.5f, 4.0f, 3.5f, 3.0f, 2.5f, 2.0f, 1.5f, 1.0f,
                        0.5f, 0.0f, -0.5f, -1.0f, -1.0f, -0.5f, 0.0f, 0.5f,
                        0.5f, 1.0f, 1.5f, 2.0f, 2.0f, 2.5f, 3.0f, 3.0f,
                        3.5f, 4.0f, 4.0f, 4.5f, 4.5f, 4.0f, 3.5f, 3.0f
                    ),
                    bandQs = List(32) { 1.414f },
                    bassBoostDb = 3.5f,
                    bassFreqHz = 85.0f,
                    virtualizerStrength = 200,
                    limiterEnabled = true,
                    autoGainEnabled = true,
                    mdrcEnabled = true,
                    ats2835pEmuEnabled = true
                ),
                AppProfile(
                    id = "prof_youtube",
                    packageName = PACKAGE_YOUTUBE,
                    appName = "YouTube",
                    presetName = "Claridad Vocal & Dinámica",
                    globalGainDb = 2.0f,
                    bandGains = listOf(
                        -1.0f, -1.0f, -0.5f, 0.0f, 0.5f, 1.0f, 1.5f, 2.0f,
                        2.5f, 3.0f, 3.5f, 3.5f, 3.0f, 2.5f, 2.0f, 1.5f,
                        1.0f, 1.5f, 2.0f, 2.5f, 2.5f, 2.0f, 1.5f, 1.0f,
                        0.5f, 0.0f, -0.5f, -1.0f, -1.5f, -2.0f, -2.5f, -3.0f
                    ),
                    bandQs = List(32) { 1.414f },
                    bassBoostDb = 1.0f,
                    bassFreqHz = 120.0f,
                    virtualizerStrength = 100,
                    limiterEnabled = true,
                    autoGainEnabled = true,
                    mdrcEnabled = true
                ),
                AppProfile(
                    id = "prof_yt_music",
                    packageName = PACKAGE_YT_MUSIC,
                    appName = "YouTube Music",
                    presetName = "Deep Bass & Club",
                    globalGainDb = 1.5f,
                    bandGains = listOf(
                        5.5f, 5.0f, 4.5f, 4.0f, 3.5f, 2.5f, 1.5f, 0.5f,
                        0.0f, -0.5f, -1.0f, -1.0f, -0.5f, 0.0f, 0.5f, 1.0f,
                        1.0f, 1.5f, 2.0f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f,
                        4.0f, 4.0f, 3.5f, 3.0f, 2.5f, 2.0f, 1.5f, 1.0f
                    ),
                    bandQs = List(32) { 1.414f },
                    bassBoostDb = 5.0f,
                    bassFreqHz = 60.0f,
                    virtualizerStrength = 250,
                    limiterEnabled = true,
                    autoGainEnabled = true,
                    mdrcEnabled = true,
                    ats2835pEmuEnabled = true
                ),
                AppProfile(
                    id = "prof_chrome",
                    packageName = PACKAGE_CHROME,
                    appName = "Chrome / Navegador",
                    presetName = "Voz & Podcasts",
                    globalGainDb = 1.0f,
                    bandGains = listOf(
                        -2.0f, -2.0f, -1.5f, -1.0f, 0.0f, 1.0f, 2.0f, 2.5f,
                        3.0f, 3.5f, 4.0f, 4.0f, 3.5f, 3.0f, 2.5f, 2.0f,
                        1.5f, 1.0f, 1.0f, 1.5f, 1.5f, 1.0f, 0.5f, 0.0f,
                        -0.5f, -1.0f, -1.5f, -2.0f, -2.5f, -3.0f, -3.5f, -4.0f
                    ),
                    bandQs = List(32) { 1.414f },
                    bassBoostDb = 0.0f,
                    bassFreqHz = 120.0f,
                    virtualizerStrength = 50,
                    limiterEnabled = true,
                    autoGainEnabled = true
                )
            )
        }
    }
}
