from pathlib import Path
import re, sys

root = Path(__file__).resolve().parents[1]
required = [
    "settings.gradle.kts",
    "build.gradle.kts",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/sb/dsp/DspConfig.kt",
    "app/src/main/java/com/sb/dsp/DspEngine.kt",
    "app/src/main/java/com/sb/dsp/DynamicsProcessingManager.kt",
    "app/src/main/java/com/sb/dsp/AudioEffectManager.kt",
    "app/src/main/cpp/oboe_dsp_engine.cpp",
    "app/src/main/cpp/oboe_dsp_engine.h",
]
missing = [p for p in required if not (root / p).exists()]
if missing:
    print("MISSING:")
    print("\n".join(missing))
    sys.exit(1)

src = "\n".join(p.read_text(errors="ignore") for p in (root/"app/src/main/java").rglob("*.kt"))
for symbol in ("VirtualizerManager", "AutoGainManager", "DspEngine", "DynamicsProcessingManager"):
    if symbol not in src:
        print("Missing Kotlin symbol:", symbol)
        sys.exit(1)

cmake = (root/"app/src/main/cpp/CMakeLists.txt").read_text()
for cpp in re.findall(r"add_library\([^\n]+?\s+([^\s)]+\.cpp)", cmake):
    if not (root/"app/src/main/cpp"/cpp).exists():
        print("CMake source missing:", cpp)
        sys.exit(1)

manifest = (root/"app/src/main/AndroidManifest.xml").read_text()
if "RECORD_AUDIO" in manifest:
    print("Unexpected RECORD_AUDIO permission")
    sys.exit(1)

print("SB structural audit: PASS")
