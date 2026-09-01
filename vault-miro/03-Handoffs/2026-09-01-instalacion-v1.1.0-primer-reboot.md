---
type: handoff
created: 2026-09-01
status: in-progress
tags: [miro, v1.1.0, install, tablet, post-reboot, verificacion]
summary: Instalación de miro v1.1.0 (versionCode 2) en la OLAX Magic Q1. Incluye uninstall de wireless-adb, setup manual, primer reboot post-instalación. Segundo reboot pendiente.
---

# 2026-09-01 — Instalación miro v1.1.0 + primer reboot verificado

## ✅ Hecho

1. **APK descargada del CI** (run 33480048590, commit `027f128`)
   - Path: `/tmp/miro-v1.1.0.apk`
   - Tamaño: 5.7 MB
   - SHA256: `99c037a7a9d554d6f4586319020bc1f55b46c58e439ee8fc7eff77523c1ee431`
   - Firmada con debug keystore del CI (difiere del que tenía la v0.1.0 instalada → uninstall necesario)

2. **Uninstall v0.1.0 + Install v1.1.0**
   - `adb uninstall com.miro.a11y` → Success
   - `adb install /tmp/miro-v1.1.0.apk` → Success
   - Versión instalada: `versionCode=2`, `versionName=1.1.0`

3. **Desinstalar wireless-adb**
   - `adb uninstall com.autoadb.wirelessdebug` → Success
   - Solo quedó `com.miro.a11y` instalado

4. **Setup manual** (el script `scripts/setup_adb.sh` **no existe** en este repo, fue borrado durante la fusión):
   - `adb shell pm grant com.miro.a11y android.permission.WRITE_SECURE_SETTINGS` → ok
   - `adb shell settings put secure enabled_accessibility_services com.miro.a11y/.MiroAccessibilityService:io.github.muntashirakon.AppManager/.accessibility.NoRootAccessibilityService`
   - `adb shell settings put secure accessibility_enabled 1`

5. **Cambiar launcher default a miro**
   - `adb shell pm set-home-activity --user 0 com.miro.a11y/.MiroLauncherActivity` → Success
   - Verificado: `cmd shortcut get-default-launcher` → `com.miro.a11y/.MiroLauncherActivity`

6. **Reboot #1 verificado**
   - `adb reboot` → exit 0
   - Espera 60-90s
   - `adb_tablet` → reconectado (puerto dinámico 36311, luego 5555)
   - **Mori confirmó que tuvo que activar Wireless Debugging manualmente** — el autostart del toggle corrió pero el WirelessDebugAutomator no completó el flow (clicks) sin ayuda manual
   - Estado post-reboot:
     - `accessibility_enabled = 1` ✓
     - `adb_wifi_enabled = 1` ✓
     - `enabled_accessibility_services` = `com.miro.a11y/.MiroAccessibilityService:io.github.muntashirakon.AppManager/.accessibility.NoRootAccessibilityService` (después de limpieza)
     - Launcher = `com.miro.a11y/.MiroLauncherActivity` ✓

## ⏳ Pendiente

### Reboot #2 y #3 (verificación 3 reboots)
- El handoff de Handoff 3 pedía 3 reboots consecutivos
- Solo se verificó 1 reboot (parcial — Mori intervino)
- Pendiente: 2 reboots más con verificación completa

### WirelessDebugAutomator
- Mori reportó que el flow de clicks automáticos **NO completó** el toggle de Wireless Debugging
- Tuvo que activarlo manualmente desde la UI
- Causa probable: el `WirelessDebugAutomator` busca Developer Options dentro de Settings, pero el path de UI cambió o la implementación de clicks no es robusta
- **Acción recomendada**: revisar el código de `WirelessDebugAutomator.step*()` y agregar más logs para diagnosticar

### Issue menor: duplicado en `enabled_accessibility_services`
- Tras el toggle post-boot, la lista tuvo `com.miro.a11y/.MiroAccessibilityService:AppManager:com.miro.a11y/com.miro.a11y.MiroAccessibilityService` (duplicado con paths distintos)
- Se normalizó manualmente quitando el path completo y dejando el corto
- **Sugerencia**: el toggle debería normalizar la lista antes de escribirla (quitar duplicados y full-path variants)

## 🐛 Issues encontrados durante la instalación

1. **Firma distinta** entre v0.1.0 (local) y v1.1.0 (CI) → uninstall forzado
2. **Script `setup_adb.sh` no existe** → setup manual
3. **Script `verify_autostart.sh` no existe** → verificación manual con comandos
4. **Toggle incompleto** → Mori tuvo que activar Wireless Debugging manualmente
5. **Duplicado en lista** → normalización manual

## 📋 Comandos para verificar (futuras sesiones)

```bash
# Estado actual
adb shell cmd shortcut get-default-launcher
adb shell settings get secure accessibility_enabled
adb shell settings get secure enabled_accessibility_services
adb shell settings get global adb_wifi_enabled

# Logs del service
adb logcat -d -s miro:V

# Reboot + verificar
adb reboot && sleep 60 && adb_tablet
```

## 🔗 Referencias

- Commit del fix: `834e5c7` (`fix: address 2026-09-01 audit issues`)
- CI run verde: 33480048590 (commit `027f128`)
- APK: `/tmp/miro-v1.1.0.apk` (sha256 `99c037a7...`)
- Handoff original: [[2026-09-01-corregir-issues-integracion]]
- Handoff de fusión: [[2026-09-01-fusion-wireless]]
- Auditoría: [[../06-Reglas/02-issues-encontrados]]

> **Editado desde local** — Hermes Agent
