# Integration boundary and validation

## Proven

- PCM -> 32 Constant-Q RBJ filters is mathematically valid.
- The same engine works at 44.1/48/96 kHz.
- Per-band gain is constrained to -24..+24 dB.
- Stereo channels have independent filter state.
- Native engine has a host stress test.

## Not claimed by the APK

A normal Android APK cannot, using public application APIs, register `libsb_eq32.so` as a new global AudioFlinger effect for every other application. That registration belongs to the platform/vendor audio-effects configuration.

Therefore this ZIP contains the real native effect implementation and platform integration artifacts, but it does not falsely claim that installing the APK alone inserts the effect into a stock Android image.

## Required platform verification

After installing the effect into a test Android image, verify the actual chain with AudioFlinger/effect diagnostics and confirm that the SB effect receives non-zero PCM frames after DynamicsProcessing. Only then should the system-wide EQ32 path be marked operational.
