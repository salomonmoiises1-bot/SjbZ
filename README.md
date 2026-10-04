# SB Audio DSP Engine

SB es un procesador DSP para Android con dos backends deliberadamente separados:

1. **Audio externo del sistema:** `AudioEffect` + `DynamicsProcessing`, conectado a la sesión anunciada por el reproductor. Aquí se aplica el EQ lógico 10/20/32 mediante adaptación al número físico de bandas aceptado por el backend.
2. **PCM propio:** `Oboe` + `PcmAudioPipeline`, para cuando SB recibe PCM directamente. Oboe no captura ni intercepta automáticamente el PCM de YouTube, Spotify, TikTok u otras aplicaciones.

## Método aplicado

- EQ lógico 10/20/32 mantenido en `DspConfig`.
- Probe de topología física de `DynamicsProcessing`: se intenta la mayor cantidad compatible sin superar el banco lógico solicitado.
- Interpolación logarítmica de ganancias cuando el backend físico tiene menos bandas.
- `InputGain` real de DynamicsProcessing para Pre-Gain y ganancia global.
- Balance externo representado mediante ganancias independientes por canal.
- MDRC nativo de 4 bandas con actualización de `MbcBand` sin reconstruir el objeto para cambios de parámetros.
- Limiter nativo de DynamicsProcessing.
- BassBoost y Virtualizer nativos como efectos de sesión independientes.
- Ruta PCM ordenada como: PreGain -> Bass -> Tone -> EQ -> MDRC -> AutoGain -> Limiter/Headroom -> Spatial -> Master/Balance.
- Oboe queda disponible como backend PCM de baja latencia y no se presenta como capturador de audio de otras apps.
- No se usa `RECORD_AUDIO`.

## Punto importante sobre Tone y AutoGain

`DynamicsProcessing.EqBand` sólo expone `enabled`, `cutoffFrequency` y `gain`; no ofrece semántica pública de shelf/peaking. Por eso el Tone Biquad exacto vive en la ruta PCM. No se presenta una aproximación como si fuera equivalente en la ruta externa.

Asimismo, el AutoGain PCM calcula RMS del buffer que realmente procesa. La ruta externa no puede obtener un RMS PCM privado de otra aplicación sin entrar en una ruta de captura/permiso que SB no utiliza. Por eso no se inventa un AutoGain externo.

## Oboe

El código nativo está en:

- `app/src/main/cpp/oboe_dsp_engine.h`
- `app/src/main/cpp/oboe_dsp_engine.cpp`

El stream Oboe está configurado para baja latencia y no hace asignaciones dentro del callback. El callback actualmente entrega silencio porque no existe en este proyecto un productor PCM conectado a ese stream. `processShortArray()` y `processDirectBuffer()` sí permiten procesar PCM que un consumidor propio entregue al motor.

## Compilación

GitHub Actions instala JDK 17, Android SDK 35, NDK 26.1.10909125 y CMake 3.22.1.

La workflow ejecuta primero:

```text
python3 tools/structural_audit.py
```

y luego:

```text
./gradlew assembleDebug --stacktrace
```

La compilación Gradle requiere acceso a los repositorios Maven/Google y al wrapper de Gradle.
