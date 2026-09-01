---
type: moc
created: 2026-09-01
status: active
tags: [miro, moc, index]
summary: Map of Content — índice navegable del vault-miro.
---

# MOC — vault-miro

Índice navegable de todo el contenido del vault.

## 📐 Fundamentos
- [[01-Fundamentos/01-wireless-adb-historia]] — Historia y aprendizaje del proyecto wireless-adb (ya fusionado aquí)
- [[01-Fundamentos/02-arquitectura-actual]] — Arquitectura del launcher-wrapper + service
- [[01-Fundamentos/03-roman-blocks]] — Por qué la ROM OLAX bloquea BOOT_COMPLETED, JobScheduler, etc.

## 🔧 Herramientas
- [[02-Herramientas/01-adb-helpers]] — Scripts ADB usados frecuentemente
- [[02-Herramientas/02-setup-adb]] — Setup one-time vía ADB
- [[02-Herramientas/03-verify-autostart]] — Verificación post-reboot

## 📋 Handoffs
- [[03-Handoffs/2026-08-15-miro-autostart-resolved]] — Resuelto el autostart vía HOME launcher
- [[03-Handoffs/2026-08-15-miro-ci-lasspath-incident]] — Fix CI lasspath
- [[03-Handoffs/2026-09-01-fusion-wireless]] — Fusión wireless-adb → miro (este proyecto)
- [[03-Handoffs/2026-09-01-ejecutar-integracion-codigo]] — Handoff ejecutable original (parcial)
- [[03-Handoffs/2026-09-01-corregir-issues-integracion]] — **Corregir los 4 issues de la auditoría** (prioridad alta)

## 🚀 Estrategia
- [[04-Estrategia/01-roadmap]] — Qué sigue después de la fusión
- [[04-Estrategia/02-naming-decision]] — Por qué `com.bootstrap.olax` y no `com.miro.a11y`

## 🎨 Diseño
- [[05-Diseno/01-state-machine]] — State machine del service de wireless debug
- [[05-Diseno/02-arquitectura-final]] — Arquitectura final unificada

## 📐 Reglas (anti-alucinación, etc.)
- [[06-Reglas/01-anti-alucinacion]] — Reglas para que el agente no marque como completed sin verificar
- [[06-Reglas/02-issues-encontrados]] — Auditoría del trabajo del agente anterior

> **Editado desde local** — Hermes Agent
