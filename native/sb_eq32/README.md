# SB EQ32 — native PCM effect layer

This module is the native side of the new SB audio path. It implements 32 independent RBJ peaking biquads at the exact SB EQ32 frequencies and is designed to sit inside the Android effect chain after DynamicsProcessing.

## Runtime path

`AudioFlinger mixer -> DynamicsProcessing -> SB EQ32 native effect -> HAL`

The Kotlin APK remains the controller for the DSP configuration. The native effect is the component that receives the actual PCM buffer from the audio-effect framework and transforms it in-place/copy-to-output.

## Important platform boundary

An ordinary third-party APK cannot register a new AudioFlinger effect library by copying a `.so` into its private application directory. The `platform/sb_eq32_effect.cpp` adapter and `audio_effects.xml.fragment` therefore belong to an Android platform/vendor build (or an equivalently privileged/rooted test image). The normal Gradle APK build does not pretend to install or register this library globally.

`src/sb_eq32.cpp` is deliberately independent of Android framework headers so it can be mathematically/unit tested on the host and reused by the platform adapter.

## Integration

1. Build `libsb_eq32.so` in the target platform/vendor audio tree using `platform/Android.bp`.
2. Install it in the platform/vendor soundfx library location used by the target image.
3. Merge `platform/audio_effects.xml.fragment` into the target audio-effects configuration.
4. Attach the effect UUID after the DynamicsProcessing effect in the target EffectChain.
5. Keep the APK's existing Kotlin controls as the parameter source; the native adapter should expose the final 32 gains through the effect command/parameter contract before production use.

The adapter intentionally does not invent a Java API for reading DynamicsProcessing output. AudioFlinger supplies the PCM buffer to the native effect.

## APK build policy

The Gradle APK deliberately does not force the NDK build. The native AudioFlinger effect is a platform/vendor component and must be compiled in the target Android image. This keeps the existing GitHub APK workflow reproducible while shipping the complete native effect source and platform build metadata in the repository.
