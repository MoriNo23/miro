# 2026-09-01 — v1.4.x: Quick Settings tile + adb_tablet mejoras

## TL;DR
Tres features nuevos en v1.4.0–1.4.4, todos verificados end-to-end en OLAX:

1. **Quick Settings tile** ("Auto-WirelessDebug") para activar/desactivar
   el auto-start wireless debug sin abrir QS panel.
2. **Socket commands** `get_auto_start` y `set_auto_start` para
   que el PC consulte/altere el flag sin QS.
3. **`adb_tablet` v1.1.0**: tres subcomandos nuevos
   (`--status`, `--setup`, `--auto-start off|on`).

## Commits de esta sesión

```
836cb0d fix(socket): bump grace period to 500ms — cold-boot was racing
7cbb545 fix(socket): release abstract name on onUnbind + 250ms grace period
d9d3ca3 fix(socket): close existing MiroSocketServer before opening new one
bd1b717 feat(socket): get_auto_start + set_auto_start commands
c3c6ec8 feat(tile): add Quick Settings tile to toggle auto-start wireless debug
```

## Feature 1: Quick Settings tile (v1.4.0)

### Archivos
- **Nuevo**: `app/src/main/java/com/miro/a11y/WirelessDebugTileService.kt`
  (4 KB) — extiende `TileService`, onClick flip el flag.
- **Cambiado**: `MiroAccessibilityService.kt` — `kAutoStartWirelessDebug`
  cambió de `private const val` a `@JvmField var` (default `true`).
- **Cambiado**: `AndroidManifest.xml` — service declaration con
  `BIND_QUICK_SETTINGS_TILE` permission.
- **Cambiado**: `strings.xml` — `tile_wireless_debug = "Auto-WirelessDebug"`.

### Cómo se usa (Mori)
1. Pull down notification shade en la tablet.
2. Long-press en QS o tap el ícono de editar.
3. En "Drag tiles here" buscar **"Auto-WirelessDebug"** y arrastrarlo
   a la zona activa.
4. Tap el tile → toggle on/off. Visible:
   - ACTIVE (encendido): icon `ic_media_play`
   - INACTIVE (apagado): icon `ic_media_pause`

### Limitación
- El QS de OLAX está simplificado. **SystemUI cargó el tile** (lo vimos en
  logcat: `QSTileHost: Creating tile: custom(com.miro.a11y/.WirelessDebugTileService)`)
  pero el layout puede no mostrarlo en la primera página. Si no aparece,
  arrastrarlo manualmente desde el editor de tiles.

## Feature 2: Socket commands (v1.4.1)

### Comandos
```
{"action":"get_auto_start"}
→ {"ok":true, "auto_start":true|false}

{"action":"set_auto_start", "enabled":true|false}
→ {"ok":true, "auto_start":true|false}
```

### Por qué se agregaron
- Complemento al QS tile para uso desde PC.
- Verificar estado del flag sin dumpsys.
- Setear el flag durante pruebas automatizadas.

### Implementación
- `MiroController.kt` (v1.4.1) — handlers agregados.
- Después de `set_auto_start`, llama `WirelessDebugTileService.refreshTile()`
  para que el tile UI se actualice la próxima vez que esté visible.

## Bug fix relacionado: socket "Address already in use" (v1.4.2, v1.4.3, v1.4.4)

### El problema
Cada vez que se hace toggle a11y (o post-reboot), el service se
recrea y trata de abrir el mismo abstract socket name `@miro`.
El FD anterior quedaba en el kernel → `Address already in use`.

### El fix (3 commits incrementales)
1. **v1.4.2**: Singleton `lastInstance` + `closeExisting()` en
   `onServiceConnected`.
2. **v1.4.3**: Override `onUnbind()` que llama `closeExisting()` y
   `socketServer?.stopServer()` con 200ms de delay.
3. **v1.4.4**: 250ms → 500ms (el cold-boot post-reboot necesita más
   tiempo para que el kernel libere el FD).

### Verificación
- Direct python socket test funciona tras toggle.
- Funciona tras reboot (con el delay correcto).
- `adb_tablet --status` muestra el flag auto-start.

## Feature 3: adb_tablet v1.1.0

### Subcomandos nuevos

#### `adb_tablet --status`
Muestra estado actual de la tablet sin reconectar:
```
═══ adb_tablet status ═══
Device:        10.42.1.63:5555
A11y enabled:  1
A11y services: ...:com.miro.a11y/com.miro.a11y.MiroAccessibilityService
Wireless:      1
HOME:          Launcher: ComponentInfo{com.miro.a11y/...MiroLauncherActivity}
Service:       ✓ BOUND (miro)
Auto-start:    ON
```

#### `adb_tablet --setup`
One-time setup que aplica:
1. `pm grant WRITE_SECURE_SETTINGS` a com.miro.a11y
2. `cmd package set-home-activity` → MiroLauncherActivity
3. Settings de a11y con MiroAccessibilityService + AppManager
4. Agrega el QS tile a `sysui_qs_tiles`
5. Ajustes WiFi anti-corte (battery)

Idempotente: si ya está aplicado, no duplica.

#### `adb_tablet --auto-start off|on`
Togglea el flag via socket (forward tcp:7777 localabstract:miro).
Si no se pasa arg, lee el estado actual y lo flipea.

### Mejoras internas
- `setup_miro_forward()` helper: limpia forwards viejos antes de crear.
- Output con `python3` para parsear JSON (más robusto que `grep`).
- Variable `MIRO_PKG` para no hardcodear `com.miro.a11y` en N lugares.

## Log completo de un toggle --auto-start

```
$ adb_tablet --auto-start off
7777
✓ Auto-start wireless debug: OFF

$ adb_tablet --auto-start on
7777
✓ Auto-start wireless debug: ON
```

## Log de un status completo

```
$ adb_tablet --status
═══ adb_tablet status ═══
Device:        10.42.1.63:5555
A11y enabled:  1
A11y services: com.miro.a11y/.MiroAccessibilityService:io.github.muntashirakon.AppManager/.accessibility.NoRootAccessibilityService
Wireless:      1
HOME:          Launcher: ComponentInfo{com.miro.a11y/com.miro.a11y.MiroLauncherActivity}
Service:       ✓ BOUND (miro)
Auto-start:    ON
```

## Limitaciones conocidas

1. **El QS tile no se ve automáticamente** — el layout OLAX esconde tiles custom. Hay que arrastrarlo manualmente desde el editor.
2. **`kAutoStartWirelessDebug` no persiste a través de reboot** — es un `var` en proceso, default `true`. Si se quiere persistir, mover a `SharedPreferences` (follow-up).
3. **El `closeExisting` con 500ms delay** es un hack para OLAX. En Android stock no es necesario.
4. **El dialog "Choose Home"** aparece en la primera reboot con `set-home-activity` y bloquea MiroLauncherActivity. Hay que elegir Miro manualmente.
5. **`adb_tablet --setup` loggea `syntax error: unexpected '('`** al agregar el tile, pero el `settings put` igual funciona (cosmetic issue en el subshell quoting).

## Comandos para repetir (Mori / próximo agente)

```bash
# 1. Instalar v1.4.4
adb install /tmp/miro-v1.4.4.apk

# 2. Setup one-time
adb_tablet --setup

# 3. Elegir Miro como HOME en el dialog que aparece
# (en la tablet, una vez por dispositivo)

# 4. Verificar
adb_tablet --status

# 5. Togglear auto-start desde PC
adb_tablet --auto-start off
adb_tablet --auto-start on
```
