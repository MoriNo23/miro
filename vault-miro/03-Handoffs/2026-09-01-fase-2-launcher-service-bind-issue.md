# 2026-09-01 — Handoff: Fase 2 intento de fix pantalla oscura — service NOT bound

## TL;DR

Mori me corrigió el approach. El service se bindea correctamente SOLO si:

1. MiroLauncherActivity es launcher (intent-filter HOME) ✓ (ya lo es)
2. El toggle corre mientras la activity está como launcher (no en un
   Thread separado)
3. El handoff a ESLauncher ocurre DESPUÉS del toggle

**El error de v1.4.14-18**: corrí el toggle en un `Thread{}` separado
para evitar ANR, pero el AccessibilityManagerService de OLAX no bindea
si el toggle no corre en el contexto del launcher.

## Lo que NO funciona

- **v1.4.14**: Theme.NoDisplay + finish() → process se mata antes del toggle
- **v1.4.15**: Translucent + moveToBack → activity se mata, handler.post no corre
- **v1.4.16**: Translucent + daemon Thread → toggle corre, pero service NO se bindea
- **v1.4.17**: Translucent + daemon Thread (no moveToBack) → toggle corre, service NO se bindea
- **v1.4.18**: igual a 17 con comentarios actualizados → igual

## Por qué el service no se bindea con Thread

El AccessibilityManagerService de OLAX detecta que MiroLauncherActivity
está en estado "resumed" (visible como launcher). Cuando el toggle
corre en un Thread separado, el sistema NO considera que el toggle
"pertenezca" al launcher. Resultado: flag 0→1 ok, lista OK, pero el
bind no ocurre.

**Solución (Mori)**: el toggle DEBE correr mientras MiroLauncherActivity
es el launcher. El handoff a ESLauncher ocurre DESPUÉS del toggle +
BIND_GRACE_MS.

## Cómo lo hacía v1.3.5 (el que funcionaba)

```
MiroLauncherActivity.onCreate
  → mainHandler.post { runToggleLoop(1) }
    → attemptToggle (3 retries, ~3s total)
      → VERIFIED
    → mainHandler.postDelayed({ launchRealLauncher + moveToBack }, 5000)
```

El toggle corre en el main thread (de la activity). Eso significa que
la activity está visible 3+5 = 8s. El user ve "pantalla oscura" durante
ese tiempo.

**El "pantalla oscura" es NECESARIO** para que el bind funcione. No es
un bug, es la señal visual de que el launcher-wrapper está vivo.

## Plan v1.4.19 (reversión a v1.3.5 pattern)

### Cambios

1. **Revertir MiroLauncherActivity al patrón v1.3.5**:
   - Toggle corre en `mainHandler.post` de la activity
   - BIND_GRACE_MS = 5000 antes de handoff
   - `moveTaskToBack(false)` (no finish()) después del handoff

2. **Mantener MiroApplication** solo para:
   - `ensureServiceInList()` antes del toggle (v1.4.15)
   - WRITE_SECURE_SETTINGS check (v1.4.12)

3. **Mantener manifest**:
   - `Theme.Translucent.NoTitleBar` (v1.4.13 revert)
   - Sin `noHistory` (rompía el flow)

### Comportamiento esperado

- ✓ MiroLauncherActivity visible 8s (pantalla oscura)
- ✓ Toggle corre en main thread
- ✓ Service se bindea
- ✓ Handoff a ESLauncher después de 5s
- ✓ Activity moveToBack, process sigue vivo

### Aceptación del trade-off

El "pantalla oscura" durante 8s es **necesario** y no es un bug. Es la
señal de que el wrapper está funcionando. El user lo aceptó en v1.3.5.

## Estado actual del repo

- Branch: `main`
- Último commit: `c1353c4` (v1.4.18 intento fallido)
- v1.4.18 instalado en tablet
- Service: NOT bound (como se esperaba)
- v1.3.5 sigue siendo la versión validada end-to-end

## Próximo paso

Implementar v1.4.19 reversión a patrón v1.3.5 + MiroApplication
helpers (ensureServiceInList + WRITE_SECURE_SETTINGS check).
