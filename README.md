# RadioCaribe v0.32

Reproductor dedicado para el **Moto G3 con LineageOS 16 / Android 9**, con emisoras online y FM física.

## Sonido online
- Reproduce por **cualquier salida disponible**, con o sin cable jack: altavoz, auriculares, USB y Bluetooth.
- Restablece automáticamente el **máximo de volumen multimedia de la ruta activa** (normalmente 25/25 en el Moto G3), cuando cambia la salida y cada 4 segundos.
- Aplica **+10 dB** con `LoudnessEnhancer` a las emisoras por Internet en todas las salidas.
- ExoPlayer se encarga de la prioridad de audio y la reconexión de streams.

La FM física sigue usando el controlador root y la ruta de audio originales; no recibe el aumento de +10 dB.

**Advertencia:** volumen máximo y +10 dB pueden generar distorsión y niveles peligrosos, especialmente con auriculares o Bluetooth. Baja primero la amplificación externa. El programa restablece el volumen alto mientras está ejecutándose.

**Nota Android:** cada salida conserva su propio volumen. Las APIs públicas ajustan la ruta **activa**; RadioCaribe fijará automáticamente el máximo en cualquier nueva ruta cuando se conecte, pero no modifica simultáneamente índices de salidas desconectadas.

## Administración y APK
- Interfaz de radio horizontal, temas claro/oscuro y última emisora recordada.
- Editor de emisoras y logos por Wi-Fi en el puerto 8080. **Sin PIN:** utilizar solo en redes de confianza.
- [APK publicado en GitHub Releases](https://github.com/EncisoMath/RadioCaribe/releases/latest): `RadioCaribe_v0.32.apk`.
- Para actualizar sin desinstalar, el certificado de firma del nuevo APK debe coincidir con el anterior. El workflow intenta reutilizar la misma clave en caché.
- **Rama única:** `main`. Compilación con GitHub Actions, Android SDK 33 y Gradle 7.5.1.
