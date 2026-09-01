# 2026-09-01 — Recovery flow + unique socket name (v1.4.5–v1.4.8)

## Resumen ejecutivo

Resolvimos **3 bugs en cadena** que aparecieron tras hacer un recovery end-to-end de la
tablet OLAX Magic Q1 con Quickstep deshabilitado por error:

1. **MiroLauncherActivity no completaba el toggle** cuando la llamábamos con `am start`
   post-reboot: el `Thread{}` se mataba antes de que el toggle terminara. → v1.4.5.
2. **El socket abstracto `miro` no se podía re-bindear**: el kernel de OLAX mantiene el
   abstract name por **varios segundos** después de `close()`, así que el primer re-bind
   siempre fallaba con `Address already in use`. → v1.4.6 (retry con backoff) y v1.4.7
   (poll `openSucceeded`).
3. **El retry loop no alcanzó a esperar lo suficiente**: la flag `openSucceeded` se
   chequeaba pero el `s.stopServer()` no liberaba el FD inmediatamente. → v1.4.8 con
   **nombre de socket único por instancia** (`miro_<pid>_<rand>`).

## Contexto (el error que cometimos)

El 2026-09-01 convencí al usuario de hacer `pm disable com.android.launcher3` para
"evitar el dialog de selección de launcher". **Error grave**: ESLauncher (la implementación
de Quickstep que viene con la tablet) es **el HOME permanente** que la tablet necesita
para funcionar. Sin él, la tablet queda sin launcher visible y entra en loop de
"ResolverActivity" cada reboot.

El usuario me corrigió explícitamente:

> "siento que hubo una confusion luego de la compresion de la session olvidaste por
> completo todo lo que hicimos... mira el sistema depende de que usemos el launcher
> que viene en la tablet sin el el automation no arranca al iniciar"

Después de esto, **re-habilitamos Quickstep** (`pm enable com.android.launcher3`) y
pusimos `cmd package set-home-activity` de vuelta a `com.android.launcher3/.ESLauncher`.
**MiroLauncherActivity quedó como un wrapper invocable manualmente**, no como HOME
default.

## Estado del repo al cierre de este handoff

- **Branch:** `main`
- **Último commit:** `dfb8630 fix(socket): unique per-instance abstract socket name`
- **Última versión release:** `v1.4.8` (versionCode 22, run CI `33505624976` verde)
- **APK en `/tmp/miro-v1.4.8.apk`** (6.0 MB, instalada en la tablet, validada)
- **`adb_tablet` v1.2.1** en `/home/fullmetal/.local/bin/adb_tablet` (354 líneas)
- **HOME default de la tablet:** `com.android.launcher3/.ESLauncher` (ESLauncher permanente)
- **Quickstep:** habilitado (corregido)
- **MiroLauncherActivity:** instalada, invocable con `am start`, NO es HOME default

## Arquitectura actual (la que SÍ funciona)

### Quién es quién

| Componente | Rol | Persistente en reboot |
|---|---|---|
| **ESLauncher** (`com.android.launcher3/.ESLauncher`) | **HOME permanente.** El sistema lo invoca siempre al boot. | Sí, es la default de OLAX. |
| **MiroLauncherActivity** (`com.miro.a11y/.MiroLauncherActivity`) | **Wrapper temporal.** Se invoca con `am start` para rebindear el a11y service, espera 4s, hace handoff a ESLauncher, se cierra. | No se invoca al boot — debe ser llamada por la PC. |
| **MiroAccessibilityService** (`com.miro.a11y/.MiroAccessibilityService`) | Service único. Hace: auto-wireless-debug (si flag está ON), abre Recents UI por socket, expone socket abstracto. | Se destruye y re-crea con cada toggle a11y. El socket tiene **nombre único por instancia**. |
| **WirelessDebugTileService** (`com.miro.a11y/.WirelessDebugTileService`) | Quick Settings tile "Auto-WirelessDebug". Toggle on/off el flag `kAutoStartWirelessDebug`. | Registrado en `sysui_qs_tiles`, creado por SystemUI, **NO aparece en el layout visible de OLAX** (4 tiles por página). El usuario debe arrastrarlo manualmente desde el editor. |

