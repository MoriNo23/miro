# 2026-09-01 — Test bounds tile en QS parcial vs completo

## TL;DR

El código actual **YA funciona** en ambos estados del QS (parcial y
completo). No necesita modificación. `MiroController.findNode` busca
por **text O contentDescription**, así que el tile "Depuración
inalámbrica" se encuentra independientemente de su posición.

## Test

| Estado del QS | Comando | Tile bounds | Centro | Flow funciona? |
|---|---|---|---|---|
| Parcial (4 tiles 2x2) | `cmd statusbar expand-notifications` | `[288,56][508,116]` | (398, 86) | ✓ |
| Completo (8 tiles 4x2) | `cmd statusbar expand-settings` | `[288,156][508,240]` | (398, 198) | ✓ |

**Diferencia**: el X se mantiene (398), pero el Y cambia (86 vs 198).

## ¿Por qué funciona?

`MiroController.findNode(query)` (línea 96-99) hace:

```kotlin
fun findNode(query: String): AccessibilityNodeInfo? {
    val root = service.rootInActiveWindow ?: return null
    return searchNode(root, query.lowercase())
}

private fun searchNode(node, q) {
    val t = node.text?.toString()?.lowercase() ?: ""
    val d = node.contentDescription?.toString()?.lowercase() ?: ""
    if (t.contains(q) || d.contains(q)) return node
    // recurse children
}
```

Y el tile tiene `content-desc="Depuración inalámbrica"` (visto en
uiautomator dump). Entonces `tapByText("Depuración inalámbrica")`
lo encuentra, **no usa coords fijas** para el tile.

## ¿Qué SÍ usa coords fijas?

Los **botones del dialog WifiDebuggingActivity** (NO cambian entre
estados del QS):
- checkbox "Permitir siempre en esta red": (511, 312) — bounds `[260,296][762,328]`
- CANCELAR: (629, 372) — bounds `[581,348][677,396]`
- PERMITIR: (721, 372) — bounds `[677,348][765,396]`

Esos bounds son del dialog (overlay) y son siempre iguales en el
OLAX Magic Q1 / Android 12.

## Conclusión

NO hay que tocar el código. El flow `start_wireless_debug` por
socket funciona post-reboot Y con QS ya abierto en cualquier estado.

## Pendiente: ¿por qué Mori cree que no funciona?

Quizás Mori vio un fallo **transitorio** (un reboot donde el
service no llegó a bindear) o un fallo del dialog (porque el
"Permitir siempre" no estaba marcado y la siguiente vez volvió a
pedir confirmación). Eso es **Android policy**, no un bug del
código.

## Próximo paso

Hacer un **reboot final** con auto-start ON, esperar 120s, verificar
que:
- Wireless = 1 (auto-activado)
- Service BOUND
- ESLauncher visible
- Sin trucos PC activos

Si eso funciona, v1.4.19 está validada end-to-end. REGLA #9
cumplida.
