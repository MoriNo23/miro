---
type: project-index
created: 2026-09-01
status: active
tags: [miro, android, accessibility-service, launcher, vault]
summary: Vault de Obsidian embebido en el repo miro. Contexto, decisiones, handoffs y diseño del proyecto unificado launcher-wrapper.
---

# vault-miro — Contexto del proyecto

Este directorio es un **vault estilo Obsidian** dentro del repo de código de `miro`. Funciona como memoria persistente del proyecto: contiene fundamentos, herramientas, handoffs entre sesiones, decisiones de diseño y estrategia.

## Estructura

| Carpeta | Contenido |
|---|---|
| [[01-Fundamentos/README\|01-Fundamentos]] | Teoría, historia, decisiones, problemas conocidos |
| [[02-Herramientas/README\|02-Herramientas]] | Scripts, configs, herramientas usadas por el proyecto |
| [[03-Handoffs/README\|03-Handoffs]] | Handoffs discretos entre sesiones — cada uno ejecutable |
| [[04-Estrategia/README\|04-Estrategia]] | Plan a futuro, roadmap, decisiones pendientes |
| [[05-Diseno/README\|05-Diseno]] | Arquitectura, diagramas, prototipos de diseño |

## MOC (Map of Content)

Ver [[00-MOC]] para índice navegable.

## Reglas del vault

1. **Frontmatter obligatorio** en cada `.md`: `type`, `created`, `status`, `tags`, `summary`.
2. **Wikilinks `[[...]]`** para cross-references. Sin links sueltos a directorios (siempre `[[carpeta/README|alias]]`).
3. **Handoffs discretos**: un archivo por sesión, con paths absolutos y comandos copy-pasteable.
4. **Proponer antes de escribir** en categorías gobernadas (todo fuera de handoffs automáticos). El agente propone; el humano aprueba.

## Cómo usar este vault

- Para retomar el proyecto: leer [[00-MOC]] → [[03-Handoffs/README|03-Handoffs]] → último handoff por fecha.
- Para entender el sistema: leer [[01-Fundamentos/README|01-Fundamentos]].
- Para ejecutar un workflow: ir a [[02-Herramientas/README|02-Herramientas]] o al handoff específico.

> **Editado desde local** — Hermes Agent