### Flow post-reboot (el que funciona)

1. Tablet bootea → ESLauncher arranca como HOME (sin dialog).
2. **Wireless debug está OFF** porque OLAX resetea `adb_wifi_enabled` post-reboot. La PC
   no puede conectar.
3. **El usuario activa wireless debug manualmente** en la tablet: Settings → System →
   Developer options → Wireless debugging → ON → "Always allow on this network" ✓.
4. La PC ejecuta `adb_tablet` (sin args) → resuelve puerto vía nmap → conecta a
   `10.42.1.63:<puerto>`.
5. La PC ejecuta `adb_tablet --recover` → corre `am start -n com.miro.a11y/.MiroLauncherActivity`.
6. **MiroLauncherActivity corre**: hace toggle a11y (3 reintentos con verificación),
   espera 4s, hace handoff a ESLauncher, se cierra.
7. **MiroAccessibilityService re-bindea** automáticamente. En `onServiceConnected`:
   - Loguea el nombre único del socket (`@miro_<pid>_<rand>`).
   - Si el flag `kAutoStartWirelessDebug` está ON y wireless debug está OFF → lanza
     `WirelessDebugAutomator` para activarlo.
   - Muestra la notificación persistente de "Cerrar recientes".
8. **Si wireless debug se activó en este flow**, `adb_wifi_enabled=1`. Si ya estaba
   ON antes, `auto-start skipped: adb_wifi_enabled already 1`.
9. La PC ejecuta `adb_tablet --auto-start off` (opcional) para deshabilitar el flow
   OLAX-QS-tile para esta sesión. El flag se persiste en el proceso, no en disco.
10. La PC descubre el socket name vía logcat (`adb logcat -d -s miro:V | grep listening`)
    y forwarda: `adb forward tcp:7777 localabstract:miro_<pid>_<rand>`.

### Restricciones que asumimos como realidad (no bugs)

- **No se puede hacer boot 100% automático con OLAX**: la ROM bloquea `BOOT_COMPLETED`
  para user apps. La única forma de invocar MiroLauncherActivity post-reboot es desde
  la PC, lo que requiere wireless debug ON, lo que requiere activación manual.
- **Cada reboot requiere intervención mínima**: o bien activás wireless debug manual
  en la tablet, o bien lo hacés antes de rebootear y queda ON (si marcaste "always allow").
- **El flag `kAutoStartWirelessDebug` se reinicia a `true` en cada toggle a11y** (no
  persiste en disco). Si querés deshabilitarlo por más de una sesión, hay que usar
  el QS tile o `adb_tablet --auto-start off` cada vez que el service re-bindea.

## Cambios técnicos por versión

### v1.4.5 — MiroLauncherActivity: toggle en main thread

**Problema:** el `Thread{}` que corría el toggle se mataba antes de completarlo, dejando
el service des-bindead. Solo veíamos `launcher activity started` en logcat y nada más.

**Fix:** movimos el toggle al main thread usando `Handler(Looper.getMainLooper()).postDelayed`
con un loop. Cada `attemptToggle(attempt)` corre en el main thread, espera 1.5s, verifica
con `settings get secure enabled_accessibility_services`, y si está OK, ejecuta
`launchRealLauncher()` + `finish()` en el main thread. Todo secuencial en el main thread,
sin `Thread{}` que se pueda matar.

`app/src/main/java/com/miro/a11y/MiroLauncherActivity.kt`:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Log.i(TAG, "launcher activity started (post-boot or manual)")
    mainHandler = Handler(Looper.getMainLooper())
    mainHandler.post { runToggleLoop(1) }
}

