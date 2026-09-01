# 2026-09-01 — Plan: Fase 4 (Recents notification) + Fase 2 (launcher oscuro)

## Estado actual (v1.4.12)

✅ Fase 1 completa: WRITE_SECURE_SETTINGS persistente
✅ Issue #2 resuelto (side-effect): dialog coords del flow OLAX QS-tile funcionan
- Wireless debug = 1 después de `--setup` + `am start`
- Service BOUND, Auto-start ON, socket listening

## Pendiente

### Fase 4: Botón notificación "Cerrar recientes" no funciona
- **Causa probable**: `PendingIntent` sin `FLAG_IMMUTABLE` o `FLAG_MUTABLE`
  en Android 12+ (target SDK 31)
- **Causa alternativa**: `BroadcastReceiver` no registrado en manifest
- **Pedido de Mori**:
  1. Prioridad 1: que abra la pantalla Recents via `GLOBAL_ACTION_RECENTS`
     o `startActivity(RecentsActivity)`
  2. Prioridad 2: si OLAX no expone el botón "Cerrar todo" al a11y (confirmado),
     crear `CustomRecents` activity que liste las tareas recientes con un
     botón "Cerrar" por app

### Fase 2: Pantalla oscura del launcher
- **Causa**: MiroLauncherActivity se queda visible ~11s durante el toggle
- **Fix propuesta**: `Theme.NoDisplay` + mover el toggle a `Application.onCreate`
- **Riesgo**: si OLAX no soporta `Theme.NoDisplay`, podemos perder el
  HOME wrapper completo

## Plan Fase 4 (este turno)

### Paso 1: leer el código actual
- [ ] `RecentTasksNotifier.kt` — ver cómo se crea el PendingIntent
- [ ] `BroadcastReceiver` interno — ver si está registrado en manifest
- [ ] `MiroAccessibilityService.kt` — ver qué hace al recibir "close recents"

### Paso 2: elegir estrategia
- [ ] Opción A: arreglar el PendingIntent con FLAG_IMMUTABLE + receiver en manifest
- [ ] Opción B: cambiar a GLOBAL_ACTION_RECENTS (que abre la pantalla Recents)
  - OLAX expone la pantalla Recents? Sí (es parte de SystemUI)
  - Pero la pantalla Recents de OLAX no expone el botón "Cerrar todo" al a11y
- [ ] Opción C: crear CustomRecents activity (más trabajo pero mejor UX)

**Decisión**: opción B primero (más simple, menos código). Si OLAX
responde a GLOBAL_ACTION_RECENTS, ya está. Si no, pasar a opción C.

### Paso 3: implementar
- [ ] En `RecentTasksNotifier`, cambiar el `PendingIntent` de broadcast a
      `startActivity` con `GLOBAL_ACTION_RECENTS`
- [ ] Bumpear versionCode 26 → 27
- [ ] Commit + push + CI

### Paso 4: testear
- [ ] Instalar v1.4.13
- [ ] Tappear la notificación
- [ ] Verificar que la pantalla Recents abre

## Criterio de éxito Fase 4

- [ ] Notificación tiene botón "Cerrar recientes"
- [ ] Al tappear el botón, la pantalla Recents abre
- [ ] La tablet sigue funcional después (no se queda en Recents para siempre)

## Riesgos

| Riesgo | Mitigación |
|---|---|
| `GLOBAL_ACTION_RECENTS` no funciona en OLAX | Fallback: `startActivity(RecentsActivity)` |
| El botón "Cerrar todo" de Recents no se ve | Crear `CustomRecents` activity en la siguiente fase |
| `startActivity` desde notificación requiere permission | `setClipData` o `Intent.FLAG_ACTIVITY_NEW_TASK` |

## Entregables

- v1.4.13 con notificación Recents funcional
- Handoff en `vault-miro/03-Handoffs/2026-09-01-v1.4.13-recents-fixes.md`
- Este plan reemplazado por el resultado real
