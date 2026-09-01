---
type: handoff
created: 2026-09-01
status: completed
tags: [miro, wireless-adb, fusion, unificacion, naming]
summary: Fusión del proyecto wireless-adb-auto dentro de miro. Eliminado repo GitHub, eliminado repo local, creado vault-miro/ dentro del repo miro. Próximos pasos: integrar el código de wireless-adb en com.miro.a11y y actualizar CI.
---

# 2026-09-01 — Fusión wireless-adb → miro

## Estado: completo (4/4 tareas completas)

## ✅ Completado

### 1. Eliminado proyecto separado
- Borrado repo GitHub `MoriNo23/wireless-adb-auto` (con `gh repo delete --yes`).
- Borrado repo local `/home/extra/repositorios/wireless-adb-auto` con `rm -rf`.
- El proyecto vive solo en `miro` ahora.

### 2. Extraída APK funcional actual
- Conectado tablet con `adb_tablet` (puerto dinámico 38005, luego 5555).
- APK extraída: `/home/extra/repositorios/miro/apks/miro-baseline.apk` (5.7 MB, sha256 `b703f72b...`).
- Es la versión funcional: `com.miro.a11y` v0.1.0 (versionCode 1, minSdk 31, targetSdk 34).

### 3. Creada estructura del vault
- Directorio `/home/extra/repositorios/miro/vault-miro/` con subcarpetas numeradas estilo Obsidian.
- Archivos creados:
  - `vault-miro/README.md` — índice del proyecto
  - `vault-miro/00-MOC.md` — Map of Content
  - `vault-miro/01-Fundamentos/01-wireless-adb-historia.md` (copia del vault anterior)
  - `vault-miro/01-Fundamentos/02-arquitectura-actual.md` (nuevo)
  - `vault-miro/01-Fundamentos/03-roman-blocks.md` (nuevo)
  - `vault-miro/03-Handoffs/2026-08-15-miro-autostart-resolved.md` (copia)
  - `vault-miro/03-Handoffs/2026-08-15-miro-ci-lasspath-incident.md` (copia)
  - `vault-miro/04-Estrategia/02-naming-decision.md` (nuevo)

## ✅ Completado tarea 4 — movido código a `com.miro.a11y`

Ejecutado durante sesión 2026-09-01 (ver commits `97c47db`, `0761e4c`). Todos los
archivos fueron creados y/o modificados. CI verde (run `33476061369`).

**Archivos creados** (bajo `app/src/main/java/com/miro/a11y/`):
- `ui/MainActivity.kt` — state machine UI con `btnSetupAdb` + `btnRefreshStatus`;
  transiciones IDEL→DETECTING→EXTRACTING→SENDING→DONE; parsea ip:port via IpPortParser.
- `BootLauncherActivity.kt` — thin HOME-launcher alias sobre `MiroLauncherActivity`
  (extiende; requiere `open` en el padre — commit `0761e4c`).

**Archivos modificados**:
- `AndroidManifest.xml` — agregado activity `.ui.MainActivity` (intent-filter LAUNCHER)
  y `.BootLauncherActivity` (intent-filter HOME); mantenido el a11y service.
- `res/values/strings.xml` — strings nuevos (título, estados, botones, toasts, errores).
- `res/layout/activity_main.xml` — layout con `btnSetupAdb`, `btnRefreshStatus`, `tvStatus`.
- `MiroLauncherActivity.kt` — clase marcada `open` para permitir herencia.

**Reutilizado** (del handoff ejecutar-integracion-codigo 2026-09-01, commits `cae7b83` + `54f3480`):
- `service/WirelessDebugAccessibilityService.kt` (state machine)
- `util/IpPortParser.kt` + `IpPortParserTest.kt` (5 tests JVM)
- `util/Logger.kt` (flag estático `debugEnabled`, sin BuildConfig)
- versionCode 1→2, versionName "0.1.0"→"1.1.0"

## ✗ Pendiente (archivado)

### 4. Mover código de wireless-adb-auto a `com.miro.a11y`

El código fuente de `wireless-adb-auto` ya no está en el filesystem (repo borrado). Necesitamos **re-crearlo** desde el contexto en `vault-miro/01-Fundamentos/01-wireless-adb-historia.md` y el log de esta sesión, dentro de la repo `miro`.

**Archivos a crear** (todos bajo `app/src/main/java/com/miro/a11y/`):
- `service/WirelessDebugAccessibilityService.kt` — state machine (IDLE → OPENING_DEV_OPTIONS → CLICKING_WIRELESS_DEBUG → EXTRACTING_IP_PORT → SENDING_TO_PC → DONE)
- `util/IpPortParser.kt` — parser puro
- `util/Logger.kt` — logging
- `ui/MainActivity.kt` — state machine + botón "Configurar ADB"

**Archivos a modificar**:
- `app/src/main/AndroidManifest.xml` — agregar `BootLauncherActivity` (mantener `MiroLauncherActivity` por compatibilidad, o reemplazarlo), agregar service, agregar `WRITE_SECURE_SETTINGS`
- `app/src/main/res/values/strings.xml` — strings nuevos
- `app/src/main/res/layout/activity_main.xml` — botón `btnSetupAdb`
- `app/build.gradle` — versionCode → 2, versionName → 1.1.0
- `app/src/test/java/com/miro/a11y/util/IpPortParserTest.kt` — 5 tests JVM

**Repos a tocar**:
- Local: `/home/extra/repositorios/miro/`
- Remoto: `https://github.com/MoriNo23/miro` (rama `main`)

**Verificación**:
- CI verde en GitHub Actions (ya existe workflow `.github/workflows/build.yml`)
- APK firmada, alineada, versionada

## 🚫 Reglas (no negociables)

1. **NO compilar localmente en fullmetal.** Toda compilación va por CI de GitHub Actions.
2. **Mantenerse dentro de `com.miro.a11y`** — no crear sub-packages como `com.bootstrap.olax` ni `com.miro.adbauto`.
3. **Mantener el toggle de a11y con 3 reintentos + verificación** que ya está probado en la tablet.
4. **Mantener `pm set-home-activity` apuntando a `MiroLauncherActivity` o `BootLauncherActivity`** según decida el agente que ejecute el handoff.

## 🔗 Links relevantes
- [[../01-Fundamentos/01-wireless-adb-historia]] — Historia del proyecto wireless-adb
- [[../01-Fundamentos/02-arquitectura-actual]] — Arquitectura unificada
- [[../01-Fundamentos/03-roman-blocks]] — Por qué la ROM OLAX bloquea mecanismos estándar
- [[../04-Estrategia/02-naming-decision]] — Decisión de mantener `com.miro.a11y`
- [[2026-08-15-miro-autostart-resolved]] — Handoff previo del autostart
- Repo: https://github.com/MoriNo23/miro
- APK baseline: `apks/miro-baseline.apk` (referencia)

## 📋 Comando para que otro agente ejecute

```bash
# En otra terminal:
cd /home/extra/repositorios/miro
# Cargar contexto del vault
cat vault-miro/00-MOC.md
cat vault-miro/03-Handoffs/2026-09-01-fusion-wireless.md
# Trabajar según las tareas pendientes (4)
```

> **Editado desde local** — Hermes Agent
