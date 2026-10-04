# SB — Procesador DSP de Audio Nativo para Android

**SB** es un procesador y controlador DSP de audio nativo de alto rendimiento para el sistema operativo Android (API 28+ / Android 9.0 a Android 14+), desarrollado exclusivamente con **Kotlin** y **Jetpack Compose**, tomando como referencia arquitectónica y funcional el proyecto `Equalizer314`.

> **NOTA DE ARQUITECTURA:** SB es una aplicación **100% nativa de Android** (`com.sb`). No es un sitio ni una aplicación web. Interactúa directamente con la capa HAL (Hardware Abstraction Layer) de Android y el kernel de audio a través de los servicios nativos `android.media.audiofx.DynamicsProcessing`, `android.media.audiofx.Equalizer`, `android.media.audiofx.BassBoost` y `android.media.audiofx.Virtualizer`.

---

## 1. Identidad y Paquete

* **Nombre de la Aplicación:** SB
* **Package ID:** `com.sb`
* **Nombre de Proyecto Gradle:** `SB`
* **APK Generado:** `SB.apk`
* **Arquitectura de Interfaz:** Jetpack Compose (Material Design 3 en modo oscuro pro-audio)
* **Idioma de la Interfaz:** Español

---

## 2. Arquitectura de la Cadena DSP Nativa

El procesamiento lógico se estructura de forma estrictamente secuencial y desacoplada:

```
INPUT (Flujo de Audio del Dispositivo / Sesión)
  ↓
PRE-GAIN / PREAMP (-20 dB a +20 dB)
  ↓
BASS BOOST (Refuerzo hardware nativo o Pre-EQ)
  ↓
TONE (3 Vías: Bass @ 100 Hz, Mid @ 1 kHz, Treble @ 10 kHz)
  ↓
EQUALIZER (10, 20 o 32 Bandas ISO con factor Q constante)
  ↓
MDRC / MBC (Compresor Multibanda Dinámico de 4 Zonas: Low, Low-Mid, High-Mid, High)
  ↓
AUTO GAIN (Nivelador de sonoridad LUFS con anti-pumping)
  ↓
AUTO HEADROOM (Cálculo dinámico de atenuación protectora ante boosts)
  ↓
LIMITER (Limitador Brickwall pico final contra sobremodulación)
  ↓
VIRTUALIZER / SPATIAL (Expansión estéreo de campo acústico)
  ↓
MASTER GAIN (-20 dB a +12 dB)
  ↓
BALANCE (-1.0 Izquierda a +1.0 Derecha)
  ↓
OUTPUT (Salida hacia HAL / Auriculares / Altavoces)
```

---

## 3. Especificación Técnica Profunda del MDRC (Compresión Multibanda)

El motor **MDRC (Multiband Dynamic Range Compression)** de SB implementa una arquitectura híbrida de alta fidelidad:

### 3.1. División en 4 Bandas Espectrales (Crossovers)
El espectro se divide mediante un árbol de filtros **Linkwitz-Riley de 4º orden (LR4 / 24 dB/octava)** en fase perfecta:
* **Banda 1 (LOW):** $0 \text{ Hz} \rightarrow c_1$ (Por defecto: $160 \text{ Hz}$) — Graves profundos, bombos y sub-bajos.
* **Banda 2 (LOW-MID):** $c_1 \rightarrow c_2$ (Por defecto: $160 \text{ Hz} \rightarrow 800 \text{ Hz}$) — Cuerpo, cajas, fundamentales vocales y bajos.
* **Banda 3 (HIGH-MID):** $c_2 \rightarrow c_3$ (Por defecto: $800 \text{ Hz} \rightarrow 4000 \text{ Hz}$) — Presencia vocal, guitarras, teclados y definición.
* **Banda 4 (HIGH):** $c_3 \rightarrow c_4$ (Por defecto: $4000 \text{ Hz} \rightarrow 20000 \text{ Hz}$) — Aire, platillos, sibilancias y brillo.

**Regla de consistencia estructural:** Las frecuencias de corte deben cumplir estrictamente:
$$c_1 < c_2 < c_3 < c_4$$

