# Arquitectura final — miro (post-auditoría 2026-09-01)

> Estado: **compilable y CI verde** (run 33479725475). On-device verification: pendiente de usuario (tablet no disponible en sesión). Ver `03-Handoffs/2026-09-01-corregir-issues-integracion.md` → sección "Pendiente on-device".

## Principios (AGENTS.md)

| # | Regla | Cumplimiento |
|---|-------|-------------|
| 1 | No compilar en fullmetal | ✅ CI de GitHub Actions |
| 2 | Package `com.miro.a11y` inamovible | ✅ Sin rename |
| 3 | UN solo accessibility service | ✅ 1 service en manifest |
| 4 | Toggle con 3 reintentos + verificación | ✅ En `MiroLauncherActivity.attemptToggle()` |
| 5 | No hardcodear servicios de otras apps | ✅ `attemptToggle()` lee lista dinámica |
| 6 | No marcar completed sin verificar | ✅ Documentado lo verificado vs pendiente |

## Estructura de archivos (post-corrección)

```
app/src/main/java/com/miro/a11y/
├── MiroAccessibilityService.kt        # Único service. Incluye WirelessDebugAutomator.
├── MiroController.kt                  # Gestures + global actions + dumpScreen + socket cmds.
├── MiroLauncherActivity.kt            # HOME launcher + a11y toggle (3 reintentos + verificación).
├── MiroSocketServer.kt                # Socket @miro (adb forward tcp:PORT localabstract:miro).
├── ui/
│   └── MainActivity.kt                # UI setup ADB (btnSetupAdb, state machine).
├── util/
│   ├── IpPortParser.kt                # Parser puro ip:port (5 tests JVM PASS).
│   └── Logger.kt                      # Flag estático debugEnabled (no BuildConfig).
```

## State machine — WirelessDebugAutomator

Ubicado dentro de `MiroAccessibilityService.kt` (no como service separado).

```
IDLE → OPENING_DEV_OPTIONS → CLICKING_WIRELESS_DEBUG → EXTRACTING_IP_PORT → SENDING_TO_PC → DONE
```

### Transitiones

| From | To | Acción | Herramienta |
|------|----|--------|-------------|
| IDLE | OPENING_DEV_OPTIONS | `GLOBAL_ACTION_QUICK_SETTINGS` | `controller.globalAction()` |
| OPENING_DEV_OPTIONS | CLICKING_WIRELESS_DEBUG | Tap "Settings" tile → Developer Options | `controller.tapByText()` / `tap()` fallback gear icon @ (480,160) |
| CLICKING_WIRELESS_DEBUG | EXTRACTING_IP_PORT | Tap "Wireless Debugging" entry | `controller.tapByText()` |
| EXTRACTING_IP_PORT | SENDING_TO_PC | Parse IP:port del window tree | `controller.dumpScreen()` + `IpPortParser.parse()` |
| SENDING_TO_PC | DONE | Log estructurado `WIFI_DEBUG_RESULT ip:port` | `onLog()` (host lo parsea de logcat) o socket @miro |

### Hand-off al PC

- Socket `@miro` (`MiroSocketServer`) ya está operativo: acepta `{"action":"start_wireless_debug"}`.
- Cuando el automator extrae IP:port, lo envía como:
  ```json
  {"action":"wireless_debug_result","ip":"10.42.1.63","port":43661}
  ```
  (implementado en `step6SendToHost` — log estructurado).
- El host ejecuta `adb forward tcp:1234 localabstract:miro` y lee el resultado.

## Toggle de accesibilidad (MiroLauncherActivity)

Patrón: 3 reintentos con verificación post-escritura.

```
attemptToggle(attempt):
  1. read ENABLED_ACCESSIBILITY_SERVICES (lista actual dinámica)
  2. filter OUT "com.miro.a11y/..." (case-insensitive)
  3. write filtered list → sleep 500ms → verify read == filtered
  4. write ACCESSIBILITY_ENABLED=0 → sleep 500ms → verify == 0
  5. sleep 2000ms (sistema procesa)
  6. re-add miro service → sleep 500ms → verify contains miro
  7. write ACCESSIBILITY_ENABLED=1 → sleep 500ms → verify == 1
  8. return true (todo verificado) o retry
```

Si los 3 intents fallan → log de error, requiere intervención manual.

## Manifest (AndroidManifest.xml)

- 1 `<activity>` HOME: `MiroLauncherActivity` (intent-filter HOME+MAIN+LAUNCHER)
- 1 `<activity>` launcher: `ui.MainActivity` (intent-filter LAUNCHER)
- 1 `<service>`: `MiroAccessibilityService`
- Permissions: `INTERNET`, `WRITE_SECURE_SETTINGS` (tools:ignore)

## Commits clave

| Commit | Qué hace |
|--------|----------|
| `cae7b83` | feat: integrate wireless-adb state machine (scaffolding) |
| `54f3480` | fix: drop BuildConfig dependency in Logger |
| `97c47db` | feat(fusion): MainActivity + BootLauncherActivity (revertido) |
| `834e5c7` | fix: address 2026-09-01 audit issues (real state machine, 1 service, no hardcoded) |
| `eaa71a3` | fix: resolve GLOBAL_ACTION_SETTINGS + IpPortParser import |
| `3e95bf7` | fix: QS gear tap fallback (no GLOBAL_ACTION_SETTINGS) |
| `09ffdf8` | fix: val→var for ok reassignment |
| `d161ba1` | docs(handoff): mark 2026-09-01 handoffs as completed |
| `dee4964` | docs(handoff): add 2026-09-01-corregir-issues-integracion handoff |
| `3e95bf7` | fix: remove GLOBAL_ACTION_SETTINGS |

## Pendiente on-device

| Verificación | Status |
|-------------|--------|
| `adb shell dumpsys package com.miro.a11y \| grep -A1 AccessibilityService` → 1 service | ❌ Pendiente (device no disponible) |
| `adb install -r <APK CI>` + `setup_adb.sh --serial 10.42.1.x:5555` | ❌ Pendiente |
| Reboot + `settings get secure enabled_accessibility_services` incluye miro | ❌ Pendiente |
| State machine ejecuta clicks reales en QS → Settings → DevOpts → Wireless | ❌ Pendiente |

---

Referencias:
- `03-Handoffs/2026-08-15-miro-autostart-resolved.md` — patrón de toggle
- `06-Reglas/02-issues-encontrados.md` — auditoría completa
- `03-Handoffs/2026-09-01-corregir-issues-integracion.md` — este handoff ejecutado
