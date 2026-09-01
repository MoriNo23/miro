# AGENTS.md — Reglas operativas para agentes que trabajen en este repo

> **Lee esto ANTES de hacer cualquier cambio en el código.**
> El proyecto `miro` es un launcher-wrapper de Android con service de accesibilidad. Tiene reglas estrictas porque corre en una tablet OLAX Magic Q1 sin root, con ROM custom, y un setup cuidadoso.

## 🚫 Reglas INAMOVIBLES

### 1. NO compilar localmente en fullmetal
- **Prohibido**: `./gradlew`, `gradle`, `make`, `npm run build`, `cargo build`, o cualquier compilación nativa.
- **Por qué**: fullmetal no tiene Android SDK + NDK completos, y la compilación local mete ruido en el proyecto.
- **Cómo verificar build**: solo vía `gh run watch --exit-status` (CI de GitHub Actions).
- **Mecanismo de enforcement**: script `/home/fullmetal/.hermes/scripts/compile-guard.sh` + skill `compile-restriction`.

### 2. Mantener `com.miro.a11y` como package
- **No renombrar** el package. Es la decisión de Mori 2026-09-01 (ver `vault-miro/04-Estrategia/02-naming-decision.md`).
- Renombrar implica reinstalar la app, perder `WRITE_SECURE_SETTINGS`, re-fijar el HOME launcher — 3 comandos ADB extras.

### 3. UN solo accessibility service
- **Pedido explícito del usuario**: "centraliza todo".
- **No declarar dos services en el manifest** aunque sean del mismo package. Si hay un nuevo comportamiento (ej. wireless debug), **mergea** su lógica en `MiroAccessibilityService` con un flag o un sub-componente.
- Verificar con `adb shell dumpsys package com.miro.a11y | grep -A1 AccessibilityService` que solo aparece uno.

### 4. Toggle de a11y con 3 reintentos + verificación
- **Handoff 2026-08-15-miro-autostart-resolved** lo confirmó: un solo intento no es suficiente. La ROM OLAX a veces no aplica el primer write.
- **Patrón obligatorio**:
  ```kotlin
  while (attempt < MAX_RETRIES) {
      // remove from list → flag 0 → wait 2s → re-add → flag 1
      // re-read each setting to verify
      // if verified: return
      // else: retry after 1s
  }
  ```

### 5. NO hardcodear nombres de servicios de otras apps
- En `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` solo remover/agregar el service de `com.miro.a11y`. No asumir que `bitpit.launcher` o `AppManager` están siempre.
- **Patrón correcto**:
  ```kotlin
  val current = Settings.Secure.getString(cr, ENABLED_ACCESSIBILITY_SERVICES)
  val filtered = current.split(":").filter { !it.contains("com.miro.a11y") }
  Settings.Secure.putString(cr, ENABLED_ACCESSIBILITY_SERVICES, filtered.joinToString(":"))
  ```

### 6. NO marcar como completed sin verificar
- Si el código tiene TODOs, es **esqueleto**, no integración.
- Si el state machine no tiene `performGlobalAction` o `dispatchGesture` implementados, el flow **no funciona**.
- Si el commit dice "feat:" debe haber funcionalidad, no solo scaffolding.
- **Antes de cerrar**: revisar las 6 preguntas de auto-verificación en `vault-miro/06-Reglas/01-anti-alucinacion.md`.

## 📂 Estructura del repo

```
miro/
├── app/
│   ├── build.gradle.kts              (Kotlin DSL, AGP/Kotlin pinned)
│   ├── src/main/
│   │   ├── AndroidManifest.xml       (1 HOME launcher, 1 service, WRITE_SECURE_SETTINGS)
│   │   ├── java/com/miro/a11y/
│   │   │   ├── MiroAccessibilityService.kt
│   │   │   ├── MiroController.kt
│   │   │   ├── MiroLauncherActivity.kt
│   │   │   ├── MiroSocketServer.kt
│   │   │   ├── BootLauncherActivity.kt (opcional, si se quiere como alias)
│   │   │   ├── util/
│   │   │   │   ├── IpPortParser.kt
│   │   │   │   └── Logger.kt
│   │   │   └── service/              (NO usar — el service va directo en com.miro.a11y)
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml
│   │   │   ├── values/strings.xml
│   │   │   └── xml/accessibility_service_config.xml
│   └── src/test/java/com/miro/a11y/util/  (JVM tests)
├── vault-miro/                       (Obsidian-style vault)
│   ├── 00-MOC.md                     (índice navegable)
│   ├── 01-Fundamentos/               (teoría, historia)
│   ├── 02-Herramientas/              (scripts, configs)
│   ├── 03-Handoffs/                  (sesiones discretas)
│   ├── 04-Estrategia/                (decisiones, roadmap)
│   ├── 05-Diseno/                    (arquitectura, diagramas)
│   └── 06-Reglas/                    (anti-alucinación, etc.)
├── apks/
│   └── miro-baseline.apk             (APK extraída de la tablet)
└── scripts/
    ├── setup_adb.sh                  (one-time setup)
    └── verify_autostart.sh           (post-reboot verify)
```

## 📋 Comandos útiles

### Conectar tablet
```bash
adb_tablet
# o
adb connect 10.42.1.63:5555
```

### Verificar estado del autostart
```bash
adb shell settings get secure accessibility_enabled     # → 1
adb shell settings get secure enabled_accessibility_services  # debe incluir com.miro.a11y/...
adb shell cmd shortcut get-default-launcher             # → com.miro.a11y/...
```

### Ver logs del service
```bash
adb logcat -d -s miro:V WirelessDebug:V
```

### Setup one-time (si es primera vez o reinstall)
```bash
bash scripts/setup_adb.sh --serial 10.42.1.63:5555
```

### Verificar CI
```bash
gh run watch --exit-status
```

## 🧪 Tests

Tests JVM en `app/src/test/java/`. Corren automáticamente en CI. Si agregás un test nuevo:
1. Ubicá en `app/src/test/java/com/miro/a11y/<paquete>/`
2. Usá JUnit 4.13.2 (ya en `app/build.gradle.kts`)
3. Naming: `<Clase>Test.kt`
4. Naming de funciones: backticks para nombres legibles (`` `parses well-formed IP:port` ``)

## 🔗 Referencias clave

- **Vault**: `vault-miro/00-MOC.md` para navegar todo
- **Handoff original del autostart**: `vault-miro/03-Handoffs/2026-08-15-miro-autostart-resolved.md`
- **Handoff de fusión wireless**: `vault-miro/03-Handoffs/2026-09-01-fusion-wireless.md`
- **Handoff ejecutable**: `vault-miro/03-Handoffs/2026-09-01-ejecutar-integracion-codigo.md`
- **Reglas anti-alucinación**: `vault-miro/06-Reglas/01-anti-alucinacion.md`
- **Issues auditoría 2026-09-01**: `vault-miro/06-Reglas/02-issues-encontrados.md`
- **Repositorio**: https://github.com/MoriNo23/miro

> **Editado desde local** — Hermes Agent
