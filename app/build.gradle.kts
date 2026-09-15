plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.sjbz.aimp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sjbz.aimp"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.9.22"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf("-opt-in=androidx.media3.common.util.UnstableApi")
    }

    buildFeatures {
        viewBinding = false
    }
}

dependencies {
    // Core AndroidX and Material 3
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // Lifecycle & Coroutines
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Media3 ExoPlayer (1.3.1)
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")
    implementation("androidx.media:media:1.6.0")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Gson JSON Parser
    implementation("com.google.code.gson:gson:2.10.1")

    // Coil - Carga de carátulas locales
    implementation("io.coil-kt:coil:2.5.0")

    // Palette - Colores dinámicos desde carátula
    implementation("androidx.palette:palette-ktx:1.0.0")

    // ViewPager2 - Swipe entre carátula / lista / ecualizador
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // Preference - Pantalla de ajustes para DSP / Hi-Res
    implementation("androidx.preference:preference-ktx:1.2.1")

    // DocumentFile - Mejor acceso a carpetas USB / SD con SAF
    implementation("androidx.documentfile:documentfile:1.0.1")
}
