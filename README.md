# RadioCaribe v0.37

Reproductor dedicado para el **Moto G3 con LineageOS 16 / Android 9**, con emisoras online y FM física.

## Radio Tiempo por ciudades y Clásicos (v0.37)

En la lista de estaciones aparecen **BARRANQUILLA, CALI, MEDELLÍN,
CARTAGENA, MONTERÍA y SINCELEJO**, compartiendo el logo original de
Radio Tiempo. **CLASICOS** utiliza el logo especial enviado por el propietario.

| Título | Frecuencia | Streaming |
|---|---|---|
| BARRANQUILLA | 96.1 FM | `RT_BARRANQUILLAAAC.aac` |
| CALI | 89.5 FM | `RT_CALIAAC_SC` |
| MEDELLÍN | 105.9 FM | `RT_MEDELLINAAC.aac` |
| CARTAGENA | 88.5 FM | `RT_CARTAGENAAAC.aac` |
| MONTERÍA | 104.5 FM | `RT_MONTERIAAAC.aac` |
| SINCELEJO | 97.3 FM | `RT_SINCELEJOAAC.aac` |
| CLASICOS | Online | `RT_CLASICA_SC` |

Los enlaces utilizan `playerservices.streamtheworld.com/api/livestream-redirect/`.
La URL de Clásicos fue confirmada como funcional por el propietario; la
reproducción efectiva de las demás depende del servidor de cada emisora.

**Migración:** al actualizar, la aplicación agrega las estaciones que falten,
renombra Barranquilla conservando su ID y no borra emisoras ni logos
personalizados. También preserva la selección anterior aunque se inserten
nuevas estaciones.

## Sonido online
- Reproduce por **cualquier salida disponible**, con o sin cable jack: altavoz, auriculares, USB y Bluetooth.
- Restablece automáticamente el **máximo de volumen multimedia de la ruta activa** (normalmente 25/25 en el Moto G3), cuando cambia la salida y cada 4 segundos.
- Usa el ecualizador digital de 12 bandas y amplificación ajustable de **0 a +25 dB** (por defecto +10 dB).
- ExoPlayer se encarga de la prioridad de audio y la reconexión de streams.

### Ecualizador de 12 bandas y amplificador (v0.37)

Pulsa el **icono EQ (☷) inmediatamente a la derecha de NET**. Se abre una ventana horizontal con 12 faders reales a 32, 64, 125, 250, 500 Hz; 1, 2, 3, 4, 6, 10 y 16 kHz.

- Cada banda ajusta **−12 a +12 dB** mediante un filtro digital peaking biquad.
- Amplificador **0 a +25 dB**, valor inicial **+10 dB**, configurable en tiempo real.
- Los parámetros se **guardan automáticamente**, incluso entre reinicios; RESTABLECER pone las bandas en cero y el amplificador en +10 dB.
- El sonido online pasa por ExoPlayer → procesador PCM de 12 bandas → amplificador → limitador suave → salida Android. Se deshabilita offload y coma flotante para que los filtros no se omitan.
- **Sin doble amplificación**: el +10 dB fijo de LoudnessEnhancer fue sustituido por este ajuste de 0 a +25 dB.
- **Límite físico:** +25 dB puede saturar la señal de una emisora que ya transmite fuerte. El limitador evita desbordamiento de muestras, pero no garantiza ausencia de distorsión ni seguridad del altavoz.

**La FM física sigue usando el puente root y la ruta de audio hardware original: la señal FM no pasa por ExoPlayer PCM, así que el EQ y la ganancia configurables se aplican a las emisoras ONLINE, no a la FM física.**

## FM física (v0.37)

Se corrigió el error de v0.34 que exigía `libc++.so` dentro del APK, aunque Android 9 la proporciona como biblioteca del sistema. El puente root ahora emplea `/system/lib/libc++.so`, `/vendor/lib/libc++.so` o una copia previamente instalada en el puente, e informa de una causa concreta cuando falta una dependencia.

**Magisk:** el UID de RadioCaribe necesita autorización `policy=2`. Si el gestor no muestra la solicitud, con acceso root desde ADB puedes establecer la política manualmente dentro de `adb shell` → `su`:

```sh
magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES (10104,2,0,1,0);"
magisk --sqlite "SELECT * FROM policies WHERE uid=10104;"
```

El UID `10104` es el del Moto G3 del propietario en esta instalación; siempre verifica el UID real con `adb shell pm list packages -U com.radiocaribe.tuner`. Tras instalar, reinicia la app y consulta los registros con `adb logcat -d -s RadioCaribeFM:I *:S`.

**Advertencia:** volumen máximo y +10 dB pueden generar distorsión y niveles peligrosos, especialmente con auriculares o Bluetooth. Baja primero la amplificación externa. El programa restablece el volumen alto mientras está ejecutándose.

**Nota Android:** cada salida conserva su propio volumen. Las APIs públicas ajustan la ruta **activa**; RadioCaribe fijará automáticamente el máximo en cualquier nueva ruta cuando se conecte, pero no modifica simultáneamente índices de salidas desconectadas.

## Reposo profundo opcional por USB (v0.37, experimental)

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
- [APK publicado en GitHub Releases](https://github.com/EncisoMath/RadioCaribe/releases/latest): `RadioCaribe_v0.37.apk`.
- Para actualizar sin desinstalar, el certificado de firma del nuevo APK debe coincidir con el anterior. El workflow intenta reutilizar la misma clave en caché.
- **Rama única:** `main`. Compilación con GitHub Actions, Android SDK 33 y Gradle 7.5.1.