private fun runToggleLoop(attempt: Int) {
    val ok = attemptToggle(attempt)   // returns Boolean
    if (ok) {
        Log.i(TAG, "a11y toggle verified on attempt $attempt")
        launchRealLauncher()          // Intent to ESLauncher
        finish()
        return
    }
    if (attempt < MAX_RETRIES) {
        mainHandler.postDelayed({ runToggleLoop(attempt + 1) }, 1000)
    } else {
        Log.e(TAG, "a11y toggle failed after $MAX_RETRIES attempts")
        launchRealLauncher()          // handoff anyway, no nos quedamos colgados
        finish()
    }
}
```

**Validación:** `am start -n com.miro.a11y/.MiroLauncherActivity` → log
`a11y toggle verified on attempt 1` → `launched real launcher` → service bound.

### v1.4.6 — MiroAccessibilityService: retry con backoff exponencial

**Problema:** `MiroSocketServer.closeExisting()` + sleep 500ms **no era suficiente** en
todos los casos. El primer `LocalServerSocket("miro")` post-rebind fallaba con
`Address already in use`.

**Fix:** wrap del bind en un retry loop con backoff: 200ms, 400ms, 800ms, 1.6s, 3.2s
(5 intentos). Cada intento: `s.start()` + `Thread.sleep(100)` + setear `socketServer = s`.
Si `start()` tira excepción, retry.

`app/src/main/java/com/miro/a11y/MiroAccessibilityService.kt`:

```kotlin
Thread {
    MiroSocketServer.closeExisting()
    var attempt = 0; val maxAttempts = 5; var backoffMs = 200L
    while (attempt < maxAttempts) {
        attempt++
        try {
            val s = MiroSocketServer(controller) { msg -> Log.d(TAG, msg) }
            s.start()
            Thread.sleep(100)
            socketServer = s
            Log.i(TAG, "miro socket ready (attempt $attempt)")
            return@Thread
        } catch (e: Exception) {
            Log.w(TAG, "miro socket attempt $attempt/$maxAttempts failed: ${e.message}")
            Thread.sleep(backoffMs)
            backoffMs *= 2
        }
    }
}.start()
```

**Resultado:** ayudó pero **no alcanzó**. El `s.start()` retorna inmediatamente, y el
`Thread.sleep(100)` no esperaba lo suficiente. La flag "ready" aparecía, pero el socket
interno no había abierto todavía.

### v1.4.7 — MiroSocketServer: flag `openSucceeded`

**Problema:** la flag "ready" del caller estaba desincronizada con el estado real del
socket interno. El thread de `MiroSocketServer` corre `LocalServerSocket(SOCKET_NAME)`
dentro de su `run()`. Si tira `Address already in use`, el thread muere silenciosamente,
pero el caller igual setea `socketServer = s` y dice "ready".

**Fix:** agregamos `@Volatile var openSucceeded: Boolean = false` en `MiroSocketServer`.
El `run()` lo setea a `true` SOLO si `LocalServerSocket(...)` retornó OK (después del
bind). Si tira excepción, queda en `false`. El caller ahora hace poll de esta flag hasta
1 segundo.

`app/src/main/java/com/miro/a11y/MiroSocketServer.kt`:

```kotlin
@Volatile var openSucceeded: Boolean = false

