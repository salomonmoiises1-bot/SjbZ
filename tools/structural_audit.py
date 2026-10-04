from pathlib import Path
import re, sys

root = Path(__file__).resolve().parents[1]
required = [
    "settings.gradle.kts", "build.gradle.kts", "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/sb/dsp/DspConfig.kt",
    "app/src/main/java/com/sb/dsp/DspEngine.kt",
    "app/src/main/java/com/sb/dsp/DynamicsProcessingManager.kt",
    "app/src/main/java/com/sb/dsp/AudioEffectManager.kt",
    "app/src/main/java/com/sb/dsp/DspConfigStore.kt",
    "app/src/main/java/com/sb/dsp/PresetRepository.kt",
    "app/src/main/java/com/sb/dsp/service/SbDspForegroundService.kt",
    "app/src/main/java/com/sb/dsp/session/AudioSessionReceiver.kt",
]
missing = [p for p in required if not (root / p).exists()]
if missing:
    print("MISSING:")
    print("\n".join(missing))
    sys.exit(1)

java_root = root / "app/src/main/java"
src = "\n".join(p.read_text(errors="ignore") for p in java_root.rglob("*.kt"))
for symbol in ("DspEngine", "DynamicsProcessingManager", "AudioEffectManager", "DspConfigStore", "PresetRepository"):
    if symbol not in src:
        print("Missing Kotlin symbol:", symbol); sys.exit(1)

for forbidden in ("NativeDspBridge", "PcmAudioPipeline", "ExternalEqualizerFallback", "OboeDspEngine", "com.google.oboe"):
    if forbidden in src or forbidden in (root / "app/build.gradle.kts").read_text():
        print("Unexpected dead backend reference:", forbidden); sys.exit(1)

manifest = (root / "app/src/main/AndroidManifest.xml").read_text()
if "RECORD_AUDIO" in manifest or "MediaProjection" in manifest:
    print("Unexpected capture permission/backend"); sys.exit(1)

print("SB structural audit: PASS")
