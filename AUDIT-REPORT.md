# SB — Auditoría profunda funcional EQ / DSP

Fecha: 2026-10-05
Base auditada: `SB-audit-final-EQ-presets-motor.zip`

## Resultado

- Integridad de proyecto: PASS
- Auditoría estructural/dependencias: PASS (42/42)
- Compilación aislada de `DspConfig.kt`: PASS
- Prueba virtual EQ: PASS
- ZIP final: validado con `unzip -t`

## EQ 10 / 20 / 32

Las tablas lógicas son las de `DspConfig` y `DynamicsProcessingManager` usa exactamente esas tablas.

### 10 bandas
31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000 Hz

### 20 bandas
31.5, 45, 63, 90, 125, 180, 250, 355, 500, 710, 1000, 1400, 2000, 2800, 4000, 5600, 8000, 11200, 16000, 20000 Hz

### 32 bandas
20, 25, 31, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 14000, 16000, 20000 Hz

## Cambio de modo

Android `DynamicsProcessing` fija la cantidad de bandas al crear la topología. Por eso 10 -> 20 -> 32 reconstruye el backend solo cuando cambia `eqMode`. Un movimiento normal de un fader no reconstruye la topología; actualiza ganancias sobre las bandas existentes.

Si el hardware acepta exactamente 10, 20 o 32 bandas físicas y el sample rate permite todos los cortes, el backend usa las frecuencias exactas 1:1 y calcula la ganancia en esos mismos puntos.

Si el hardware solo ofrece menos bandas (por ejemplo 5), es físicamente imposible aplicar 32 filtros independientes. En ese caso se conserva la curva lógica completa y se proyecta mediante una aproximación de mínimos cuadrados sobre las bandas físicas disponibles.

## Presets

`DspConfigStore` y `PresetRepository` mantienen tres bancos independientes: `eq10`, `eq20`, `eq32`. Cambiar de modo no destruye los valores de los otros modos.

## Otras funciones auditadas

- Motor general ON/OFF: PASS.
- EQ ON/OFF: PASS.
- Pre-Gain: PASS.
- Bass Boost: integrado en la curva física pre-EQ: PASS.
- Tone: graves/medios/agudos integrados sin reconstrucción por movimiento: PASS.
- MDRC/MBC: 4 bandas: PASS.
- Limiter: PASS.
- AutoGain: PASS en la ruta de control.
- Master Gain: PASS.
- Balance: PASS.
- Virtualizer: PASS con protección para evitar activación a fuerza cero.
- Presets completos: PASS.
- Servicio foreground: PASS.
- Sesión global 0: PASS.
- Sin `RECORD_AUDIO`, MediaProjection ni backend PCM/Oboe oculto: PASS.
- Botón de motor general en UI: PASS.
- Acción de activar/desactivar motor desde la notificación: añadido y auditado.

## Limitación de verificación

No fue posible ejecutar `./gradlew assembleDebug` en este entorno porque el wrapper necesita descargar Gradle 8.9 y el entorno de ejecución no tiene acceso de red. No se declara un build Android completo como PASS por ese motivo.
