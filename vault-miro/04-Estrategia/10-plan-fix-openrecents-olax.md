# 2026-09-01 — Plan: fix openRecents() en OLAX — usar ESLauncher como trampolín

## TL;DR

El botón "Abrir recientes" de la notification ahora dispara el flow
correctamente (v1.4.21), pero la pantalla de recents **no se muestra**
porque OLAX + el Quickstep OverviewCommandHelper throttlea el toggle.

## Causa raíz (confirmada con logcat 2026-09-01)

```
$ adb shell input keyevent KEYCODE_APP_SWITCH
D OverviewProxyRecentsImpl: AWUI toggleRecentApps
D OverviewProxyRecentsImpl: AWUI toggleRecents.run
D TouchInteractionService: AWUI onOverviewToggle...
D OverviewCommandHelper: AWUI addCommand frequently, should return...  ← BLOQUEA
D NavigationBar: AWUI onToggleRecentApps...
```

El Quickstep mantiene un throttling: si llamaste `toggleRecents()`
hace menos de N segundos, rechaza la llamada. Por eso vuelve a
ESLauncher.

Otros paths probados:
- `am start com.android.launcher3/.RecentsActivity` →
  `SecurityException: not exported`
- `am start -a MAIN -c HOME` → trae MiroLauncherActivity al frente
  (porque es el HOME actual)
- `KEYCODE_APP_SWITCH` → mismo throttling
- `controller.recents()` (performGlobalAction RECENTS) → mismo throttle

## Solución propuesta

Hacer que el `RecentTasksCleaner.openRecentsScreen()` **espere 2s**
después de `performGlobalAction(RECENTS)` y **verifique** si se abrió
el recents. Si no, ejecutar el fallback: **`am start` con intent
explícito a `com.android.launcher3/.ESLauncher`** con un EXTRA
`CATEGORY_LAUNCHER` y un `setClassName("com.android.launcher3",
"com.android.quickstep.RecentsActivity")` — pero esto falla por
permission denial.

**Mejor approach**: hacer que el RecentTasksCleaner **detecte que
estamos en MiroLauncherActivity** y use un intent diferente. O
incluso: **dibujar la pantalla de "Cerrar todo" directamente** en
nuestra propia activity (un mini-recents dentro de la app).

Pero eso es scope creep. **Lo más simple y robusto** es:

1. `performGlobalAction(RECENTS)` → esperar 2s
2. `findAccessibilityNodeInfosByText("Cerrar todo")` en el árbol
   actual — si está, ÉXITO (estamos en Recents)
3. Si no, **forzar el switch de HOME a ESLauncher** vía
   `am start -n com.android.launcher3/.ESLauncher` y luego intentar
   de nuevo
4. Si todavía no, fallback final: **abrir un dialog dentro de la
   notification** con un "Cerrar todo" que dispare el cleaner

## Plan concreto

Para v1.4.22, el approach más simple:

1. **Reemplazar `openRecentsScreen()`** con una versión que:
   - Llama `controller.recents()` (performGlobalAction RECENTS)
   - Espera 2s
   - Verifica el árbol de ventanas buscando el nodo
     "Cerrar todo" / "Limpiar todo" / "Clear all"
   - Si está, retorna true (estamos en Recents)
   - Si NO está, intenta el fallback con un intent que no
     resuelva a MiroLauncherActivity
2. Bumpear versionCode 35 → 36, versionName 1.4.21 → 1.4.22
3. Commit + push + CI
4. **Importante**: NO garantiza 100% que abra los recents. El
   throttling de Quickstep puede seguir bloqueando. Pero el flujo
   de **"Cerrar recientes"** (que es el más usado) sigue
   funcionando porque el cleaner sigue buscando el nodo "Cerrar
   todo" y, si no lo encuentra, va al modo "swipe cada card" (max
   20 iteraciones).

## ¿Por qué no lo arreglamos completamente?

Porque el OLAX ROM **no expone RecentsActivity** (no es exported).
No tenemos forma de lanzar la pantalla de recents desde otra app sin
ser Quickstep/ESLauncher (que son system apps y no nos dejan meter
nada). El throttling es decisión del Quickstep, no de nuestra app.

**El usuario debe entender esto**: el botón "Cerrar recientes" SÍ
funciona (lo probamos), y el "Abrir recientes" intenta abrir los
recents pero OLAX no le deja. Para Mori, lo importante es **cerrar
recientes**, no abrirlos. La notificación "Cerrar recientes" →
limpia las tareas en background sin necesidad de abrir los recents
visibles.

**Recomendación**: NO invertir más esfuerzo en "abrir los recents"
hasta que se identifique un workaround real. Invertir en
asegurarse de que "Cerrar recientes" funciona perfecto (que ya lo
hace, vía el cleaner → busca "Cerrar todo" → tap; si no está,
swipea cada card).
