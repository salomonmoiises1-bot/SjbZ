from pathlib import Path
import re, sys

root = Path(__file__).resolve().parents[1]
required = [
    "settings.gradle.kts", "build.gradle.kts", "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/sb/sb/dsp/DspConfig.kt",
    "app/src/main/java/com/sb/sb/dsp/DspEngine.kt",
    "app/src/main/java/com/sb/sb/dsp/DynamicsProcessingManager.kt",
    "app/src/main/java/com/sb/sb/dsp/AudioEffectManager.kt",
    "app/src/main/java/com/sb/sb/dsp/DspConfigStore.kt",
    "app/src/main/java/com/sb/sb/dsp/PresetRepository.kt",
    "app/src/main/java/com/sb/sb/dsp/service/SbDspForegroundService.kt",
    "app/src/main/java/com/sb/sb/dsp/session/AudioSessionReceiver.kt",
    "app/src/main/java/com/sb/ui/EqualizerScreen.kt",
    "app/src/main/java/com/sb/ui/MainViewModel.kt",
]
missing = [p for p in required if not (root / p).exists()]
if missing:
    print("MISSING:")
    print("\n".join(missing)); sys.exit(1)

files = {p: (root/p).read_text(errors="ignore") for p in required if (root/p).suffix in {".kt", ".xml"}}
src = "\n".join(files.values())

checks = []
def check(name, ok, detail=""):
    checks.append((name, ok, detail))

# Architecture / forbidden paths.
for symbol in ("DspEngine", "DynamicsProcessingManager", "AudioEffectManager", "DspConfigStore", "PresetRepository"):
    check(f"symbol:{symbol}", symbol in src)
for forbidden in ("NativeDspBridge", "PcmAudioPipeline", "ExternalEqualizerFallback", "OboeDspEngine", "com.google.oboe"):
    check(f"no-dead-backend:{forbidden}", forbidden not in src and forbidden not in (root/"app/build.gradle.kts").read_text())
manifest = files["app/src/main/AndroidManifest.xml"]
check("no-RECORD_AUDIO", "RECORD_AUDIO" not in manifest)
check("no-MediaProjection", "MediaProjection" not in src)

# Exact logical frequency tables.
dsp = files["app/src/main/java/com/sb/sb/dsp/DspConfig.kt"]
dyn = files["app/src/main/java/com/sb/sb/dsp/DynamicsProcessingManager.kt"]
expected = {
    10: [31,63,125,250,500,1000,2000,4000,8000,16000],
    20: [31.5,45,63,90,125,180,250,355,500,710,1000,1400,2000,2800,4000,5600,8000,11200,16000,20000],
    32: [20,25,31,40,50,63,80,100,125,160,200,250,315,400,500,630,800,1000,1250,1600,2000,2500,3150,4000,5000,6300,8000,10000,12500,14000,16000,20000]
}
for n, vals in expected.items():
    pattern = rf"FREQUENCIES_{n}\s*=\s*floatArrayOf\(\s*" + r"\s*,\s*".join(re.escape(f"{v:g}f") for v in vals) + r"\s*\)"
    check(f"DspConfig frequencies {n}", re.search(pattern, dsp) is not None)
    check(f"DP uses DspConfig frequencies {n}", f"private val EQ{n} = DspConfig.FREQUENCIES_{n}" in dyn)

# Mode switching and independent storage.
vm = files["app/src/main/java/com/sb/ui/MainViewModel.kt"]
equi = files["app/src/main/java/com/sb/ui/EqualizerScreen.kt"]
preset = files["app/src/main/java/com/sb/sb/dsp/PresetRepository.kt"]
store = files["app/src/main/java/com/sb/sb/dsp/DspConfigStore.kt"]
for n in (10,20,32):
    check(f"UI mode {n}", f"BANDS_{n}" in equi)
    check(f"VM bank {n}", f"gains{n}BandDb" in vm)
    check(f"preset bank {n}", f'"eq{n}"' in preset)
    check(f"store bank {n}", f'"eq{n}_' in store)
check("setEqBand selects active bank", "fun setEqBand(index: Int, db: Float)" in vm and all(x in vm for x in ("gains10BandDb", "gains20BandDb", "gains32BandDb")))
check("mode switch rebuilds native topology", "previous.eqMode != config.eqMode" in dyn and "restartForEqTopology(config)" in dyn)
check("live EQ does not rebuild topology", "applyEqGainsOnly(effect, config)" in dyn)
check("requested physical topology first", "add(requested)" in dyn)
check("exact 1:1 logical/native EQ mapping", "n == freqs.size" in dyn and "return EqMap(\n                freqs.copyOf()" in dyn)
check("HAL fallback", "PHYSICAL_CANDIDATES" in dyn and "5" in dyn)
check("MDRC four bands", "true, 4" in dyn and "getMbcByChannelIndex" in dyn)
check("limiter", "setLimiterByChannelIndex" in dyn)
check("virtualizer", "Virtualizer" in files["app/src/main/java/com/sb/sb/dsp/AudioEffectManager.kt"])
check("master enable", "effect.enabled = config.masterEnabled" in dyn)
service = files["app/src/main/java/com/sb/sb/dsp/service/SbDspForegroundService.kt"]
check("notification master toggle", "ACTION_TOGGLE_MASTER" in service and "Desactivar motor" in service and "Activar motor" in service)
check("preset preserves all EQ banks", all(f'"eq{n}"' in preset for n in (10,20,32)))

failed = [x for x in checks if not x[1]]
for name, ok, detail in checks:
    print(("PASS " if ok else "FAIL ") + name + (f" — {detail}" if detail else ""))
print(f"\nSB structural/dependency audit: {'PASS' if not failed else 'FAIL'} ({len(checks)-len(failed)}/{len(checks)})")
if failed: sys.exit(1)
