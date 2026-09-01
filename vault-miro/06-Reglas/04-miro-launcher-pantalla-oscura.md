# 2026-09-01 — NOTA #1: MiroLauncherActivity pantalla oscura (issue launcher)

## Síntoma

Cuando MiroLauncherActivity corre como HOME al boot, en vez de hacer
"handoff instantáneo" a ESLauncher, **se queda visible** mostrando una
pantalla oscura. Esto bloquea el HOME default y el sistema muestra el
dialog "Selecciona aplicación de inicio" en cada reboot.

## Causa raíz

`AndroidManifest.xml` línea 37:
```xml
android:theme="@android:style/Theme.Translucent.NoTitleBar"
```

Y `MiroLauncherActivity.kt` línea 27: `class MiroLauncherActivity : Activity()`.

**No llama `setContentView`** porque es un wrapper "fantasma". Pero el
theme `Translucent` en OLAX se renderiza como una **pantalla negra**
porque la activity no tiene un surface real, y el compositor de OLAX
no maneja bien el caso "activity sin contenido, sin wallpaper".

**Adicional**: la activity corre `attemptToggle()` en el main thread
con `Handler.postDelayed(BIND_GRACE_MS = 5000L)`. Durante esos 5s, la
activity está visible (translúcida pero visible) y muestra la "pantalla
negra" antes de hacer `moveTaskToBack()`.

## Por qué pasa

EL problema NO es el `set-home-activity`. El problema es que la
activity **se queda visible durante el toggle** y durante el `BIND_GRACE_MS`.

El toggle dura ~5s (3 writes con sleeps + 2s A11Y_TOGGLE_DELAY_MS).
Después hay 5s de grace. Total: 10s de "pantalla oscura" antes del
handoff.

## Fix propuesta (NO implementar todavía)

1. **Cambiar el theme a `Theme.NoDisplay`** en vez de `Translucent`:
   ```xml
   android:theme="@android:style/Theme.NoDisplay"
   ```
   Esto hace que Android **no muestre nada** de la activity. Es el
   pattern estándar para "wrapper invisible" como `BroadcastReceiver`
   lanzados como activity.

2. **Agregar `finish()` antes del toggle** (NO al final), para que
   la activity se destruya inmediatamente:
   ```kotlin
   override fun onCreate(savedInstanceState: Bundle?) {
       super.onCreate(savedInstanceState)
       Log.i(TAG, "launcher activity started (post-boot or manual)")
       // Schedule work, then finish immediately so we don't show anything.
       mainHandler.post { startToggleSequence(1) }
       // Don't wait. Finish() schedules destruction AFTER the current
       // message is processed, so the postDelayed will keep running.
   }
   ```
   Pero `finish()` no se puede llamar si la activity no está visible.
   Con `Theme.NoDisplay` la activity está "visible" pero invisible.
   Y `finish()` antes de onResume causaría una excepción.

3. **Alternativa: hacer todo en onCreate sincrónicamente, sin
   Handler.postDelayed**. Pero los sleeps `Thread.sleep` no son válidos
   en el main thread (causan ANR).

4. **Alternativa correcta: usar `Application.onCreate` con un
   `registerActivityLifecycleCallbacks`** que detecte cuando
   MiroLauncherActivity arranca y haga el toggle desde un
   `IntentService` que sobrevive a la activity.

## Próximos pasos

- Probar `Theme.NoDisplay` (cambio mínimo) y ver si la activity se
  muestra como nada.
- Si `Theme.NoDisplay` no funciona, probar `Theme.Translucent` con
  `android:windowNoDisplay="true"`.
- Si ninguno funciona, **abandonar el patrón HOME wrapper** y
  buscar otro mecanismo (BootReceiver con LOCKED_BOOT_COMPLETED).

## Referencias

- v1.4.11: el service SÍ se bindea (gracias al toggle 0→1 simple).
- Handoff `2026-09-01-end-to-end-reboot-validated.md`: v1.3.5
  funcionaba end-to-end — verificar qué theme usaba y comparar.
