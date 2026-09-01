---
type: note
created: 2026-08-31
status: active
tags: [wireless-adb-auto, android, accessibility-service, autostart, launcher, olax-magic-q1, no-root, ci]
summary: >
  Proyecto Wireless ADB Auto — APK que automatiza Wireless Debugging y se
  auto-re-enlaza tras reboot via HOME launcher wrapper.
  Estado: CI verde, test JVM incluido, listo para verificación en tablet.
---

# Wireless ADB Auto — Autostart post-boot (2026-08-31)

APK Android que automatiza la activación de **Wireless Debugging** y envía la dirección IP:puerto a un servidor en PC. **Extiende** el patrón de launcher-wrapper demostrado con miro en la OLAX Magic Q1.

## Estado del proyecto

**EN DESARROLLLO — Fase 1 (infra) COMPLETA (2026-08-31).**

| Check | Estado | Cómo verificar |
|---|---|---|
| Repo git inicializado + push a GitHub | DONE | `git ls-remote --heads origin` |
| Matriz de versiones coherente (AGP 8.7 / Kotlin 1.9.24 / Gradle 8.9 / SDK 35) | DONE | CI green |
| Workflow CI (`.github/workflows/build.yml`) | DONE | badge en README |
| Scripts `setup_adb.sh` / `verify_autostart.sh` | DONE | `bash scripts/*.sh --help` |
| Test JVM `IpPortParserTest` (5 tests) | DONE | en CI (`gradle test`) |
| Retry/verificación del toggle en `BootLauncherActivity` | DONE | 3 intents, re-lectura post-escritura |
| `MainActivity` con fallback `cmd shortcut get-default-launcher` | DONE | API 31 OLAX no expone `DEFAULT_HOME_LAUNCHER` |
| README con badge CI + instrucciones | DONE | en repo |
| CI verde (assembleDebug + lint + test + APK upload) | DONE | run 33368946556 ✓ |
| `ADB_WIFI_ENABLED` + `DEFAULT_HOME_LAUNCHER` @hide/no-constant fixes | DONE | string literals + `Settings.Global.getInt` |

**Pendiente (Fase 2 — device-side, requiere tablet):**
- [ ] Verificar en tablet OLAX Magic Q1: 3 reboots consecutivos — `scripts/verify_autostart.sh`
- [ ] Test con re-install del APK
- [ ] `adb connect` funcionante vía servidor Python

## Qué cambió vs el proyecto original

| Archivo | Cambio |
|---|---|
| `build.gradle` (raíz) | Reemplazado `buildscript { classpath AGP:9.1.1 }` por `plugins DSL` con AGP 8.7 + Kotlin 1.9.24 (el bloque buildscript causaba conflicto "plugin already on classpath") |
| `gradle.properties` | Eliminada `android.buildInKotlin=false` (causaba el conflicto del plugin kotlin) |
| `app/build.gradle` | Agregada `testImplementation 'junit:junit:4.13.2'` |
| `AndroidManifest.xml` | Añadido `BootLauncherActivity` con `HOME` intent-filter; `MainActivity` ya no es launcher; permiso `WRITE_SECURE_SETTINGS` |
| `BootLauncherActivity.kt` | HOME launcher wrapper: toggle completo de accesibilidad con retry (3 intentos) y verificación post-escritura + handoff a ESLauncher |
| `MainActivity.kt` | State machine + botón "Configurar ADB"; `isDefaultLauncher()` usa fallback a `cmd shortcut` (API 31 OLAX no expone `DEFAULT_HOME_LAUNCHER`) |
| `WirelessDebugAccessibilityService.kt` | Timeout 15s, `POST_TOGGLE_WAIT_MS` 3s, salto si wireless debug ya ON; refactor a `IpPortParser` |
| `util/IpPortParser.kt` | **Nuevo.** Parsing de IP:Port extraído a módulo puro y testeable |
| `test/.../IpPortParserTest.kt` | **Nuevo.** 5 tests JVM (válido, múltiple formatos, ausente, vacío, no 4 dígitos) |
| `strings.xml` | Limpiado strings no usados (`status_success`, `status_error`, *notifications*, etc.); conservados `app_name`, `accessibility_service_description` |
| `README.md` | Badge CI + instrucciones de build via CI + scripts |
| `.github/workflows/build.yml` | **Nuevo.** assembleDebug + lint + test |
| `scripts/setup_adb.sh` | **Nuevo.** One-time ADB grant + set-home-activity |
| `scripts/verify_autostart.sh` | **Nuevo.** Post-reboot verification helper |

