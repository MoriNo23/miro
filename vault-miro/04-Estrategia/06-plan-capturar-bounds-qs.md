# 2026-09-01 — Plan: capturar bounds del tile QS en 2 estados

## Goal

El user quiere que capturemos los bounds del tile "Depuración
inalámbrica" en **2 estados del Quick Settings**:

1. **QS parcialmente abierto** (como el `cmd statusbar expand-notifications`
   que hicimos — el QS se ve en la parte de arriba, en un "row" de
   4 tiles 2x2)
2. **QS completamente abierto** (full expanded — 8+ tiles en grilla 4x2)

En cada estado, hay que:
- Capturar los bounds exactos del tile
- Hacer tap en el tile
- Capturar los bounds del dialog WifiDebuggingActivity que aparece
- Verificar que el checkbox + PERMITIR/CANCELAR aparecen con sus
  bounds reales

## Por qué importa

El `WirelessDebugAutomator` actual **NO usa bounds** — usa coordenadas
fijas (511, 312), (721, 372) que el user (Mori) ya verificó
visualmente que coinciden con el dialog cuando aparece. Pero:

- **El tile cambia de ubicación** según el estado del QS:
  - Parcial: 2x2 grid, el tile está en la primera fila izquierda
  - Completo: 4x2 grid, el tile puede estar en otra posición
  - El orden de los tiles cambia según qué tiles tenga el user

- **El dialog WifiDebuggingActivity** también puede tener bounds
  ligeramente diferentes en distintas versiones de Android
  (12, 12L, 13, 14)

## Plan de ejecución

### Paso 1: setup limpio
- [ ] Reinstalar v1.4.19 con --setup
- [ ] Verificar wireless=0, a11y=1, service BOUND

### Paso 2: capturar QS parcialmente abierto
- [ ] `cmd statusbar expand-notifications` (parcial)
- [ ] `uiautomator dump` → buscar bounds de "Depuración inalámbrica"
- [ ] Tap por bounds reales (no coords fijas)
- [ ] Cuando aparece WifiDebuggingActivity, dump bounds de
  checkbox + PERMITIR + CANCELAR

### Paso 3: capturar QS completamente abierto
- [ ] Cerrar el dialog (CANCELAR o back)
- [ ] `cmd statusbar expand-settings` (completo)
- [ ] `uiautomator dump` → buscar bounds de "Depuración inalámbrica"
- [ ] Tap por bounds reales
- [ ] Cuando aparece WifiDebuggingActivity, dump bounds de
  checkbox + PERMITIR + CANCELAR

### Paso 4: comparar bounds entre los 2 estados
- [ ] ¿El dialog WifiDebuggingActivity aparece en el mismo lugar?
- [ ] ¿Los bounds del checkbox y PERMITIR cambian?
- [ ] Documentar las diferencias (o confirmar que son iguales)

### Paso 5: reportar
- [ ] Mostrar al user los bounds de ambos estados
- [ ] Preguntar: ¿mejor buscar el tile por content-desc en vez de
  coordenadas fijas?
