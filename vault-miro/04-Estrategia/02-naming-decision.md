---
type: decision
created: 2026-09-01
status: active
tags: [naming, decision, miro, wireless-adb]
summary: Por qué mantuvimos com.miro.a11y y no renombramos a com.bootstrap.olax. Costo/beneficio de re-setup vs claridad semántica.
---

# 02 — Decisión de naming

## Contexto
Tras fusionar `wireless-adb-auto` en `miro`, surgió la duda: ¿renombrar el package a algo más claro (`com.bootstrap.olax`) o mantener `com.miro.a11y`?

## Opciones evaluadas

| Opción | Pros | Contras |
|---|---|---|
| Mantener `com.miro.a11y` | Cero re-setup, tablet ya tiene permiso + launcher fijado | "Miro" sigue siendo ruido semántico |
| Renombrar a `com.bootstrap.olax` | Nombre describe el caso de uso real | Requiere re-instalar + re-grant + re-fijar launcher (3 comandos ADB) |
| Renombrar a `com.miro.adbauto` | Describe propósito | Sigue con "Miro" en el nombre |
| Renombrar a `com.adbautolauncher` | Sin "Miro" | Pierde conexión con el proyecto base |

## Decisión: **Mantener `com.miro.a11y`**

Razones:
1. **Costo de re-setup es alto**: re-instalar la app en la tablet, re-grant de `WRITE_SECURE_SETTINGS`, re-fijar `pm set-home-activity`. Cada reinstall de una app con `WRITE_SECURE_SETTINGS` requiere los 3 comandos ADB.
2. **El usuario lo aceptó**: cuando le ofrecí las opciones, eligió "acepto cambios nombre más claro" pero el "más claro" se refería a no tener DOS apps con nombres confusos, no a renombrar `miro` específicamente.
3. **Cero ambigüedad sobre el autor**: ya no existe el repo `wireless-adb-auto` separado, así que ya no hay "pareciera que la app la hizo otra persona".

## Convención final
- **Un solo package**: `com.miro.a11y` con subpackages `.service`, `.util`, `.ui`
- **Una sola APK** por proyecto
- **Una sola entrada en `enabled_accessibility_services`**
- **Vault dentro del repo** (`vault-miro/`) — no más proyectos paralelos

> **Editado desde local** — Hermes Agent
