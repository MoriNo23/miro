# 2026-09-01 — NOTA #2: Dialog WifiDebuggingActivity coordenadas OLAX

## Estado del flow OLAX QS-tile (v1.4.11)

El flow **SÍ se ejecuta completo** después de que el service se
bindea post-reboot. Log observado en `vault-miro/03-Handoffs/2026-09-01-v1.4.11-bind-fixes-mas-info.md`:

```
09:20:51  auto-start: triggering OLAX QS-tile wireless debug flow
09:20:51  state=OPENING_DEV_OPTIONS
09:20:53  state=CLICKING_WIRELESS_DEBUG — tapping tile
09:20:53  tile tapped
09:20:55  state=CLICKING_WIRELESS_DEBUG — checking 'Permitir siempre'
09:20:55  tapped 'Permitir siempre' checkbox at (511, 312)
09:20:56  state=SENDING_TO_PC — tapping PERMITIR
09:20:56  wireless debug: DONE
```

**Pero `adb_wifi_enabled=0` después**. Y el `WifiDebuggingActivity`
sigue visible (vimos `topAct=com.android.systemui.wifi.WifiDebuggingActivity`
más de 1 minuto después). El tap en PERMITIR no acertó.

## Coords actuales hardcodeadas

```kotlin
// MiroAccessibilityService.kt (WirelessDebugAutomator)
const val CHECKBOX_X = 511; const val CHECKBOX_Y = 312
const val PERMITIR_X = 721;  const val PERMITIR_Y = 372
const val CANCELAR_X = 629; const val CANCELAR_Y = 372
```

Y el botón CANCELAR está a la izquierda del PERMITIR. Resolución de la
tablet: **1024x600** (landscape).

## Por qué falla el tap en PERMITIR

Hipótesis:
1. **El checkbox "Permitir siempre" requiere doble tap** o un tap
   largo. O requiere que el focus esté en el dialog antes del tap.
2. **El botón PERMITIR no está exactamente en (721, 372)**. La
   resolución del dialog puede tener padding distinto entre builds
   de OLAX.
3. **El dialog se cierra y reabre en bucle**. Si el primer tap
   (checkbox) falla, el dialog se queda; si el segundo (PERMITIR)
   falla, el dialog se queda; al rato el sistema lo cierra por
   timeout.
4. **El dialog se muestra, el service tappea fuera del dialog**
   (porque OLAX mueve la posición al mostrar el IME o el QS).

## Lo que el flow REAL hace (no lo que tengo hardcodeado)

El dialog `WifiDebuggingActivity` tiene 3 elementos:

| Elemento | Texto | Coordenadas esperadas | Estado actual |
|---|---|---|---|
| Checkbox | "Permitir siempre en esta red" | (511, 312) | ✓ tappeado, parece OK |
| Botón izquierdo | "CANCELAR" | (629, 372) | no se usa |
| Botón derecho | "PERMITIR" | (721, 372) | ✗ falla |

## Fix propuesta (NO implementar todavía)

1. **Verificar bounds reales** del dialog vía `dumpsys window` o
   `dumpsys activity top` ANTES de tappear. Las coords hardcodeadas
   son una lotería.

2. **Usar `dispatchGesture` con `GESTURE_TAP` en vez de
   `clickByCoordinate`**. OLAX responde mejor a gestos reales que
   a clicks.

3. **Si `dispatchGesture` no funciona, usar `AccessibilityNodeInfo`**
   (pero el tree está hidden en OLAX según handoff v1.3.5).

4. **Retry el tap PERMITIR 3 veces** con variación de ±5px. Y
   entre cada intento, esperar 500ms y verificar si el dialog
   desapareció (`uiautomator dump` o `dumpsys activity top`).

5. **Verificar `adb_wifi_enabled` después de cada tap**. Si es 1,
   parar. Si sigue 0, retry.

## Comando útil para ver bounds

```bash
# Mientras el dialog está visible:
adb shell dumpsys activity top | grep -A 30 "WifiDebuggingActivity"
adb shell dumpsys window | grep -A 5 "WifiDebugging"
```

## Referencias

- v1.3.5: el flow funcionaba con las mismas coordenadas. ¿Qué
  cambió? Verificar git log de los cambios entre v1.3.5 y v1.4.11
  en `MiroAccessibilityService.kt`.
- v1.4.11 service SÍ se bindea (gracias al toggle 0→1 simple).
- Handoff `2026-09-01-end-to-end-reboot-validated.md`: el flow
  completo tomó 16s desde `launcher activity started` hasta
  `DONE`. Ahora toma ~6s. Posible que el flow esté corriendo
  demasiado rápido.
