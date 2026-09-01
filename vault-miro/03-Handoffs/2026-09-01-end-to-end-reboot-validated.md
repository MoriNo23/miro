# 2026-09-01 — End-to-end reboot validation: v1.3.5

## TL;DR
**v1.3.5 funciona end-to-end tras reboot en OLAX Magic Q1.**
- Service se rebindea automáticamente (`MiroLauncherActivity` corre como HOME)
- Toggle de a11y verifica en intento 1
- 8s después del `onServiceConnected()`, el `WirelessDebugAutomator` corre el path OLAX QS-tile
- Wireless Debugging queda activado (`adb_wifi_enabled = 1`)
- El botón "Cerrar recientes" en la notificación abre la pantalla Recents (user cierra manualmente)

## Commits clave de la sesión

```
e4a90e3 fix(automator): tap dialog by coordinates — OLAX dialog tree hidden from a11y
defa1d4 fix(recents): inline stop-with-error (stopWithError was on the wrong class)
7f575cd fix(recents): just open the Recents screen — user dismisses manually
5e25f2d feat(recents): RecentTasksCleaner (just opens Recents, user dismisses)
027f128 docs: arquitectura-final + handoff
524f101 docs(handoff): record miro v1.1.0 install + first reboot on tablet
33497021730 GH Actions run: green
33495377556 GH Actions run: green
33495192576 GH Actions run: green
33494507538 GH Actions run: green
33494110947 GH Actions run: green
33493914308 GH Actions run: green (recents open)
33492782528 GH Actions run: green
33492487051 GH Actions run: failed (POST_NOTIFICATIONS lint)
33491153577 GH Actions run: green
33488425702 GH Actions run: green
33488183125 GH Actions run: failed (continue in lambda)
33487184096 GH Actions run: green
33486347107 GH Actions run: green
33485153128 GH Actions run: green
33483782938 GH Actions run: green
33482229748 GH Actions run: green
33481880289 GH Actions run: failed (companion object dup)
```

## Logros técnicos (v1.3.0 → v1.3.5)

### 1. Path OLAX QS-tile para Wireless Debug (v1.2.0)
- **`performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)`** abre la NotificationShade con QS tiles directamente
- **Tile "Depuración inalámbrica"** en `(410, 85)` (1024x600 OLAX, Spanish locale)
- **Dialog "Permitir depuración inalámbrica"** con:
  - Checkbox "Permitir siempre en esta red" en `(511, 312)`
  - Botón "PERMITIR" en `(721, 372)`
  - Botón "CANCELAR" en `(629, 372)`
- **NO se captura IP:Port desde la tablet** — el `adb_tablet` del PC adivina el puerto via nmap

### 2. Coordinate-based taps para dialog hidden (v1.2.0)
- El `WifiDebuggingActivity` de OLAX **NO expone su árbol a11y** al MiroAccessibilityService
- `tapByText` retorna `false` aunque el dialog es visible
- **Workaround**: tap por coordenadas hard-coded, verificadas manualmente

### 3. Swipe-down fallback para QS (v1.3.3)
- `performGlobalAction(QUICK_SETTINGS)` a veces retorna `false` justo después de wakeup
- Si el primer `tile not found`, swipe-down en `(512, 5) → (512, 380, 250ms)` para forzar QS
- Re-buscar tile

### 4. HandlerThread/Thread fix para MiroLauncherActivity (v1.3.5)
- Plain `Thread{}` se mataba cuando la activity pasaba a background
- **Solución**: `Thread { ... }` simple pero **NO hacer `launchRealLauncher()+finish()` hasta que el toggle verifique**
- Si el toggle falla tras 3 intentos, MiroLauncherActivity queda visible con un error log

### 5. HOME launcher post-reboot (v1.3.5)
- **Clave**: `cmd package set-home-activity com.miro.a11y/.MiroLauncherActivity` debe ejecutarse **una vez** antes del reboot
- Después del reboot, el sistema invoca MiroLauncherActivity como HOME
- Sin este set, ESLauncher es el HOME default y MiroLauncherActivity nunca corre

### 6. Recents feature (v1.3.0 + v1.3.1)
- **RecentTasksNotifier** muestra notificación persistente con action "Cerrar recientes"
- **RecentTasksCleaner** original intentaba buscar "Cerrar todo" → falló
- **v1.3.1**: cambia a `performGlobalAction(GLOBAL_ACTION_RECENTS)` + wait 2s + verificar
- El user cierra manualmente (patrón estándar de Task Killer apps)

## Log completo de un reboot exitoso (v1.3.5)

