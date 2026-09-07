# SjbZ - Offline Android Audio Player

Un reproductor de audio local y offline para Android construido desde cero con **AndroidX Media3 ExoPlayer**, procesamiento avanzado de audio con **DynamicsProcessing (MDRC)** y fallback transparente a **Equalizer legacy**.

Diseñado y configurado específicamente para compilar de forma limpia y salir verde en **GitHub Actions**.

---

## 📋 Especificaciones del Proyecto

- **App Name:** `SjbZ`
- **Package ID:** `com.sjbz.player`
- **minSdk:** `26` (Android 8.0 Oreo)
- **targetSdk:** `34` (Android 14)
- **compileSdk:** `34` (Android 14)
- **Java:** `17` (Temurin)

---

## 🛠️ Tecnologías y Dependencias

- **Reproducción de Audio:**
  - `androidx.media3:media3-exoplayer:1.2.1`
  - `androidx.media3:media3-session:1.2.1`
  - `androidx.media3:media3-common:1.2.1`
- **UI:**
  - `com.google.android.material:material:1.11.0`
  - `androidx.appcompat:appcompat:1.6.1`
  - `androidx.recyclerview:recyclerview:1.3.2`
  - `androidx.constraintlayout:constraintlayout:2.1.4`

---

## 🎵 Formatos de Audio Soportados (Offline)

El escaneo de medios mediante `MediaStore.Audio` detecta e indexa automáticamente archivos con las extensiones:
- **MP3** (`.mp3`)
- **FLAC** (`.flac`)
- **WAV** (`.wav`)
- **M4A** (`.m4a`)
- **OGG** (`.ogg`)
- **OPUS** (`.opus`)
- **WMA** (`.wma`)

Sin dependencias externas de streaming, sin YouTube y sin radio online. Totalmente offline.

---

## 🎛️ Procesamiento de Audio & MDRC

- **MDRCAdapter (9 parámetros):**
  - `cutoffs` (Hz)
  - `gains` (dB)
  - `thresholds` (dB)
  - `ratios`
  - `attacks` (ms)
  - `releases` (ms)
  - `knees` (dB)
  - `enabled` (boolean)
  - `bandNames` (String[])
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
