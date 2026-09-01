# 2026-09-01 — Handoff: 3 issues críticos identificados, plan listo

## TL;DR

Identifiqué 3 issues + 1 secundario que están bloqueando el auto-arranque
post-reboot **100% desde la APK** (sin trucos de la PC). El plan completo
está en `vault-miro/04-Estrategia/03-plan-3-issues-2026-09-01.md`.

**NO TOCO CÓDIGO TODAVÍA** — Mori pidió crear handoff + notas + plan primero.
Eso es lo que hice.

## Issues (resumen)

### #1 — MiroLauncherActivity pantalla oscura
- **Síntoma**: la activity se queda visible ~10s mostrando pantalla negra
  mientras hace el toggle + espera 5s de BIND_GRACE_MS.
- **Causa**: `Theme.Translucent.NoTitleBar` + activity sin `setContentView`
  + duración del toggle. El compositor de OLAX no maneja bien "activity
  sin contenido, sin wallpaper".
- **Bloqueo**: el sistema muestra el dialog "Selecciona launcher" en cada
  reboot porque MiroLauncherActivity está visible.
- **Nota**: `vault-miro/06-Reglas/04-miro-launcher-pantalla-oscura.md`
- **Verificado**: comparé theme con v1.3.5 (que sí funcionaba) — **mismo
  theme**. Entonces el problema NO es el theme, es la duración. Pendiente
  investigar más.

### #2 — Tap PERMITIR no acierta
- **Síntoma**: el flow OLAX QS-tile corre completo (todos los states OK)
  pero el último tap en PERMITIR (721, 372) no cierra el dialog.
- **Causa probable**: bounds incorrectos o el tap necesita variación.
- **Bloqueo**: `adb_wifi_enabled = 0` después del flow. Wireless debug
  no se activa.
- **Nota**: `vault-miro/06-Reglas/05-wifi-debugging-dialog-coords.md`

### #3 — Botón notificación "Cerrar recientes" no funciona
- **Síntoma**: el botón en la notificación no dispara nada visible.
- **Causa probable**: `PendingIntent` sin `FLAG_IMMUTABLE` o
  `BroadcastReceiver` no registrado.
- **Bloqueo**: el user no puede cerrar las recents apps desde la tablet.
- **Pedido de Mori**: que abra la pantalla Recents o haga una
  `CustomRecents` activity con lista + botón Cerrar.
- **Nota**: `vault-miro/06-Reglas/06-notification-recents-no-funciona.md`

### #4 (secundario) — WRITE_SECURE_SETTINGS se pierde tras uninstall
- **Síntoma**: tras `uninstall + install`, el grant se pierde. La
  activity crashea con `SecurityException` en `attemptToggle`.
- **Causa**: `pm grant` solo se aplica al package actual. Cualquier
  reinstall borra el grant.
- **Bloqueo**: bloquea la Fase 5 (validación end-to-end) si el user
  reinstala.
- **Fix propuesta**: verificar en `--setup` después del grant, y
  en el `Application.onCreate` chequear el permission y log warning.

## Estado del repo

- Branch: `main`
- Último commit: `096abc2 docs(notas): 3 issues críticas pendientes`
- APK instalada: v1.4.11 (`/tmp/miro-v1.4.11.apk`)
- Truco PC borrado: ✓
- Service: ✓ bound post-reboot (v1.4.11)
- Wireless debug: 0 (issue #2)
- USB: conectado (`6c000c6d480109622dd`)

## Próximo paso

Implementar Fase 1 (WRITE_SECURE_SETTINGS) primero porque las otras
dependen de eso. Si Mori confirma que el plan está bien, arranco.

## Reglas de Mori a no olvidar

- NO compilar en fullmetal (CI solamente)
- Mantener package `com.miro.a11y`
- NO trucos de la PC (regla #7)
- SIEMPRE revisar handoffs al continuar de compactación (regla #8)
- ESLauncher es el HOME permanente (regla original)
