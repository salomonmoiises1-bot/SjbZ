# SB Audio DSP Engine (Android Native)

Motor de ecualización y procesamiento de señal digital de baja latencia para Android.
Paquete: com.sb.dsp

## Compilación en GitHub Actions
Este proyecto incluye el flujo automatizado en `.github/workflows/build-android.yml`.
1. Sube este repositorio a GitHub (`git push -u origin main`).
2. Ve a la pestaña **Actions** en tu repositorio de GitHub.
3. El APK se compilará automáticamente con JDK 17, Android SDK 35 y Android NDK 26.
4. Descarga el archivo compilado desde **Artifacts: SB-DSP-Debug-APK**.

## Compilación local en Android Studio
1. Abre esta carpeta en Android Studio Hedgehog / Ladybug o superior.
2. Ejecuta:
```bash
chmod +x gradlew
./gradlew assembleDebug
```
El APK compilado estará en `app/build/outputs/apk/debug/app-debug.apk`.
