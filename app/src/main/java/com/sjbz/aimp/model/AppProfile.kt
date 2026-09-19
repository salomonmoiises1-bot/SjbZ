package com.sjbz.aimp.model
import java.io.Serializable
data class AppProfile(
    val id: String,
    val appPackage: String,
    val appName: String,
    val presetName: String = "Plano",
    val preampDb: Float = 0f,
    val bandGains: FloatArray = FloatArray(32){0f},
    val bassBoostDb: Float = 0f,
    val bassBoostFreq: Float = 85f,
    val virtualizerStrength: Int = 0,
    val isLimiterEnabled: Boolean = true,
    val isMdrcEnabled: Boolean = true,
    val isAts2835pEnabled: Boolean = false,
    val ats2835pAmount: Float = 0.8f
): Serializable {
    val packageName get() = appPackage
    companion object {
        fun createDefaultProfiles() = listOf(
            AppProfile("global","global","Sistema"),
            AppProfile("spotify","com.spotify.music","Spotify", bassBoostDb=3f, isAts2835pEnabled=true),
            AppProfile("youtube","com.google.android.youtube","YouTube"),
            AppProfile("ytm","com.google.android.apps.youtube.music","YT Music", bassBoostDb=4f),
            AppProfile("chrome","com.android.chrome","Chrome")
        )
    }
}
