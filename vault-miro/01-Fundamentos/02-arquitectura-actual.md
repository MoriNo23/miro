---
type: fund
created: 2026-09-01
status: active
tags: [miro, arquitectura, launcher, accessibility-service, olax]
summary: Arquitectura unificada del proyecto miro. Un solo package, un solo service, un launcher-wrapper con state machine interna.
---

# 02 — Arquitectura actual

## Componentes

```
ROM OLAX/Allwinner (post-boot)
    ↓ (único componente que arranca solo)
BootLauncherActivity (HOME launcher, singleTask)
    ↓ onCreate → Thread
reenableAccessibility()  ← toggle completo (3 reintentos, verificación)
    ↓
WirelessDebugAccessibilityService  ← service unificado
    ↓ state machine: IDLE → OPENING_DEV_OPTIONS → CLICKING_WIRELESS_DEBUG → EXTRACTING_IP_PORT → SENDING_TO_PC → DONE
POST http://<PC-IP>:port/connect {ip, port}
    ↓
server.py ejecuta: adb connect 10.42.1.x:xxxxx
    ↓
Hand off a ESLauncher (launcher real)
```

## Tabla de componentes

| Componente | Path | Función |
|---|---|---|
| `BootLauncherActivity` | `app/src/main/java/com/miro/a11y/BootLauncherActivity.kt` | HOME launcher. Toggle a11y + handoff a ESLauncher |
| `WirelessDebugAccessibilityService` | `app/src/main/java/com/miro/a11y/service/WirelessDebugAccessibilityService.kt` | State machine para automatizar clicks de Wireless Debugging |
| `MiroController` | `app/src/main/java/com/miro/a11y/MiroController.kt` | Controlador genérico del service |
| `MiroSocketServer` | `app/src/main/java/com/miro/a11y/MiroSocketServer.kt` | Socket de control |
| `IpPortParser` | `app/src/main/java/com/miro/a11y/util/IpPortParser.kt` | Parser puro de IP:Port (testeado) |
| `Logger` | `app/src/main/java/com/miro/a11y/util/Logger.kt` | Logging centralizado |

## Convenciones de nombres

- **Package raíz**: `com.miro.a11y` (mantenido por compatibilidad con setup ya hecho en la tablet)
- **Subpackages**: `.service`, `.util`, `.ui`
- **Activity launcher**: `*LauncherActivity` suffix
- **Service**: `*AccessibilityService` suffix
- **Util**: nombre descriptivo sin suffix (ej. `IpPortParser`, no `IpPortParserUtil`)

> **Editado desde local** — Hermes Agent
