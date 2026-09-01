# 2026-09-01 — Plan: Fase 2 pantalla oscura MiroLauncherActivity

## Problema (Fase 2)

MiroLauncherActivity se queda visible **~11 segundos** durante el
toggle de a11y:

| Etapa | Duración | ¿Visible? |
|---|---|---|
| `onCreate` → primer write `flag=0` | ~50ms | sí |
| Sleep `VERIFY_DELAY_MS` (500ms) | 500ms | sí |
| Sleep `A11Y_TOGGLE_DELAY_MS` (2000ms) | 2000ms | sí |
| `flag=1` + sleep `VERIFY_DELAY_MS` | 500ms | sí |
| 3 attempts × (sleeps) si retry | hasta 6s | sí |
| `BIND_GRACE_MS` post-toggle | 5000ms | sí |
| **TOTAL** | **~11s** | **sí** |

El system muestra el dialog "Selecciona launcher" si no encuentra
HOME default. Esto pasa cuando:
- La activity se queda visible mientras ESLauncher está en background
- OLAX no maneja bien `Theme.Translucent.NoTitleBar` con una activity
  sin contenido (problema conocido del compositor OLAX/Allwinner)

## Opciones de fix (evaluadas)

### Opción A: cambiar theme a `Theme.NoDisplay`
```xml
android:theme="@android:style/Theme.NoDisplay"
```

**Pro**: la activity no se muestra NUNCA. Standard pattern para
"wrapper invisible" (BroadcastReceiver disfrazado de activity).

**Contra**:
- OLAX puede no soportarlo y crashear
- `finish()` debe llamarse desde `onResume` antes de que la activity
  sea visible
- Si OLAX no permite `NoDisplay` como HOME, el system no lo invocará

### Opción B: mover el toggle a `Application.onCreate`
```kotlin
class MiroApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(...) { ... }
        })
    }
}
```

**Pro**: el toggle corre sin activity visible. La activity se destruye
inmediatamente.

**Contra**:
- El toggle corre antes de que el sistema bindee el service
- Si MiroLauncherActivity se destruye antes del toggle, el handler
  postDelayed se mata con el activity

### Opción C: combinar A + B
- `Theme.NoDisplay` en manifest
- Toggle en `Application.onCreate` con WorkManager-like approach
- BIND_GRACE_MS = 0 (no se necesita porque la activity ya se destruyó)

**Pro**: más robusto. El toggle corre desde el Application, la activity
es invisible, no hay BIND_GRACE_MS.

**Contra**:
- Más código
- Necesita testing exhaustivo

### Opción D: aceptar el trade-off, hacer el handoff más rápido
- Reducir `BIND_GRACE_MS` de 5000ms a 2000ms
- Reducir `A11Y_TOGGLE_DELAY_MS` de 2000ms a 1000ms
- Total: ~6s de visibilidad en vez de 11s

**Pro**: cambio mínimo, no riesgo.

**Contra**: solo atenúa el problema. OLAX puede no bindear con grace < 2s.

## Decisión

Voy con **Opción C** (combinar Theme.NoDisplay + Application.onCreate)
porque es la más robusta y resuelve el problema de raíz. Si OLAX no
soporta NoDisplay, fallback a Opción D (reducir tiempos).

Pero antes: **Opción A sola** como primer intento (cambio mínimo, alto
impacto). Si A no funciona, paso a C.

## Plan de ejecución

### Paso 1: Opción A — cambiar theme a `Theme.NoDisplay` (v1.4.14)
- [ ] Modificar `AndroidManifest.xml`:
  ```xml
  android:theme="@android:style/Theme.NoDisplay"
  ```
- [ ] En `MiroLauncherActivity.onCreate`, **agregar finish() al final
  del toggle** (porque Theme.NoDisplay requiere que la activity llame
  finish() o se mantenga invisible). Verificar que el proceso sigue
  vivo después.
- [ ] Bumpear versionCode 27 → 28
- [ ] Commit + push + CI
- [ ] Test: instalar y verificar que la activity no se ve

### Paso 2: si A falla — Opción C (v1.4.15)
- [ ] Crear `MiroApplication` que extiende `Application`
- [ ] Mover el toggle de MiroLauncherActivity a MiroApplication.onCreate
- [ ] Usar `registerActivityLifecycleCallbacks` para detectar cuando
  MiroLauncherActivity se invoca y NO hacer nada (dejar que la
  Application haga el trabajo)
- [ ] Bumpear versionCode 28 → 29
- [ ] Commit + push + CI
- [ ] Test

### Paso 3: si C falla — Opción D (v1.4.16 fallback)
- [ ] Reducir `A11Y_TOGGLE_DELAY_MS` de 2000 a 1000
- [ ] Reducir `BIND_GRACE_MS` de 5000 a 2000
- [ ] Bumpear versionCode 29 → 30
- [ ] Commit + push + CI

## Criterio de éxito

- [ ] MiroLauncherActivity NO se queda visible más de 1s
- [ ] Wireless debug = 1 después del flow
- [ ] Service BOUND
- [ ] ESLauncher visible (handoff OK)
- [ ] NO dialog "Selecciona launcher"

## Riesgos

| Riesgo | Mitigación |
|---|---|
| OLAX no soporta `Theme.NoDisplay` | Fallback a Opción C |
| Toggle en Application crashea | Fallback a Opción D |
| Process se mata con la activity | Mantener BIND_GRACE_MS pero reducir |
| Service no se bindea con toggle más rápido | Re-introducir 3-attempt retry |
| HOME default se pierde | Verificar `cmd package set-home-activity` post-test |

## Entregables

- v1.4.14 (Opción A) o v1.4.15 (Opción C) o v1.4.16 (Opción D)
- Handoff en `vault-miro/03-Handoffs/2026-09-01-v1.4.X-launcher-oscuro.md`
- Plan reemplazado por el resultado real
