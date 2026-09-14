# SjbZ AIMP 1.9.22 Cyan - Offline Android Audio Player

Un reproductor de audio local y offline de alta fidelidad para Android construido con **AndroidX Media3 ExoPlayer**, procesamiento avanzado de audio mediante **DynamicsProcessing (PreEQ 32 bandas + MDRC 5 bandas + BassBoost ATS2835P)** y arquitectura moderna basada en **MediaSessionService**.

Diseñado y verificado para compilar limpiamente en **GitHub Actions** con JDK 17 y Gradle 8.5.

---

## 📋 Especificaciones del Proyecto

- **App Name:** `SB-Z` (SjbZ AIMP Cyan)
- **Version:** `1.9.22` (VersionCode: `10`)
- **Package ID:** `com.sjbz.aimp`
- **minSdk:** `26` (Android 8.0 Oreo)
- **targetSdk:** `34` (Android 14)
- **compileSdk:** `34` (Android 14)
- **Kotlin:** `1.9.22`
- **Java:** `17` (Temurin)
- **Tema Visual:** Cyan Studio (`#00E5FF`) con fondo Obsidian (`#0A0E17`)

---

## 🛠️ Tecnologías y Dependencias Principales

- **Reproducción de Audio (AndroidX Media3 1.3.1):**
  - `androidx.media3:media3-exoplayer:1.3.1`
  - `androidx.media3:media3-session:1.3.1`
  - `androidx.media3:media3-ui:1.3.1`
  - `androidx.media3:media3-common:1.3.1`
- **Servicio en Primer Plano:**
  - `PlaybackService` heredando de `MediaSessionService` con control de reproducción y notificaciones multimedia usando `ic_skip_previous` y `ic_skip_next`.
- **UI & Material Design:**
  - `com.google.android.material:material:1.11.0`
  - `androidx.appcompat:appcompat:1.6.1`
  - `androidx.recyclerview:recyclerview:1.3.2`
  - `androidx.constraintlayout:constraintlayout:2.1.4`
- **Persistencia Local:**
  - `androidx.room:room-runtime:2.6.1` & `room-ktx:2.6.1` con SQLite embebido.

---

## 🎵 Formatos de Audio Soportados (100% Offline)

El escaneo de medios mediante `MediaStore.Audio` indexa de forma nativa:
- **MP3** (`.mp3`)
- **FLAC** (`.flac` - 24-bit Hi-Res)
- **WAV** (`.wav` - PCM sin compresión)
- **M4A / AAC** (`.m4a`, `.aac`)
- **OGG / OPUS** (`.ogg`, `.opus`)
- **WMA** (`.wma`)

---

## 🎛️ Procesamiento de Audio DSP de Estudio

1. **Ecualizador Pre-EQ de 32 Bandas ISO:**
   - Frecuencias centrales: 25 Hz, 31 Hz, 40 Hz, 50 Hz, 63 Hz, 80 Hz, 100 Hz, 125 Hz, 160 Hz, 200 Hz, 250 Hz, 315 Hz, 400 Hz, 500 Hz, 630 Hz, 800 Hz, 1 kHz, 1.25 kHz, 1.6 kHz, 2 kHz, 2.5 kHz, 3.15 kHz, 4 kHz, 5 kHz, 6.3 kHz, 8 kHz, 10 kHz, 12.5 kHz, 16 kHz, 18 kHz, 19 kHz y 20 kHz.
   - Configuración directa sobre `dynamicsProcessing.setPreEqBandAllChannelsTo(band, EqBand)`.
2. **Compresor Multibanda Dinámico (MDRC) de 5 Bandas:**
   - Bandas: Sub-Bass, Low-Mid, Midrange, High-Mid y Treble.
   - Control de ganancia independiente por banda (-12 dB a +12 dB) y limitador post-procesamiento.
3. **Motor Psicoacústico de Graves ATS2835P:**
   - 3 puntos de corte armónico seleccionables: 60 Hz (Sub), 85 Hz (Punch) y 120 Hz (Mid-Bass).
4. **Fallback Transparente:**
   - Protección con detección de API 28+ y bloques try-catch seguros contra excepciones de hardware o HAL.
- **Compatibilidad de Efectos:**
  - En `Build.VERSION.SDK_INT >= 28` (Android 9+), utiliza `DynamicsProcessing` con compresión multibanda (MDRC) usando `setMbcBandByChannelIndex` (estándar de Android API 28-34). **No utiliza `setMbcBand`**, el cual no existe en la API.
  - En versiones anteriores o en caso de excepción de hardware/HAL, realiza fallback seguro y silencioso a `Equalizer` legacy, con `try-catch` para garantizar que la app **nunca crashea**.

---

## 🚀 GitHub Actions CI (`.github/workflows/android.yml`)

El flujo de trabajo está listo para que el build salga verde inmediatamente:

```yaml
name: Android CI

on:
  push:
    branches: [ "main", "master" ]
  pull_request:
    branches: [ "main", "master" ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v3

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Assemble Debug APK
        run: ./gradlew assembleDebug --stacktrace
```

---

## 📦 Instrucciones para subir a GitHub

Para subir el proyecto a un repositorio de GitHub nuevo:

```bash
cd SjbZ
git init
git add .
git commit -m "Initial commit: SjbZ Android Studio project"
git branch -M main
git remote add origin https://github.com/TU_USUARIO/SjbZ.git
git push -u origin main
```

Una vez realizado el push, ve a la pestaña **Actions** en GitHub para ver la Action ejecutándose y finalizando en verde con el APK generado.
