# 2026-09-01 — Hack de la PC borrado: adb-wifi-persist.service

## Lo que pasó

El usuario detectó que el "auto-arranque de wireless debug" en la tablet estaba
**facilitado por un truco de la PC**, no por el automation de miro:

```
/home/fullmetal/.config/systemd/user/adb-wifi-persist.service
ExecStart=/usr/bin/python3 /home/fullmetal/adb_wifi_persist.py
```

Era un **servicio systemd del usuario** que detectaba la conexión USB de la tablet
y activaba `adb_wifi_enabled` por la PC (vía `adb shell settings put global
adb_wifi_enabled 1`). Eso es exactamente lo que el usuario **NO quiere** porque:

1. Requiere tener la PC encendida y el cable USB conectado.
2. La tablet no es auto-suficiente tras reboot.
3. El "auto-arranque" que validamos en v1.3.5 fue en parte gracias a este hack.

## Acción tomada

```bash
systemctl --user stop adb-wifi-persist.service
systemctl --user disable adb-wifi-persist.service
rm -f /home/fullmetal/.config/systemd/user/adb-wifi-persist.service
systemctl --user daemon-reload
```

**El truco está borrado.** `systemctl --user status adb-wifi-persist.service`
devuelve "Unit not found".

## Lo que la APK de miro DEBE hacer (no la PC)

El auto-arranque **post-reboot SIN USB** tiene que venir 100% de la APK:

1. **MiroLauncherActivity** está registrada como `CATEGORY_HOME` en el manifest
   (`AndroidManifest.xml:38-43`).
2. **`cmd package set-home-activity com.miro.a11y/.MiroLauncherActivity`** se setea
   con `adb_tablet --setup` y queda persistente.
3. Al boot, el sistema Android invoca MiroLauncherActivity como HOME.
4. MiroLauncherActivity hace el toggle a11y (3 reintentos con verificación,
   `MiroLauncherActivity.kt:startToggleSequence`).
5. MiroLauncherActivity espera 5s (`BIND_GRACE_MS`) para que el system server
   bindee el `MiroAccessibilityService` — **esto es crítico** y fue la fix
   v1.4.9 (commit `69000e4`).
6. MiroLauncherActivity hace handoff a ESLauncher vía
   `moveTaskToBack(false)` (no `finish()`, para mantener el process vivo).
7. El `MiroAccessibilityService`, ya bindeado, corre el flow OLAX QS-tile
   para activar `adb_wifi_enabled = 1` automáticamente
   (`MiroAccessibilityService.kAutoStartWirelessDebug`).
8. Wireless debug queda ON post-boot, sin USB.

**Si MiroAccessibilityService no se bindea** (como pasó en el test de hoy
post-reboot), **el handoff no sirve y wireless debug queda OFF**. Eso es
exactamente el problema que Mori está viendo.

## Estado del repo al cierre

- Branch: `main`
- Último commit: `69000e4 fix(launcher): keep process alive after toggle`
- Versión instalada en la tablet: `v1.4.9` (versionCode 23, instalada vía
  `adb install /tmp/miro-v1.4.9.apk`)
- HOME: `com.miro.a11y/.MiroLauncherActivity` (seteado con `--setup`)
- Wireless debug: **OFF** (post-reboot, sin truco de la PC)
- Service: `✗ NOT bound` (porque el toggle verificado no bindeó post-reboot)
- USB conectado al device serial `6c000c6d480109622dd` (Mori lo dejó así
  antes de ausentarse)

## Próximos pasos para el agente (regla de Mori)

> "REGLA NUEVA PARA EL REPOSITORIO: SIEMPRE QUE HERMES COMPRIMA LA SESSION O
> SE DÉ A CONTINUAR, REVISA EL HANDOFF O LAS NOTAS DEL VAULT DEL REPOSITORIO.
> CREA UN HANDOFF ACTUAL. LUEGO REVISA SI ES QUE PUSISTE UN TRUCO PC-USBADB
> PARA QUE SE ACTIVARA EL ADB WIFI DE ESTA FORMA."

Implementado en `AGENTS.md` como regla #7.

## Bug conocido: service no se bindea post-reboot

**Síntoma** (verificado hoy 2026-09-01):

1. Reboot via `adb reboot` (USB).
2. Espera 90s.
3. MiroLauncherActivity corre como HOME.
4. Toggle a11y verificado en attempt 1.
5. **5s después**, handoff + moveToBack.
6. **Service: NOT bound** — `miro accessibility service connected` no aparece
   en logcat.

**Causa sospechada** (no confirmada):
El `AccessibilityManagerService` requiere un re-enable explícito del flag
después de la verificación. Cuando hacemos flag 0 → flag 1 dentro del toggle
(`MiroLauncherActivity.kt:attemptToggle`), el system server a veces no
dispara el bind inmediatamente.

**Fix propuesta** (a probar en próxima sesión):

En `MiroLauncherActivity.startToggleSequence`, después de que el toggle
verifica, hacer **un segundo toggle** (otro flag 0 → flag 1) y luego el
handoff:

```kotlin
// Second toggle to force the bind (OLAX quirk: first toggle
// doesn't always wake the AccessibilityManagerService).
if (!attemptToggle(attempt + 1)) {
    Log.w(TAG, "second toggle failed — service may not bind")
}
```

Otra opción: **tocar `Settings.Secure.ACCESSIBILITY_ENABLED = 0` después
del primer toggle verificado, esperar 1s, y volver a poner `= 1`**. Eso
fuerza un unbind/rebind cycle.

Ambas opciones son hacks. La solución correcta sería que OLAX respete
el `BIND_ACCESSIBILITY_SERVICE` flag sin estas artimañas, pero la ROM
es la que es.

## Test pendiente (con USB, sin trucos)

1. Conectar USB (Mori lo dejó así).
2. Verificar que `adb_wifi_enabled = 0`.
3. `adb reboot`.
4. Esperar 90-120s.
5. Verificar que `adb_wifi_enabled = 1` (auto-activado por la APK,
   sin trucos de la PC).
6. Verificar que el service está bound.
7. Desconectar USB.
8. Verificar que `adb_tablet` conecta vía wireless.

Si el paso 5 falla, el service no se está bindeando y hay que aplicar
la "second toggle" fix propuesta arriba.
