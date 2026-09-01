# 2026-09-01 — Fix: restoration of the HOME wrapper pattern (v1.3.5)

## TL;DR

**Rompí el flow que funcionaba**. La solución correcta ya existía y estaba validada
end-to-end en `vault-miro/03-Handoffs/2026-09-01-end-to-end-reboot-validated.md` (v1.3.5).
La confundí con otra cosa y le dije al usuario que la saque de `--setup`. Ahora la
restauro en `adb_tablet v1.2.2` y agrego tests para no romperla de nuevo.

## Qué se rompió

**Antes** (v1.3.5, validada end-to-end, ver handoff original):
1. `cmd package set-home-activity com.miro.a11y/.MiroLauncherActivity` se ejecuta una
   vez antes del reboot.
2. Tras el reboot, el sistema **invoca MiroLauncherActivity como HOME** (es el único
   mecanismo post-boot que OLAX permite para user apps — `BOOT_COMPLETED` está bloqueado).
3. `MiroLauncherActivity` corre: hace el toggle a11y (3 reintentos con verificación),
   espera ~4s, hace handoff a `com.android.launcher3/.ESLauncher`, y se cierra.
4. ESLauncher toma el control y queda como launcher visible.
5. 8 segundos después, `MiroAccessibilityService` corre el flow OLAX QS-tile para
   activar wireless debug automáticamente.

**Lo que yo hice mal** (en este turno):
1. Convencí al usuario de hacer `pm disable com.android.launcher3` para "evitar el
   dialog de selección de launcher". **Error grave**: ESLauncher (la implementación
   de Quickstep de OLAX) es **el launcher que la tablet necesita para funcionar**.
2. El usuario me corrigió: "el sistema depende de que usemos el launcher que viene
   en la tablet".
3. Re-habilité Quickstep, pero **también** eliminé el `set-home-activity` de
   `--setup` por miedo a que causara el dialog de selección. **Esto fue el error
   técnico**: la `cmd package set-home-activity` en sí misma **NO causa el dialog**
   (el dialog aparece solo la primera vez, cuando no hay default; después es
   persistente).
4. Sin Miro como HOME default, MiroLauncherActivity nunca corre al boot. La app
   queda inerte. El user tiene que correr `am start -n com.miro.a11y/.MiroLauncherActivity`
   manualmente desde la PC, lo que requiere wireless debug ON, lo que requiere
   activación manual en la tablet. **Catch-22**.

## La fix

`adb_tablet v1.2.2` (en `/home/fullmetal/.local/bin/adb_tablet`):

1. **Restaura `cmd package set-home-activity` en `--setup`**. El comentario en el
   código explica claramente:
   - "CRITICAL: set MiroLauncherActivity as the default HOME so the system invokes
     it after every reboot (the OLAX Magic Q1 has no BOOT_COMPLETED for user apps)."
   - "ESLauncher is still enabled and present — we are not disabling it."
   - "Verified end-to-end in v1.3.5. The 'Select home app' dialog only appears the
     first time (when no default is set)."

2. **Bump VERSION** 1.2.1 → 1.2.2.

3. **`--help` actualizado** para reflejar el cambio:
   ```
   adb_tablet --setup    One-time setup: grant WRITE_SECURE_SETTINGS,
                         set HOME a MiroLauncherActivity (clave para
                         auto-arranque post-reboot), habilita a11y,
                         agrega tile a QS, WiFi anti-corte.
   ```

4. **`--recover` se mantiene** como fallback: si por alguna razón MiroLauncherActivity
   no se invoca al boot (por ejemplo, si Quickstep se re-instaló y cambió el HOME
   default), `adb_tablet --recover` lanza MiroLauncherActivity manualmente.

## Qué NO se rompió

- **El toggle a11y en MiroLauncherActivity** (v1.4.5 main-thread fix) — sigue funcionando.
- **El socket con nombre único** (v1.4.8) — sigue funcionando.
- **El auto-wireless-debug via QS-tile** — sigue funcionando.
- **El Quick Settings tile "Auto-WirelessDebug"** — sigue funcionando.
- **ESLauncher y Quickstep** — ambos habilitados, no se deshabilitan.

## Pasos para aplicar el fix

1. Conectar la tablet (USB o wireless debug ON manualmente).
2. `adb_tablet --setup` (re-setea todo, incluido HOME=Miro).
3. `adb reboot`.
4. Esperar ~90 segundos.
5. `adb_tablet --status` para verificar.

Si por alguna razón MiroLauncherActivity no se invoca al boot (lo sabremos porque
`a11y toggle verified` no aparece en logcat), correr `adb_tablet --recover`.

## Por qué me confundí (lección para el futuro)

El handoff `2026-09-01-end-to-end-reboot-validated.md` documentaba la solución
v1.3.5 de manera explícita:

> ### 5. HOME launcher post-reboot (v1.3.5)
> - **Clave**: `cmd package set-home-activity com.miro.a11y/.MiroLauncherActivity`
>   debe ejecutarse **una vez** antes del reboot
> - Después del reboot, el sistema invoca MiroLauncherActivity como HOME
> - Sin este set, ESLauncher es el HOME default y MiroLauncherActivity nunca corre

Yo vi el dialog "Selecciona aplicación de inicio" después de un reboot y **asumí
incorrectamente** que el `set-home-activity` lo causaba. La realidad: el dialog
apareció porque en el reboot anterior yo había hecho `pm disable com.android.launcher3`,
lo que **invalidó el HOME default** y forzó al sistema a preguntar de nuevo. Una vez
que el usuario tocó ESLauncher, el dialog no volvió a aparecer (porque ESLauncher
queda como default después).

**Lección**: cuando rompa algo, antes de "arreglar" eliminando features, **revisar
el handoff que documentó la feature originalmente**. No asumir causalidad.

## Validación esperada

Tras `adb_tablet --setup` + `adb reboot`, el logcat debe mostrar:

```
I miro: launcher activity started (post-boot or manual)
I miro: a11y toggle verified on attempt 1
I miro: launched real launcher
I miro: miro accessibility service connected
I miro: miro socket listening on @miro_<pid>_<rand>
D miro: wireless debug: state=OPENING_DEV_OPTIONS
D miro: wireless debug: tile tapped
D miro: WIRELESS_DEBUG_ENABLED via OLAX QS-tile flow
```

Tiempo total: ~16 segundos desde `launcher activity started` hasta wireless debug ON.