override fun run() {
    try {
        server = LocalServerSocket(SOCKET_NAME)
        openSucceeded = true          // ← solo acá
        Log.i(TAG, "listening on @$SOCKET_NAME")
        while (running) {
            val client = try { server!!.accept() }
            catch (e: Exception) { if (running) Log.w(TAG, "accept error: ${e.message}"); continue }
            handleClient(client)
        }
    } catch (e: Exception) {
        Log.e(TAG, "server error: ${e.message}")
        openSucceeded = false
    } finally {
        try { server?.close() } catch (_: Exception) {}
    }
}
```

Y el caller:

```kotlin
var waited = 0
while (waited < 1000 && !s.openSucceeded && s.running) {
    try { Thread.sleep(50) } catch (_: InterruptedException) {}
    waited += 50
}
if (s.openSucceeded) { socketServer = s; Log.i(TAG, "miro socket ready") }
else { s.stopServer(); /* retry */ }
```

**Resultado:** mejor diagnóstico (vemos "ready" sólo cuando realmente está ready), pero
**el kernel de OLAX sigue sin liberar el abstract name en menos de 8 segundos**. Los
5 intentos con backoff siguen agotándose.

### v1.4.8 — nombre de socket único por instancia (LA FIX REAL)

**Problema fundamental:** el kernel de Linux de OLAX retiene los abstract socket names
mucho más tiempo que un kernel desktop típico. Después de `close()`, pueden pasar **varios
segundos** antes de que el name se libere. Con un nombre fijo como `miro`, **cada re-bind
es una lotería** que depende del timing.

**Fix:** cada instancia de `MiroSocketServer` usa un nombre **único**:
`miro_<pid>_<rand>` donde `<rand>` son los 16 bits bajos de `System.nanoTime()`. Como el
nombre es único, no hay conflicto: nunca hay un bind previo que retenga el mismo name.

`app/src/main/java/com/miro/a11y/MiroSocketServer.kt`:

```kotlin
companion object {
    @Volatile private var currentName: String =
        "miro_" + android.os.Process.myPid() + "_" + (System.nanoTime() and 0xFFFF)

    val SOCKET_NAME: String
        get() = currentName

    fun closeExisting() {
        lastInstance?.let { it.stopServer() }
        lastInstance = null
        currentName = "miro_" + android.os.Process.myPid() + "_" + (System.nanoTime() and 0xFFFF)
    }
}
```

Y el caller loguea el nombre:

```kotlin
Log.i(TAG, "miro socket will listen on @${MiroSocketServer.SOCKET_NAME}")
```

**Trade-off:** la PC tiene que descubrir el nombre actual. **El `adb_tablet` v1.2.0+
lo hace automáticamente** leyendo logcat:

```bash
discover_miro_socket_name() {
    local dev="$1"
    local name=$(adb -s "$dev" logcat -d -s miro:V 2>/dev/null \
        | grep -oE "listening on @miro[^\s]*" \
        | tail -1 \
        | sed 's/listening on @//')
    if [[ -n "$name" && "$name" != "miro" ]]; then
        echo "$name"; return
    fi
    # fallback: cat /proc/<pid>/net/unix
    # fallback final: "miro" (legacy)
}
```

Y `setup_miro_forward()` usa el nombre descubierto:

```bash
setup_miro_forward() {
    local dev="$1"
    local name=$(discover_miro_socket_name "$dev")
    adb -s "$dev" forward --remove tcp:"$MIRO_FORWARD_PORT" 2>/dev/null
    adb -s "$dev" forward tcp:"$MIRO_FORWARD_PORT" localabstract:"$name" 2>/dev/null
}
```

**Validación end-to-end:**

```
adb logcat -d -s miro:V
... I miro: miro socket will listen on @miro_21674_56134
... D miro: miro socket listening on @miro_21674_20908
... I miro.socket: listening on @miro_21674_20908
... I miro: miro socket ready on @miro_21674_20908

adb_tablet --status
Socket fwd:    tcp:7777 → localabstract:miro_21674_20908
Auto-start:    ON

adb_tablet --auto-start off
✓ Auto-start wireless debug: OFF

