# 2026-09-01 — Plan: Custom Recents UI (v1.4.22)

## Goal

El botón "Abrir recientes" de la notification (v1.4.21) **dispara el
flow correctamente** pero el Quickstep de OLAX lo throttlea y nunca
muestra la pantalla de recents. Para sortear eso, construimos
**nuestra propia UI de Recents** como una `Activity` de la app
`com.miro.a11y`.

## Por qué es viable

Un AccessibilityService con `canRetrieveWindowContent=true` PUEDE:

1. **Listar tasks corriendo** vía `ActivityManager.getRunningTasks()`
   (necesita `GET_TASKS` permission — normal, no dangerous)
2. **Cerrar procesos en background** vía
   `ActivityManager.killBackgroundProcesses(packageName)` (necesita
   `KILL_BACKGROUND_PROCESSES` permission — normal, no dangerous)
3. **Hacer tap/swipe en cualquier ventana** vía `dispatchGesture`
4. **Mostrar una activity fullscreen** con una `RecyclerView` que
   muestre icon+name+X de cada app

Lo que **NO** puede:
- `forceStopPackage()` (necesita `FORCE_STOP_PACKAGES` signature)
- matar apps en foreground sin ir a Recents (la pantalla del
  sistema)

**Pero** podemos hacer algo equivalente y mejor: hacer que el
AccessibilityService swipe la app fuera del recents (que es
exactamente lo que `RecentTasksCleaner` ya hace, pero extendido).

## Diseño de la UI Custom Recents

```
┌────────────────────────────────────────────┐
│  Recents                [Cerrar todas]     │
├────────────────────────────────────────────┤
│  [icon] Discord                  [X]       │
│  [icon] Brave                   [X]       │
│  [icon] Settings                [X]       │
│  [icon] WhatsApp                [X]       │
│  [icon] Camera                  [X]       │
└────────────────────────────────────────────┘
```

- Activity `RecentsOverviewActivity`, fullscreen, dark theme
- `RecyclerView` con `RecentsAdapter`
- Cada item: PackageIcon + PackageLabel + botón X
- Tap en X → invoca al AccessibilityService vía callback
- Botón "Cerrar todas" arriba → invoca al AccessibilityService para
  matar todas

## Cambios concretos

### Permisos nuevos en AndroidManifest.xml
```xml
<uses-permission android:name="android.permission.GET_TASKS" />
<uses-permission android:name="android.permission.KILL_BACKGROUND_PROCESSES" />
```

### Nueva activity
- `app/src/main/java/com/miro/a11y/RecentsOverviewActivity.kt`:
  - Fullscreen activity (Theme.Miro.NoActionBar)
  - Lee `getRunningTasks()` en onCreate
  - Muestra RecyclerView
  - Botón "Cerrar todas" → invoca el callback static del service
  - Tap en X individual → invoca el callback con packageName

### Modificar el notification action
- `RecentTasksNotifier.buildActionPendingIntent(ACTION_OPEN_RECENTS)`
  ahora apunta a `RecentsOverviewActivity` en vez de
  `RecentsActionActivity`

### Modificar el AccessibilityService
- Nuevo método `killPackage(packageName: String): Boolean`:
  - `am.killBackgroundProcesses(packageName)`
  - si falla, intenta `dispatchGesture` en la pantalla de recents
- Nuevo método `killAllPackages(packages: List<String>)`: itera

## Pasos

1. Plan + handoff (este archivo)
2. Agregar permisos al manifest
3. Crear `RecentsOverviewActivity.kt`
4. Modificar `RecentTasksNotifier.kt` para apuntar el "Abrir
   recientes" a la nueva activity
5. Modificar `MiroAccessibilityService.kt` para tener
   `killPackage` y `killAllPackages`
6. Bumpear versionCode 35 → 36, versionName 1.4.21 → 1.4.22
7. Commit + push + CI
8. Install + test
9. **Reboot real** (regla #9, #11) para validar

## Riesgos

| Riesgo | Mitigación |
|---|---|
| `GET_TASKS` deprecated en API 21+ pero todavía funciona | Si no funciona, usar `UsageStatsManager` con `PACKAGE_USAGE_STATS` (permission special) |
| `killBackgroundProcesses` no mata apps en foreground | Mostrar un toast "app X está en foreground, no se puede cerrar" |
| Lista de tasks no incluye todas las apps (es una vista parcial) | Mostrar lo que devuelve, marcar "running" vs "background" |
| El botón "Cerrar todas" tarda mucho | Async con progress bar |

## Criterio de éxito

- [ ] APK v1.4.22 compila en CI
- [ ] "Abrir recientes" → abre la RecentsOverviewActivity fullscreen
- [ ] La lista muestra las apps que están corriendo
- [ ] Tap en X individual → cierra la app
- [ ] "Cerrar todas" → cierra todas las apps en background
- [ ] Reboot real + post-boot: la notification sigue funcionando
