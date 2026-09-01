# 2026-09-01 — NOTA #3: Botón notificación "Cerrar recientes" no funciona

## Síntoma

El botón "Cerrar recientes" en la notificación persistente de
`RecentTasksNotifier` **no sirve**. Cuando se tappea, no pasa nada
visible en la tablet.

## Causa probable

La notificación usa `PendingIntent.getBroadcast` + `BroadcastReceiver`
interno. Posibles causas:

1. **El `BroadcastReceiver` no está registrado en el manifest**.
   Si es dinámico, se pierde cuando el service se destruye.
2. **El `PendingIntent` no se dispara** por falta de permisos
   (target SDK 31+ requiere `FLAG_IMMUTABLE` o `FLAG_MUTABLE`).
3. **El receiver dispara el flow pero el flow falla** porque OLAX
   no expone el botón "Cerrar todo" de Recents al a11y.

## Fix propuesta del usuario

> "adicional issue hay un boton de notificaciones que dice eliminar todas las recents app
> pero ese boton notificacion no sirve debe mejor ejecutar el recents botton si es posible
> si no es posible entonces busca alguna forma de arreglar eso para que yo pueda ver
> las recents apps"

**Prioridad 1**: que el botón abra la pantalla Recents vía
`GLOBAL_ACTION_RECENTS` o `startActivity(Intent("android.intent.action.VIEW")
  .setClassName("com.android.systemui", "com.android.quickstep.RecentsActivity"))`.

**Prioridad 2** (si Prioridad 1 no funciona en OLAX): que el botón
abra el dialog de "Force stop" o "App info" de la última app, que el
user pueda elegir qué cerrar.

**Prioridad 3** (última opción): lista todas las apps recientes vía
`ActivityManager.getRecentTasks()` y mostrarlas en una nueva activity
"CustomRecents" con un botón "Cerrar" por app.

## Referencias

- v1.3.5: `RecentTasksCleaner` intentaba buscar "Cerrar todo" → falló
- v1.3.1: cambió a `performGlobalAction(GLOBAL_ACTION_RECENTS)` + wait 2s
- La pantalla Recents de OLAX **no expone el botón "Cerrar todo"** al
  a11y (verificado en handoff 2026-09-01-end-to-end-reboot-validated.md)

## Estado

Pendiente. NO implementar hasta resolver:
1. Issue #1 (MiroLauncherActivity pantalla oscura)
2. Issue #2 (coordenadas del dialog WifiDebuggingActivity)
