package com.sjbz.aimp.model

import java.io.Serializable
import java.util.UUID

/**
 * AppProfile: Estructura de datos para perfiles de audio por aplicación.
 * Totalmente compatible con MainActivity.kt, GlobalAudioSessionManager.kt y procesador DSP PCM.
 */
data class AppProfile(
    val id: String = UUID.randomUUID().toString(),
    val appName: String,
    val presetName: String = "Personalizado",
    val appPackage: String = "",
    val preampDb: Float = 0.0f,
    val bassGainDb: Float = 0.0f,
    val midGainDb: Float = 0.0f,
    val trebleGainDb: Float = 0.0f,
    val bandGains: FloatArray = FloatArray(32) { 0.0f },
    val bassBoostDb: Float = 0.0f,
    val bassBoostFreq: Float = 85.0f,
    val virtualizerStrength: Int = 0,
    val isMdrcEnabled: Boolean = true,
    val isLimiterEnabled: Boolean = true,
    val isAts2835pEnabled: Boolean = false,
    val ats2835pAmount: Float = 0.8f,
    val autoGainEnabled: Boolean = true,
    val autoGainTargetLufs: Float = -14.0f
) : Serializable {

    // Alias para compatibilidad con código antiguo que use packageName
    val packageName: String get() = appPackage

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AppProfile

        if (id != other.id) return false
        if (appName != other.appName) return false
        if (presetName != other.presetName) return false
        if (appPackage != other.appPackage) return false
        if (preampDb != other.preampDb) return false
        if (!bandGains.contentEquals(other.bandGains)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + appName.hashCode()
        result = 31 * result + appPackage.hashCode()
        result = 31 * result + bandGains.contentHashCode()
        return result
    }

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
                    appPackage = PACKAGE_GLOBAL,
                    appName = "Sistema Global",
                    presetName = "Plano Hi-Fi",
                    preampDb = 0.0f,
                    bandGains = FloatArray(32) { 0.0f },
                    isLimiterEnabled = true,
                    autoGainEnabled = true
                ),
                AppProfile(
                    id = "prof_spotify",
                    appPackage = PACKAGE_SPOTIFY,
                    appName = "Spotify",
                    presetName = "Audiophile V-Curve",
                    preampDb = 1.0f,
                    bassGainDb = 2.0f,
                    trebleGainDb = 2.5f,
                    bandGains = floatArrayOf(
                        4.5f, 4.0f, 3.5f, 3.0f, 2.5f, 2.0f, 1.5f, 1.0f,
                        0.5f, 0.0f, -0.5f, -1.0f, -1.0f, -0.5f, 0.0f, 0.5f,
                        0.5f, 1.0f, 1.5f, 2.0f, 2.0f, 2.5f, 3.0f, 3.0f,
                        3.5f, 4.0f, 4.0f, 4.5f, 4.5f, 4.0f, 3.5f, 3.0f
                    ),
                    bassBoostDb = 3.5f,
                    bassBoostFreq = 85.0f,
                    virtualizerStrength = 200,
                    isLimiterEnabled = true,
                    autoGainEnabled = true,
                    isMdrcEnabled = true,
                    isAts2835pEnabled = true,
                    ats2835pAmount = 0.8f
                ),
                AppProfile(
                    id = "prof_youtube",
                    appPackage = PACKAGE_YOUTUBE,
                    appName = "YouTube",
                    presetName = "Claridad Vocal & Dinámica",
                    preampDb = 2.0f,
                    midGainDb = 3.0f,
                    bandGains = floatArrayOf(
                        -1.0f, -1.0f, -0.5f, 0.0f, 0.5f, 1.0f, 1.5f, 2.0f,
                        2.5f, 3.0f, 3.5f, 3.5f, 3.0f, 2.5f, 2.0f, 1.5f,
                        1.0f, 1.5f, 2.0f, 2.5f, 2.5f, 2.0f, 1.5f, 1.0f,
                        0.5f, 0.0f, -0.5f, -1.0f, -1.5f, -2.0f, -2.5f, -3.0f
                    ),
                    bassBoostDb = 1.0f,
                    bassBoostFreq = 120.0f,
                    virtualizerStrength = 100,
                    isLimiterEnabled = true,
                    autoGainEnabled = true,
                    isMdrcEnabled = true
                ),
                AppProfile(
                    id = "prof_yt_music",
                    appPackage = PACKAGE_YT_MUSIC,
                    appName = "YouTube Music",
                    presetName = "Deep Bass & Club",
                    preampDb = 1.5f,
                    bassGainDb = 4.0f,
                    bandGains = floatArrayOf(
                        5.5f, 5.0f, 4.5f, 4.0f, 3.5f, 2.5f, 1.5f, 0.5f,
                        0.0f, -0.5f, -1.0f, -1.0f, -0.5f, 0.0f, 0.5f, 1.0f,
                        1.0f, 1.5f, 2.0f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f,
                        4.0f, 4.0f, 3.5f, 3.0f, 2.5f, 2.0f, 1.5f, 1.0f
                    ),
                    bassBoostDb = 5.0f,
                    bassBoostFreq = 60.0f,
                    virtualizerStrength = 250,
                    isLimiterEnabled = true,
                    autoGainEnabled = true,
                    isMdrcEnabled = true,
                    isAts2835pEnabled = true,
                    ats2835pAmount = 0.9f
                ),
                AppProfile(
                    id = "prof_chrome",
                    appPackage = PACKAGE_CHROME,
                    appName = "Chrome / Navegador",
                    presetName = "Voz & Podcasts",
                    preampDb = 1.0f,
                    midGainDb = 2.0f,
                    bandGains = floatArrayOf(
                        -2.0f, -2.0f, -1.5f, -1.0f, 0.0f, 1.0f, 2.0f, 2.5f,
                        3.0f, 3.5f, 4.0f, 4.0f, 3.5f, 3.0f, 2.5f, 2.0f,
                        1.5f, 1.0f, 1.0f, 1.5f, 1.5f, 1.0f, 0.5f, 0.0f,
                        -0.5f, -1.0f, -1.5f, -2.0f, -2.5f, -3.0f, -3.5f, -4.0f
                    ),
                    bassBoostDb = 0.0f,
                    bassBoostFreq = 120.0f,
                    virtualizerStrength = 50,
                    isLimiterEnabled = true,
                    autoGainEnabled = true
                )
            )
        }
    }
}
