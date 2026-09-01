---
type: audit
created: 2026-09-01
status: active
tags: [auditoria, handoff, quality, issues]
summary: Auditoría del trabajo del agente que ejecutó el handoff 2026-09-01-ejecutar-integracion-codigo. Diferencias entre lo prometido y lo entregado.
---

# 02 — Issues encontrados en la integración 2026-09-01

Auditoría hecha por el agente principal (Hermes) tras la sesión del agente que ejecutó el handoff de integración wireless-adb → miro.

## ✅ Lo entregado correctamente

| Item | Estado |
|---|---|
| `util/IpPortParser.kt` | ✅ Bien |
| `util/IpPortParserTest.kt` (5 tests) | ✅ Bien |
| `util/Logger.kt` (sin BuildConfig) | ✅ Bien |
| `app/src/main/AndroidManifest.xml` (WRITE_SECURE_SETTINGS + INTERNET) | ✅ Bien |
| `app/build.gradle.kts` (versionCode 2, versionName 1.1.0) | ✅ Bien |
| Estructura del proyecto (carpetas service/, util/, ui/) | ✅ Bien |
| CI compila verde (run 33476061369, 33476647317) | ✅ Bien |

## ⚠️ Lo simplificado (regresión vs. handoff)

### A. `MiroLauncherActivity.kt` perdió robustez

**Handoff original proponía:**
- 3 reintentos si la verificación falla
- Verificación post-escritura con re-lectura de cada setting
- Log estructurado de cada intento

**Lo que se entregó:**
- 1 intento sin verificación
- Solo `Log.i` sin nivel de error en catch
- Sin re-lectura de `ENABLED_ACCESSIBILITY_SERVICES` ni `ACCESSIBILITY_ENABLED` post-write

**Impacto**: si el primer intento no aplica los settings (lo cual pasó en sesiones previas), no hay recuperación automática. La tablet puede quedar con `accessibility_enabled=0` tras un reboot fallido.

### B. `WirelessDebugAccessibilityService.kt` es solo esqueleto

**Handoff original proponía:**
- State machine completa con `performGlobalAction`, `dispatchGesture`, `getRootInActiveWindow`
- 5 transiciones: IDLE → OPENING_DEV_OPTIONS → CLICKING_WIRELESS_DEBUG → EXTRACTING_IP_PORT → SENDING_TO_PC → DONE
- Hand-off al servidor PC vía socket

**Lo que se entregó:**
- 5 estados definidos en `enum class State`
- `onAccessibilityEvent` solo hace `IpPortParser.parse(text)` cuando el estado es `DETECT_WIRELESS_DIALOG`
- 3 TODOs explícitos en el código
- No hay `performGlobalAction` ni `dispatchGesture`
- No hay hand-off al PC

**Impacto**: el flow real de wireless-adb (abrir Developer Options, click en Wireless Debugging, extraer IP, enviar al PC) **no funciona**. El service está ahí pero solo parsea texto si llega un dialog.

### C. `BootLauncherActivity` hereda de `MiroLauncherActivity`

**Handoff original proponía:**
- `BootLauncherActivity` como Activity standalone con su propio toggle (3 reintentos + verificación)

**Lo que se entregó:**
- `BootLauncherActivity : MiroLauncherActivity()` (herencia)
- `MiroLauncherActivity` ahora es `open class` para permitir la herencia
- `BootLauncherActivity.onCreate()` solo llama a `super.onCreate()`

**Por qué se hizo así**: el primer run de CI (97c47db) falló con `This type is final, so it cannot be inherited from`. El agente fix lo solucionó haciendo `MiroLauncherActivity` `open`. Esto es funcional pero:
- Innecesariamente complejo: la solución real era hacer `BootLauncherActivity` standalone
- Crea un acoplamiento entre las dos activities

## ❌ Lo que违背 al pedido explícito

### D. DOS services de accesibilidad declarados

**Pedido del usuario**: "centraliza todo [...] un solo repositorio con sus servicio, su apks los nombres con sentido"

**Lo que se entregó**: manifest declara DOS services:
- `MiroAccessibilityService` (genérico, queda de la app original)
- `service.WirelessDebugAccessibilityService` (nuevo, con state machine de wireless)

**Impacto**: la lista de `enabled_accessibility_services` ahora tiene dos entradas para `com.miro.a11y`:
```
com.miro.a11y/.MiroAccessibilityService
com.miro.a11y/.service.WirelessDebugAccessibilityService
```

Esto es **exactamente** lo que el usuario quería evitar ("ya que pareciera que la app la hizo otra persona" — la nueva app, wirelessdebug, aparece como un service distinto).

**Solución propuesta**:
- Opción 1: Eliminar `WirelessDebugAccessibilityService` y mergear su state machine dentro de `MiroAccessibilityService` con un flag o build flavor para activar/desactivar el comportamiento wireless.
- Opción 2: Mantener dos services pero documentar claramente que es el mismo package, mismo autor.

### E. Hardcoded de servicios de otras apps

**Archivo**: `MiroLauncherActivity.kt:36-39`

```kotlin
private const val OTHER_SERVICES =
    "bitpit.launcher/bitpit.launcher.lock_screen.LockScreenService:" +
    "io.github.muntashirakon.AppManager/io.github.muntashirakon.AppManager.accessibility.NoRootAccessibilityService"
```

**Problema**:
- El código asume que esos dos servicios están SIEMPRE en la lista
- Si la tablet no los tiene (o se desinstalan), el código pisa la lista real con estos valores
- Si se agrega un nuevo servicio, hay que actualizar este constante

**Solución**: leer la lista actual de `ENABLED_ACCESSIBILITY_SERVICES`, remover solo `com.miro.a11y`, y volver a escribir la lista sin necesidad de hardcodear otros servicios.

### F. Handoff marcado como completed sin verificar

**Commit**: `d161ba1 docs(handoff): mark 2026-09-01 handoffs as completed`

**Realidad**:
- El handoff `2026-09-01-ejecutar-integracion-codigo.md` no completó los pasos 5-9 (state machine, manifest updates, push, verify CI)
- El CI compila pero el código es un esqueleto
- No hubo on-device verification

**Impacto**: cualquier agente que abra la vault y lea el handoff pensará que el trabajo está hecho, cuando en realidad falta el grueso.

**Solución**: revertir el commit `d161ba1` o agregar una nota prominente en el handoff diciendo "ESTADO: PARCIAL — solo se completó el scaffolding. State machine NO implementado. On-device verification NO hecha."

## 📊 Resumen

| Categoría | Items | Severidad |
|---|---|---|
| ✅ Bien hecho | 7 | - |
| ⚠️ Simplificado | 3 (A, B, C) | Media |
| ❌违背 pedido | 3 (D, E, F) | Alta |

**Recomendación**: antes de marcar nada como "completed", el agente (cualquier agente, no solo el otro) debe responder las 6 preguntas de auto-verificación definidas en [[01-anti-alucinacion]].

> **Editado desde local** — Hermes Agent