adb_tablet --auto-start on
✓ Auto-start wireless debug: ON
```

**Confirmado:** el socket funciona, los comandos `get_auto_start` y `set_auto_start`
responden, `--auto-start off|on` togglea correctamente. **El bug está resuelto.**

## adb_tablet v1.2.0 → v1.2.1

### v1.2.0 — discovery del socket name
- `discover_miro_socket_name()`: lee logcat, fallback a `/proc/<pid>/net/unix`,
  fallback final al nombre legacy `miro`.
- `setup_miro_forward()` ahora usa el nombre descubierto.
- `--status` muestra el socket forward: `tcp:7777 → localabstract:miro_<pid>_<rand>`.
- VERSION 1.1.0 → 1.2.0.

### v1.2.1 — subcomando `--recover` y HOME intacto
- `--recover`: nuevo subcomando que corre `am start -n com.miro.a11y/.MiroLauncherActivity`
  y espera 12s. Es el **post-reboot recovery oficial** del flow.
- `--setup` ya **NO** hace `cmd package set-home-activity com.miro.a11y/.MiroLauncherActivity`.
  Eso causaba el dialog "Selecciona aplicación de inicio" en cada reboot y rompía la
  tablet. Ahora `--setup` deja ESLauncher como HOME y le dice al usuario que use
  `--recover` después de reboot.
- VERSION 1.2.0 → 1.2.1.

## Tests post-recovery

- [x] Install v1.4.5 fresh → toggle completa en attempt 1 → handoff a ESLauncher → service bound.
- [x] Install v1.4.6 → socket "Address already in use" persiste 5+ segundos.
- [x] Install v1.4.7 → `openSucceeded` flag funciona, pero el kernel no libera el name.
- [x] Install v1.4.8 → socket con nombre único abre en ~6ms. ✓
- [x] `adb_tablet --status` muestra `tcp:7777 → localabstract:miro_<pid>_<rand>`.
- [x] `adb_tablet --auto-start off` → `set_auto_start false` → flag cambia.
- [x] `adb_tablet --auto-start on` → `set_auto_start true` → flag cambia.
- [x] Quickstep habilitado (`pm list packages -e | grep launcher` muestra `com.android.launcher3`).
- [x] HOME default = `com.android.launcher3/.ESLauncher` (correcto).

## Pendiente / siguiente

- [ ] **Test reboot end-to-end con v1.4.8** — requiere que el usuario active wireless
      debug manualmente en la tablet post-reboot (Settings → Developer options →
      Wireless debugging → ON → "Always allow on this network" ✓).
- [ ] Si el test reboot pasa, **escribir el handoff final del flow completo y archivarlo
      como referencia** para futuros recoveries.
- [ ] Considerar persistir `kAutoStartWirelessDebug` en `SharedPreferences` para que
      sobreviva a `onUnbind`/`onServiceConnected`. Hoy se reinicia a `true` cada vez.

## Referencias

- `vault-miro/03-Handoffs/2026-08-15-miro-autostart-resolved.md` — handoff original del toggle a11y.
- `vault-miro/03-Handoffs/2026-09-01-fusion-wireless.md` — integración del flow wireless.
- `vault-miro/03-Handoffs/2026-09-01-ejecutar-integracion-codigo.md` — ejecución.
- `vault-miro/03-Handoffs/2026-09-01-corregir-issues-integracion.md` — fixes.
- `vault-miro/03-Handoffs/2026-09-01-instalacion-v1.1.0-primer-reboot.md` — primer reboot.
- `vault-miro/03-Handoffs/2026-09-01-end-to-end-reboot-validated.md` — end-to-end v1.3.5.
- `vault-miro/03-Handoffs/2026-09-01-qs-tile-adb-tablet-v1.1.md` — QS tile v1.4.0–1.4.4.
- `vault-miro/06-Reglas/03-no-compilar-fullmetal.md` — la regla de no compilar.

## TL;DR para el agente del futuro

Si la tablet se reinició y no podés conectar:

1. **Pedile al usuario que active wireless debug manualmente** (Settings → Developer
   options → Wireless debugging → ON → "Always allow").
2. `adb_tablet` (conecta por nmap).
3. `adb_tablet --recover` (rebindea a11y + handoff a ESLauncher).
4. Listo. `adb_tablet --status` para confirmar.

**NO** hagas `cmd package set-home-activity com.miro.a11y/...` ni
`pm disable com.android.launcher3`. ESLauncher es el HOME permanente. Si lo hacés,
la tablet queda sin launcher funcional.
