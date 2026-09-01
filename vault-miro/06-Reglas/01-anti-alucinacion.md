---
type: regles
created: 2026-09-01
status: active
tags: [anti-alucinacion, reglas, agente, vault]
summary: Reglas para que los agentes NO alucinen ni mientan sobre el estado del trabajo. Se aplican en vault-miro/ y AGENTS.md del repo.
---

# 01 — Reglas anti-alucinación

Estas reglas nacen de una auditoría (2026-09-01) donde un agente marcó handoffs como "completed" cuando en realidad solo había creado esqueletos y stubs.

## 🚫 Lo que el agente NUNCA debe hacer

1. **Marcar como "completed" sin verificar.** Si el archivo tiene TODOs o el código no compila, el handoff NO está completo. Estado correcto: `in-progress` o `blocked`.
2. **Decir "integré X" sin ejecutar el código.** Si un test no corre, una función solo tiene la firma, o un service no bindea — no está integrado, está esqueletado.
3. **Asumir que el CI verde = trabajo completo.** CI verde significa que COMPILA. No significa que FUNCIONA en el device, ni que el toggle persiste, ni que el state machine dispara.
4. **Hardcodear nombres de servicios de otras apps** en código que toca `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. Si la lista tiene `bitpit.launcher` o `AppManager`, es porque ya estaban antes; no asumir que siempre estarán.
5. **Marcar commits con prefijos falsos.** `feat:` requiere funcionalidad. `fix:` requiere bug confirmado. `docs:` requiere docs reales. Si pusiste un esqueleto con TODOs, es `feat: scaffold X` o `wip: X`.
6. **Cambiar la arquitectura prometida sin avisar.** Si el handoff dice "1 service", hacer 2 services es desviarse. Hay que proponer primero.
7. **Perder robustez en reescrituras.** Si el handoff original decía "3 reintentos + verificación", entregar "1 intento sin verificación" es una regresión.

## ✅ Lo que el agente SIEMPRE debe hacer

1. **Verificar antes de marcar como completed.** Leer el código generado, contar TODOs, ejecutar tests si aplica.
2. **Diferenciar esqueleto de integración.** `feat: scaffold X` ≠ `feat: integrate X`. Si es solo el esqueleto, decirlo.
3. **Reportar diferencias con el handoff.** Si el agente tomó una decisión distinta a la propuesta, documentar el porqué en el commit message y en el vault.
4. **Actualizar el vault con el estado real.** Si lo que hizo difiere de lo que proponía el handoff, agregar una nota en `04-Estrategia/decisiones-durante-integracion.md`.
5. **Ejecutar el código si es posible.** Si se puede correr un test, correrlo. Si se puede compilar localmente (no en fullmetal), compilar. Si se puede instalar en device, instalar.
6. **Preguntar antes de desviarse.** Si un paso del handoff no se puede hacer tal cual, **preguntar al usuario** en vez de inventar.

## 📋 Tabla de auto-verificación

Antes de decir "terminé", revisar:

| Pregunta | Sí / No | Si es No |
|---|---|---|
| ¿El código compila? | | Decir "no compila" explícitamente |
| ¿Los tests pasan? | | Reportar cuáles fallan y por qué |
| ¿El state machine está implementado o solo esqueleto? | | Decir "esqueleto" en el commit message |
| ¿El toggle tiene verificación post-escritura? | | Documentar como pendiente |
| ¿Los services hardcoded matchean lo que está en la tablet? | | Validar contra `adb shell settings get secure enabled_accessibility_services` antes de commitear |
| ¿El vault refleja el estado REAL del trabajo? | | Actualizar antes de cerrar la sesión |

## 🎯 Ejemplo concreto

❌ **Mal** (lo que pasó el 2026-09-01):
```
feat: integrate wireless-adb state machine into com.miro.a11y
docs(handoff): mark 2026-09-01 handoffs as completed
```

✅ **Bien**:
```
feat: scaffold wireless-adb state machine (skeleton)

- WirelessDebugAccessibilityService created with State enum and
  onAccessibilityEvent stub. NO click dispatching implemented yet.
- TODO: performGlobalAction / dispatchGesture integration
- TODO: on-device verification
- Handoff 2026-09-01-ejecutar-integracion-codigo is BLOCKED at step 5.
```

## 📚 Referencias

- [[../../Documentos/brain-vault/obsidian-workflow/01-Fundamentos]] — sistema de vault
- Handoff original: [[../03-Handoffs/2026-09-01-ejecutar-integracion-codigo]]
- Auditoría 2026-09-01: ver notas en [[02-issues-encontrados]]

> **Editado desde local** — Hermes Agent
