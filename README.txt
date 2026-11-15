RadioCaribe v0.37 — Moto G3 / Android 9

La documentación completa está en README.md.
Descarga APK: https://github.com/EncisoMath/RadioCaribe/releases/latest

Radio por Internet en cualquier salida, máximo volumen multimedia
para la ruta activa y aumento de +10 dB solo online. FM sin cambios.
ATENCIÓN: volumen máximo +10 dB puede ser extremadamente alto.

Reposo USB v0.37: espera 1 s; al reconectar, la pantalla despierta primero.
Para Wi-Fi listo más rápido (más consumo):
adb shell settings put global radiocaribe_sleep_keep_wifi 1

FM: v0.37 usa biblioteca libc++ del sistema Android 9.
Permiso Magisk: comprobar UID antes de autorizar manualmente.

EQ real 12 bandas: botón junto a NET. Cada banda -12..+12dB.
Amplificador PCM online 0..+25dB (por defecto +10dB), ajuste en vivo.
FM analógica física no pasa por el procesador PCM.

Radio Tiempo v0.37: títulos por ciudad; CLASICOS usa logo suministrado.
Sin perder emisoras personalizadas al actualizar el APK.