## Cómo funciona el autostart

La ROM OLAX/Allwinner corta BOOT_COMPLETED, JobScheduler, AlarmManager y WorkManager para apps de usuario. Solo el launcher arranca solo.

1. `BootLauncherActivity` arranca como HOME launcher tras boot.
2. Ejecuta el **toggle completo** de accesibilidad con retry/verificación:
   - Quitar `com.autoadb.wirelessdebug/.service.WirelessDebugAccessibilityService` de `enabled_accessibility_services` → verificar.
   - `accessibility_enabled = 0` → verificar (re-leer).
   - Esperar 2 s.
   - Re-agregar self a la lista → verificar.
   - `accessibility_enabled = 1` → verificar.
3. Lanza `com.android.launcher3/.ESLauncher` (fallback genérico HOME) y `finish()`.
4. El servicio queda bindeado, el servidor recibe IP:Port, `adb connect` se ejecuta.

## Setup ADB (una vez, tras instalar)

```bash
# Build via CI (NO compilar localmente — regla de compile-restriction)
git push origin main
gh run watch
gh run download

# Instalar
adb install app-debug.apk

# Conceder permiso + fijar launcher
bash scripts/setup_adb.sh
# o manual:
adb shell pm grant com.autoadb.wirelessdebug android.permission.WRITE_SECURE_SETTINGS
adb shell pm set-home-activity --user 0 com.autoadb.wirelessdebug/.BootLauncherActivity
adb shell cmd shortcut get-default-launcher
adb shell dumpsys deviceidle whitelist +com.autoadb.wirelessdebug
```

## Verificación (3 reboots — los 3 deben pasar)

```bash
bash scripts/verify_autostart.sh
# Checks:
# 1. accessibility_enabled == 1
# 2. Bound services contiene WirelessDebug
# 3. launcher por defecto es BootLauncherActivity
# 4. adb_wifi_enabled == 1
# 5. logcat limpio de la app
```

## Pitfalls (documentados)

- **Permiso perdido al reinstalar.** `WRITE_SECURE_SETTINGS` y el launcher se pierden → re-correr setup ADB.
- **Toggle completo, no solo el flag.** `accessibility_enabled=1` solo NO re-enlaza; la lista debe tocarse.
- **No bloquear `onCreate`.** El toggle va en hilo separado; `finish()` solo después del handoff.
- **Selector de launcher.** Solo aparece en primer boot si el launcher no está fijado; `pm set-home-activity` lo elimina en boots subsiguientes.
- **API 31 no expone `DEFAULT_HOME_LAUNCHER`.** Usar fallback a `cmd shortcut get-default-launcher` (implementado en `MainActivity.isDefaultLauncher()`).

## References

- Skill: [[android-launcher-autostart]] — workflow general.
- Skill: [[compile-restriction]] — regla de no-compilación en fullmetal.
- Skill: [[android-gradle-ci-no-wrapper]] — workflow CI verificado (adaptado a SDK 35).
- Skill: [[adb-wireless-debugging-port-management]] — descubrimiento de puerto aleatorio.
- Vault: [[olax-rom-post-boot-block]], [[pm-set-home-activity-no-touch]], [[2026-08-15-miro-autostart-resolved]].
