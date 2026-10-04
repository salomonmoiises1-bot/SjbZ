# SB Audio DSP

SB is a system-wide Android DSP controller. The external-audio backend uses Android `DynamicsProcessing` on session 0, with a logical 10/20/32-band EQ projected onto the device's real DP EQ capacity. MBC and limiter are native DP stages. `Visualizer` provides external-path metering and AutoGain feedback. Oboe remains an independent app-owned PCM backend and never captures PCM from other apps.

## Processing model
Input Gain (Pre-Gain/AutoGain/Master/Balance) -> DP Pre-EQ (logical EQ + Tone response) -> 4-band MBC -> Limiter -> system output. BassBoost and Virtualizer remain optional Android effects on the same session.

The project intentionally does not request RECORD_AUDIO or MediaProjection.