### 3.2. Parámetros de Dinámica por Zona
Cada una de las 4 bandas cuenta con controles independientes:
* **Umbral (Threshold):** $-60.0 \text{ dB}$ a $0.0 \text{ dB}$.
* **Relación (Ratio):** $1.0:1$ a $20.0:1$ (expansión/compresión).
* **Tiempo de Ataque (Attack Time):** $0.5 \text{ ms}$ a $500 \text{ ms}$.
* **Tiempo de Relajación (Release Time):** $5 \text{ ms}$ a $1500 \text{ ms}$.
* **Curvatura de Codo (Knee Width):** $0.0 \text{ dB}$ (Hard-Knee) a $24.0 \text{ dB}$ (Soft-Knee parabólico).
* **Pre-Gain y Post-Gain:** $-15.0 \text{ dB}$ a $+15.0 \text{ dB}$ por banda para makeup transparente.

### 3.3. Integración con Android AudioFX y Fallback
* **Dispositivos con Android 9.0+ (API 28+):** Se enruta a `DynamicsProcessing.MbcBand` dentro de la etapa MBC del HAL de audio para aceleración por hardware con latencia mínima.
* **Separación Realtime vs Estructural:** Los ajustes de threshold, ratio, attack, release y gains se transmiten instantáneamente sin clicks ni silencios. Solo la alteración de frecuencias de crossover dispara reconstrucción segura.
* **Motor Nativo de Software (`MdrcProcessor`):** Incluye procesador nativo con Linkwitz-Riley y detectores de envolvente híbridos Peak/RMS con cálculo de vúmetros de Reducción de Ganancia (GR en dB).

---

## 4. Limitación Fundamental de Android y Honestidad Técnica

Android no garantiza que todos los fabricantes (Samsung, Xiaomi, Google, Sony, OnePlus) expongan las mismas capacidades de efectos de audio.

**SB implementa la regla de oro: NUNCA fingir capacidades inexistentes.**

* **Sesión Global 0 (`GLOBAL_SESSION_ID = 0`):** SB intenta inicializar los efectos en la sesión 0. Si el fabricante del dispositivo bloquea la sesión global en Android 10+, SB no simula procesamiento: detecta la restricción, pasa al estado `DEGRADED`, informa la causa en el Diagnóstico Técnico, y conmuta automáticamente a las sesiones activas notificadas por reproductores mediante el broadcast `android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION`.
* **Mapeo de Bandas (Logical vs Hardware):** Si el dispositivo solo ofrece 5 bandas nativas en su ecualizador de hardware pero el usuario selecciona el modo **EQ32**, `CapabilityAdapter` interpola acústicamente las 32 bandas lógicas sobre las 5 bandas físicas reales y expone con total transparencia:
  * *Bandas Lógicas:* 32
  * *Bandas Físicas Aplicadas:* 5
* **Sin Permisos Innecesarios:** SB **NO** utiliza `RECORD_AUDIO` ni `MediaProjection`. SB es un controlador de efectos real de audio, no una grabadora oculta ni un reproductor simulado.

---

## 5. Resolución del Requisito EQ32 (Exactamente 32 Bandas)

La norma acústica ISO estándar para 1/3 de octava define 31 frecuencias entre 20 Hz y 20 kHz:
`[20, 25, 31, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000]`.

Para satisfacer de manera acústicamente rigurosa el requisito de **EQ32**, SB integra como **32ª frecuencia** la frecuencia de **16 Hz** (sub-bass anchor ISO logarítmico inmediatamente inferior a 20 Hz). De este modo, la matriz cuenta con exactamente **32 bandas** que cubren el espectro completo desde el subgrave más profundo hasta el límite audible humano.

---

## 6. Permisos de Android Utilizados

```xml
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

---

## 7. Compilación con Gradle y GitHub Actions

### Requisitos Locales:
* JDK 17
* Android SDK 34 (Android 14)
* Gradle 8.5+

### Compilación desde Terminal:
```bash
# Otorgar permisos de ejecución al wrapper
chmod +x gradlew

# Ejecutar suite de pruebas unitarias
./gradlew test

# Compilar APK final para instalación
./gradlew assembleRelease
```
El APK se genera automáticamente como **`SB.apk`** en `app/build/outputs/apk/release/SB.apk`.

### Integración Continua (CI):
El flujo de trabajo automatizado en `.github/workflows/build.yml` ejecuta los tests, compila el APK firmado para depuración/instalación y lo publica como artefacto listo para descarga directa.
