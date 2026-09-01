# 2026-09-01 — Plan: buscar tile por content-desc en WirelessDebugAutomator

## Goal

Implementar la opción 1 del plan anterior: el `WirelessDebugAutomator`
busca el tile "Depuración inalámbrica" por `content-desc` en vez de
usar coordenadas fijas. Esto es **state-agnostic** — funciona
independientemente de si el QS se abre en modo parcial o completo.

## Problema

El código actual (`step2TapWirelessDebugTile` en MiroAccessibilityService.kt
línea 337) hace `controller.tap(?, ?)` con coords fijas. Funciona solo
si el QS está en el mismo estado que cuando se hardcodeó. Si el user
abre el QS completo o parcial, el tile está en Y=198 o Y=86
respectivamente. Las coords fijas fallan.

## Solución propuesta

Reemplazar `step2TapWirelessDebugTile` y `step2AfterSwipe` con búsqueda
real del nodo via `AccessibilityNodeInfo`:

```kotlin
private fun findQSTileByContentDesc(
    desc: String,
    rootNode: AccessibilityNodeInfo?
): AccessibilityNodeInfo? {
    if (rootNode == null) return null
    // BFS por todo el árbol
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(rootNode)
    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        if (node.contentDescription?.toString() == desc && node.isClickable) {
            return node
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { queue.add(it) }
        }
    }
    return null
}

private fun tapNodeBounds(node: AccessibilityNodeInfo): Boolean {
    val r = android.graphics.Rect()
    node.getBoundsInScreen(r)
    val cx = r.centerX().toFloat()
    val cy = r.centerY().toFloat()
    return controller.tap(cx, cy)
}
```

## Pasos

1. **Plan + handoff** (este archivo)
2. **Modificar `MiroAccessibilityService.kt`**:
   - Reemplazar `step2TapWirelessDebugTile()` con `findAndTapTile()`
   - Agregar helper `findQSTileByContentDesc()`
   - Mantener el fallback a swipe-expand si no encuentra
3. **Bumpear versionCode 33 → 34, versionName 1.4.19 → 1.4.20**
4. **Commit + push + CI**
5. **Test**:
   - Verificar que el flow corre en QS parcial (estado real post-reboot)
   - Verificar que el flow corre en QS completo (test con `expand-settings`)
6. **Handoff final** con resultado

## Riesgos

| Riesgo | Mitigación |
|---|---|
| El dialog `WifiDebuggingActivity` no es visible al a11y | Mantener coords fijas para el dialog (511, 312 / 721, 372) — esos son state-independent |
| El QS tiene el tile pero con `content-desc` diferente en otro idioma | Usar lista de descs (es/en) |
| El nodo raíz no se obtiene a tiempo | Aumentar delay antes de buscar (1s extra) |

## Criterio de éxito

- [ ] El tile se busca por content-desc
- [ ] Funciona en QS parcial (Y=86) y completo (Y=198)
- [ ] El dialog aparece y los taps por coords fijas (511, 312) y (721, 372) cierran el dialog
- [ ] Wireless debug = 1 después del flow
- [ ] Service bound
