# RadioCaribe v0.34

Reproductor dedicado para el **Moto G3 con LineageOS 16 / Android 9**, con emisoras online y FM física.

## Sonido online
- Reproduce por **cualquier salida disponible**, con o sin cable jack: altavoz, auriculares, USB y Bluetooth.
- Restablece automáticamente el **máximo de volumen multimedia de la ruta activa** (normalmente 25/25 en el Moto G3), cuando cambia la salida y cada 4 segundos.
- Aplica **+10 dB** con `LoudnessEnhancer` a las emisoras por Internet en todas las salidas.
- ExoPlayer se encarga de la prioridad de audio y la reconexión de streams.

La FM física sigue usando el controlador root y la ruta de audio originales; no recibe el aumento de +10 dB.

**Advertencia:** volumen máximo y +10 dB pueden generar distorsión y niveles peligrosos, especialmente con auriculares o Bluetooth. Baja primero la amplificación externa. El programa restablece el volumen alto mientras está ejecutándose.

**Nota Android:** cada salida conserva su propio volumen. Las APIs públicas ajustan la ruta **activa**; RadioCaribe fijará automáticamente el máximo en cualquier nueva ruta cuando se conecte, pero no modifica simultáneamente índices de salidas desconectadas.

## Reposo profundo opcional por USB (v0.34, experimental)

**Apagado por defecto**, para no dejar el Moto G3 sin ADB Wi-Fi antes de probar el despertar con USB. Actívalo desde CMD cuando el teléfono esté encendido y conectado a la red:

```bat
adb connect 10.0.214.8:5555
adb shell settings put global radiocaribe_ultra_sleep 1
adb shell settings get global radiocaribe_ultra_sleep
```

Cuando se **desconecta la alimentación USB** durante aproximadamente 1 segundo, RadioCaribe:
- Detiene la reproducción online y la FM física sin modificar el controlador FM.
- Pausa el servidor de administración y los temporizadores de volumen.
- Apaga Bluetooth y, por defecto, Wi-Fi; recuerda cuáles estaban habilitados.
- Libera el bloqueo de pantalla encendida y envía una pulsación de reposo mediante `su`.
- Permite que Android/LineageOS suspenda la CPU si no existen otros bloqueos de suspensión.

Al **reconectar USB**, Android despierta la pantalla **antes de restaurar Wi-Fi y Bluetooth**. Las emisoras online vuelven en cuanto Android detecta conexión, evitando esperar el reintento fijo de 5 segundos. **No hay un sondeo periódico** que impida entrar en reposo. Requiere batería funcional, root permitido para RadioCaribe y que la ROM despierte con el evento USB. El resultado **debe probarse en el Moto G3**; no se garantiza un consumo determinado ni un despertar instantáneo.

### Priorizar batería o rapidez del Wi-Fi

La configuración predeterminada apaga Wi-Fi durante el reposo para ahorrar batería. Al reconectar USB, la pantalla despierta de inmediato, pero el audio online debe esperar a que Wi-Fi se conecte nuevamente.

**Para que la emisora online reaccione más rápido**, mantén Wi-Fi activo durante el reposo (mayor consumo):

```bat
adb shell settings put global radiocaribe_sleep_keep_wifi 1
```

**Para volver al ahorro mínimo**, apagando Wi-Fi durante el reposo:

```bat
adb shell settings put global radiocaribe_sleep_keep_wifi 0
```

El ajuste se aplica al siguiente ciclo USB, sin modificar los scripts Magisk existentes.

**Prueba con precaución:** la primera vez, prueba junto al teléfono. Durante el reposo **ADB por Wi-Fi queda desconectado**. Si la ROM no despierta o no restaura Wi-Fi, usa el botón físico, una conexión USB de datos con ADB, o la interfaz Magisk para recuperar acceso.

Para **desactivar** la función cuando ADB vuelva a estar conectado:

```bat
adb shell settings put global radiocaribe_ultra_sleep 0
adb shell svc wifi enable
adb shell am start -n com.radiocaribe.tuner/.MainActivity
```

Logs para comprobar el comportamiento:

```bat
adb logcat -d -s RadioCaribeSleep:I RadioCaribeAudio:I *:S
```

Los scripts Magisk v3 que ajustan volumen 25/25 y ADB TCP 5555 **no necesitan ser cambiados**.

## Administración y APK
- Interfaz de radio horizontal, temas claro/oscuro y última emisora recordada.
- Editor de emisoras y logos por Wi-Fi en el puerto 8080. **Sin PIN:** utilizar solo en redes de confianza.
- [APK publicado en GitHub Releases](https://github.com/EncisoMath/RadioCaribe/releases/latest): `RadioCaribe_v0.34.apk`.
- Para actualizar sin desinstalar, el certificado de firma del nuevo APK debe coincidir con el anterior. El workflow intenta reutilizar la misma clave en caché.
- **Rama única:** `main`. Compilación con GitHub Actions, Android SDK 33 y Gradle 7.5.1.
