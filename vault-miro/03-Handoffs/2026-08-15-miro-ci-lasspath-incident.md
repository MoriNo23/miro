---
type: handoff
created: 2026-08-15
project: miro
status: resolved
topic: CI build incident — Gradle wrapper 'lasspath' + Android resource errors
related_skill: android-gradle-ci-no-wrapper
---

# miro — Incidente CI (2026-08-15)

Repositorio: `MoriNo23/miro` (privado, GitHub). App de accesibilidad para tablet OLAX Magic Q1 (Android 12, API 31, ARMv7 32-bit).

## Contexto

Se creó el repo desde cero con un `AccessibilityService` stub (Kotlin). El CI (GitHub Actions) debía compilar el APK debug en cada push. El primer run falló y encadenó 7 corridas hasta quedar verde.

## Secuencia de errores y fixes (CRONOLOGÍA — para revisar al actualizar la skill)

| # | Síntoma | Causa | Fix aplicado |
|---|---------|-------|--------------|
| 1 | `The specified settings file '.../lasspath' does not exist.` | `gradle/actions/setup-gradle@v3` inyecta init script Develocity que rompe el path del settings file | Quité setup-gradle, agregué `android-actions/setup-android@v3` |
| 2 | mismo `lasspath` | setup-android TAMBIÉN inyecta init script roto | Removí setup-android, instalé SDK manual con cmdline-tools |
| 3 | mismo `lasspath` | `actions/setup-java@v5` inyecta init script Develocity (el real culpable) | Probé JDK manual + `--no-init-script` |
| 4 | `Process completed with exit code 127` en install JDK | URL Adoptium devolvió vacío / glob `mv /opt/jdk-17*` sin match con `set -e` | Volví a setup-java@v5 + `--no-init-script` en gradle |
| 5 | mismo `lasspath` | `--no-init-script` NO es flag válido de `gradle <task>` (solo de `gradle init`) | Quité `--no-init-script` |
| 6 | `android:canTakeScreenshots not found` (AAPT resource linking) | atributo no existe en XML metadata de accessibility-service (es API en código) | Quité `android:canTakeScreenshots="true"` del XML |
| 7 | `mv: cannot move '/opt/gradle-8.5' to a subdirectory of itself` | `unzip -d /opt` ya crea `/opt/gradle-8.5`; el `mv` posterior era redundante/autoreferencial | Usé path extraído directo, sin `mv` |
| 8 | **VERDE** | — | workflow final: Gradle binario 8.5 + SDK manual + setup-java@v5 |

## Root cause final

El `gradle-wrapper.jar` descargado de `raw.githubusercontent.com/gradle/gradle/<tag>/...` es el wrapper de DESARROLLO, no el release. Combinado con el init script que `actions/setup-java` inyecta, Gradle 8.5 trunca `classpath` → `lasspath`.

**Solución definitiva:** NO usar `./gradlew`. Instalar Gradle 8.5 como binario en el runner y llamarlo por path absoluto (`/opt/gradle-8.5/bin/gradle`).

## Workflow final (verificado, build 3m26s)

Ver skill `android-gradle-ci-no-wrapper` (en `~/.hermes/skills/devops/`). Resumen:
- `actions/setup-java@v5` (temurin 17)
- Instalar Gradle 8.5 binario manualmente (curl zip → unzip /opt → usar path directo)
- Instalar Android SDK cmdline-tools manualmente + `sdkmanager` platforms/android-34, build-tools/34.0.0
- `/opt/gradle-8.5/bin/gradle assembleDebug --no-daemon`
- Upload artifact APK (~2.5 MB)

## Cuándo revisar esto

Si al actualizar la skill `android-gradle-ci-no-wrapper` cambiamos de versión de Gradle (9.x), o si `actions/setup-java` deja de inyectar init scripts, este handoff es la bitácora de por qué se eligió el approach binario. El APK artifact de referencia es `miro-debug-apk` (~2.66 MB).

## Estado al cierre

- Repo privado `MoriNo23/miro`: ✓
- CI verde en push a main: ✓ (run 31878244452)
- Skill `android-gradle-ci-no-wrapper` creada: ✓
- Siguiente paso: implementar lógica real del AccessibilityService (gestos, taps, comunicación con PC vía ui2).