```
09-01 06:26:20.088  1154  1154 I miro    : launcher activity started (post-boot or manual)
09-01 06:26:23.428  1154  1154 I miro    : miro accessibility service connected
09-01 06:26:23.440  1154  1391 D miro    : miro socket listening on @miro
09-01 06:26:23.531  1154  1154 D miro    : window: com.miro.a11y/com.miro.a11y.MiroLauncherActivity
09-01 06:26:24.357  1154  1216 I miro    : a11y toggle verified on attempt 1
09-01 06:26:24.505  1154  1154 I miro    : launched real launcher
09-01 06:26:25.955  1154  1154 D miro    : window: com.android.launcher3/com.android.launcher3.ESLauncher
09-01 06:26:30.242  1154  1154 D miro    : window: com.android.systemui/android.widget.FrameLayout
09-01 06:26:30.434  1154  1154 D miro    : window: com.android.systemui/android.widget.FrameLayout
09-01 06:26:31.531  1154  1154 I miro    : auto-start: triggering OLAX QS-tile wireless debug flow
09-01 06:26:31.539  1154  1154 D miro    : wireless debug: state=OPENING_DEV_OPTIONS
09-01 06:26:32.186  1154  1154 D miro    : window: com.android.systemui/android.widget.FrameLayout
09-01 06:26:33.054  1154  1154 D miro    : wireless debug: state=CLICKING_WIRELESS_DEBUG — tapping tile
09-01 06:26:33.363  1154  1154 D miro    : wireless debug: tile tapped
09-01 06:26:34.715  1154  1154 D miro    : window: com.android.systemui/android.app.Dialog
09-01 06:26:35.063  1154  1154 D miro    : window: com.android.systemui/android.widget.FrameLayout
09-01 06:26:35.366  1154  1154 D miro    : wireless debug: state=CLICKING_WIRELESS_DEBUG — checking 'Permitir siempre'
09-01 06:26:35.385  1154  1154 D miro    : wireless debug: tapped 'Permitir siempre' checkbox at (511, 312)
09-01 06:26:36.208  1154  1154 D miro    : window: com.android.systemui/android.widget.FrameLayout
09-01 06:26:36.386  1154  1154 D miro    : wireless debug: state=SENDING_TO_PC — tapping PERMITIR
09-01 06:26:36.552  1154  1154 D miro    : WIRELESS_DEBUG_ENABLED via OLAX QS-tile flow
09-01 06:26:36.553  1154  1154 D miro    : wireless debug: DONE
09-01 06:26:36.573  1154  1154 D miro    : wireless debug: stopped (state=DONE)
09-01 06:26:37.000  1154  1154 D miro    : window: com.android.launcher3/com.android.launcher3.ESLauncher
```

**Tiempo total**: ~16 segundos desde `launcher activity started` hasta `DONE`.

## Pasos para repetir (Mori / próximo agente)

### Setup one-time (antes del primer reboot)
```bash
# 1. Conectar tablet
adb_tablet  # o adb connect 10.42.1.63:5555

# 2. Instalar v1.3.5
adb install /tmp/miro-v1.3.5.apk

# 3. Grant WRITE_SECURE_SETTINGS
adb shell pm grant com.miro.a11y android.permission.WRITE_SECURE_SETTINGS

# 4. Setear MiroLauncherActivity como HOME default
adb shell cmd package set-home-activity com.miro.a11y/.MiroLauncherActivity

# 5. Verificar
adb shell cmd shortcut get-default-launcher
# debe decir: com.miro.a11y/com.miro.a11y.MiroLauncherActivity
```

### Verificar post-reboot
```bash
# 1. Reboot
adb reboot
sleep 90

# 2. Verificar service
adb shell dumpsys accessibility | grep "Bound services"
# debe contener: Service[label=miro, ...]

# 3. Verificar wireless debug
adb shell settings get global adb_wifi_enabled
# debe ser: 1

# 4. Verificar logcat
adb logcat -d -s miro:V | tail -30
# debe terminar en: WIRELESS_DEBUG_ENABLED via OLAX QS-tile flow
```

## Limitaciones conocidas

1. **Coordinate-based taps son específicos de 1024x600 OLAX Spanish** — si cambia la resolución o el locale, hay que recalcular.
2. **`cmd package set-home-activity` no es persistente a través de wipe de data** — hay que reaplicarlo tras un factory reset.
3. **El flow completo toma ~16s** desde el boot — si el user interactúa con la tablet durante ese tiempo, puede interferir.
4. **El tile "Depuración inalámbrica" en QS puede estar en otra posición** si OLAX actualiza su ROM.
5. **El path QS falla si Wireless Debugging ya está activado** (no se abre el dialog) — el `isWirelessDebugAlreadyOn()` lo detecta y skip el flow.

## Reglas críticas para futuros agentes

1. **NO compilar en fullmetal** — solo CI o Colab.
2. **Mantener `com.miro.a11y`** como package name.
3. **NO hardcodear nombres de servicios de otras apps** — leer `enabled_accessibility_services` dinámicamente.
4. **Toggle de a11y con 3 reintentos + verificación** post-escritura.
5. **Coordenadas del dialog OLAX son estables** (verificado 2026-09-01).
6. **Si el service se rebindea, el auto-start wireless debug re-dispara** (por diseño — usa el flag `kAutoStartWirelessDebug = true` y se ejecuta 8s después de cada `onServiceConnected()`).
7. **HOME launcher debe ser MiroLauncherActivity** — sin esto, el service no se rebindea post-reboot.
8. **Notification channel `miro_recents` con IMPORTANCE_LOW** — no molestar al user.
9. **RecentTasksCleaner solo abre Recents** — el user cierra manualmente.
10. **El toggle thread NO termina la activity antes de verificar** — el finish() espera al result del toggle.
