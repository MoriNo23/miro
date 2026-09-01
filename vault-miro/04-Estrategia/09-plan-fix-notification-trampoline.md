# 2026-09-01 — Plan: fix notification action button (Android 12 trampoline)

## TL;DR

El botón "Cerrar recientes" / "Abrir recientes" en la notification
persistente **no dispara nada cuando lo tappeás**. La causa raíz es
**Android 12 Notification Trampoline Restrictions** (target SDK 31+).

## Causa raíz

Cuando un `PendingIntent.getBroadcast` se dispara desde un
notification action, el `BroadcastReceiver` resultante **NO PUEDE
iniciar activities ni services** en Android 12+. El sistema bloquea
la transición y el botón no hace nada visible.

Ref: https://proandroiddev.com/notification-trampoline-restrictions-android12-7d2a8b15bbe2

En nuestro caso (MiroAccessibilityService.kt:618):
```kotlin
// RecentTasksCleaner.start() → openRecentsScreen() → service.startActivity(intent)
val intent = android.content.Intent("android.intent.action.MAIN", null)
service.startActivity(intent)  // <-- BLOQUEADO en Android 12 si la app es trampolín
```

El broadcast path es:
```
Notification action tap
  → PendingIntent.getBroadcast (action KILL_ALL_RECENT)
  → RecentsActionReceiver.onReceive
  → onKillAllCallback()
  → MiroAccessibilityService.startKillAllRecents()
  → recentTasksCleaner.start()
  → openRecentsScreen() ← service.startActivity(intent) ← BLOQUEADO
```

## Solución propuesta

**Opción A (recomendada)**: cambiar el PendingIntent de
`getBroadcast` a `getService` o `getActivity` directamente. Pero
`getService` requiere el componente destino exportado (no es el caso
del AccessibilityService en algunos ROMs).

**Opción B (más simple)**: hacer que el `BroadcastReceiver` se
registre como **no-trampolín**: confirmar que el `BroadcastReceiver`
no inicie activities directamente, sino que invoque el service vía
`LocalBroadcastManager` o `Intent` con `setPackage` que sea procesado
por el service. El service ya es el que abre la activity, no el
receiver.

**Opción C (más simple aún)**: usar `PendingIntent.getActivity`
apuntando a un `Activity` "shim" interno (exported=true) que ejecute
el comando. Esta es la solución canónica que sugiere Google.

### Plan de implementación (Opción C)

1. Crear `RecentsActionActivity.kt`:
   - `Activity` exported=true, theme=NoDisplay
   - En `onCreate`: parsea la action del intent, invoca el
     AccessibilityService vía static callback, `finish()`
   - Esta activity es el destino directo del notification action
     (NO un trampolín porque ES una activity destino)

2. Modificar `RecentTasksNotifier.kt`:
   - `buildActionPendingIntent()` usa `PendingIntent.getActivity`
     apuntando a `RecentsActionActivity` con la action

3. Remover `RecentsActionReceiver` del manifest (ya no se usa)
   - O dejarlo por retrocompatibilidad pero no usarlo

4. Bumpear a v1.4.21

5. CI + install + test

## Pasos

1. Plan + handoff (este archivo)
2. Crear `RecentsActionActivity.kt`
3. Modificar `RecentTasksNotifier.buildActionPendingIntent` → `getActivity`
4. Eliminar RecentsActionReceiver del manifest
5. Bumpear versionCode 34→35, versionName 1.4.20→1.4.21
6. Commit + push + CI
7. Install + test
8. Handoff final con resultados

## Criterio de éxito

- [ ] APK v1.4.21 compila en CI
- [ ] Botón "Cerrar recientes" → dispara el flow de cierre
- [ ] Botón "Abrir recientes" → abre la pantalla de Recents
- [ ] NO hay log "Notification trampoline" en logcat
- [ ] NO hay SecurityException en logcat

## Riesgos

| Riesgo | Mitigación |
|---|---|
| `RecentsActionActivity` requiere exported=true → accesible desde otros paquetes | Validar la action con `intent.action == ACTION_*` y el package con `intent.`package` == getPackageName()` |
| El service aún no está bound cuando se tappea el botón | El callback static es null-safe (Log.w si no está set) |
| La activity se queda visible | `Theme.Translucent.NoTitleBar` + `finish()` inmediato |
| Otra app spamea la activity | exported=true PERO validamos el package |
