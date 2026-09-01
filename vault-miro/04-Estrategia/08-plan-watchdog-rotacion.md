# 2026-09-01 — Plan: watchdog "Girar pantalla automáticamente" v1.4.20

## Goal

El user reporta que el tile "Girar pantalla automáticamente" en el
QS de la tablet OLAX se **auto-activa solo** (probablemente por un
bug del ROM OLAX o por alguna app que escribe `accelerometer_rotation=1`).

Quiere que la APK `com.miro.a11y` tenga un **watchdog** que detecte
cuando ese tile se activa y lo **desactive automáticamente** (sin
intervención del user).

## Test verificado (2026-09-01)

| accelerometer_rotation | Switch checked | bounds |
|---|---|---|
| `1` | `true` | `[516,340][736,424]` (centro (626, 382)) |
| `0` | `false` | mismo |

Verificado: tap en (626, 382) → `accelerometer_rotation` pasa a 0 y
el Switch se actualiza a `checked=false`. **No se necesita
WRITE_SECURE_SETTINGS** — el a11y service ya tiene permiso de
disparar clicks en el QS.

## Solución

1. **`RotationWatchdog`** — clase nueva que extiende
   `ContentObserver` y observa
   `Settings.System.getUriFor("accelerometer_rotation")`.
2. Registrado en `MiroAccessibilityService.onServiceConnected` y
   desregistrado en `onDestroy` / `onUnbind`.
3. Cuando `onChange` recibe `1`:
   - **Cooldown** de 5s (anti-loop: si YO mismo lo activé al
     tappear el tile, no quiero que el watchdog lo desactive
     instantáneamente)
   - Abrir QS completo (`performGlobalAction(QUICK_SETTINGS)` +
     swipe-down extra para expandir, o `cmd statusbar expand-settings`
     — pero la app no puede ejecutar comandos shell, solo el
     `adb_tablet` script. Entonces el a11y service usa swipe)
   - Buscar el Switch por `content-desc="Girar pantalla automáticamente"`
   - Tap centro de sus bounds
4. **Bumpear** a versionCode 34 / versionName 1.4.20.

## Pasos

1. Plan + handoff (este archivo)
2. Crear `app/src/main/java/com/miro/a11y/RotationWatchdog.kt`
3. Modificar `MiroAccessibilityService.kt`:
   - Importar y registrar `RotationWatchdog` en `onServiceConnected`
   - Desregistrar en `onUnbind`
4. Bumpear `app/build.gradle.kts`: versionCode 33→34, versionName 1.4.19→1.4.20
5. Commit + push
6. CI build
7. Descargar APK de CI
8. **adb_tablet --setup** para reinstalar
9. Test: tap al tile para activarlo → esperar 5s → ver que se desactiva solo
10. Handoff final

## Riesgos

| Riesgo | Mitigación |
|---|---|
| Anti-loop: si el user QUIERE activarlo, el watchdog lo desactiva | Cooldown 5s — el user tiene tiempo a desactivarlo. Aceptable porque el user dijo "no me gusta, desactivalo siempre" |
| El QS no se expande solo con `QUICK_SETTINGS` | Hacer swipe-down adicional si no aparece el tile |
| El Switch no se encuentra por content-desc | Buscar por texto "Girar pantalla automáticamente" o "Girar automáticamente" como fallback |
| `ContentObserver` se registra muchas veces | Usar `applicationContext` para que sobreviva |
| El ROM OLAX cambia `accelerometer_rotation` a 1 muy seguido | Cooldown 5s previene spam de taps. Logs con `onLog` para debugging |

## Criterio de éxito

- [ ] APK v1.4.20 compila en CI
- [ ] Después de install, el service registra el watchdog
- [ ] Tap al tile "Girar pantalla automáticamente" (cualquier vía: shell, QS, app) → tras 5s, se desactiva solo
- [ ] El Switch refleja el cambio (checked=false)
- [ ] `accelerometer_rotation=0` después del watchdog
- [ ] El service sigue bound y los demás features (Recents, wireless debug flow manual) siguen funcionando
