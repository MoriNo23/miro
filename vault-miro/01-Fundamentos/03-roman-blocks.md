---
type: fund
created: 2026-09-01
status: active
tags: [olax, rom, boot, broadcast, jobscheduler, allwinner]
summary: Por qué la ROM OLAX/Allwinner bloquea BOOT_COMPLETED, JobScheduler, AlarmManager y WorkManager para apps de usuario. Solo el HOME launcher arranca solo.
---

# 03 — ROM OLAX bloquea mecanismos estándar de post-boot

## Síntoma
Tras reiniciar la tablet OLAX Magic Q1 (Android 12, ROM Allwinner), las apps de usuario **no reciben** los broadcasts ni ejecutan sus schedulers.

## Lo que NO funciona (medido en la tablet, no en teoría)

| Mecanismo | Comportamiento |
|---|---|
| `BroadcastReceiver` (`BOOT_COMPLETED`, `LOCKED_BOOT`, `USER_PRESENT`, `SCREEN_ON`) | No llega al receiver de la app |
| `JobScheduler` persistido | No corre tras reboot |
| `AlarmManager.setAlarmClock()` | La alarma no sobrevive al reboot |
| `WorkManager` (AndroidX) | El `RescheduleReceiver` queda registrado pero el sistema no lo dispara para apps de usuario tras boot |

## Lo que SÍ funciona: HOME launcher wrapper
El único componente de usuario que Android arranca solo tras boot es el **launcher**. Por eso `MiroLauncherActivity` declara `HOME` + `MAIN` + `DEFAULT` + `LAUNCHER` y actúa como wrapper:
1. al arrancar (boot o manual) hace el toggle completo de a11y
2. lanza el launcher real (`com.android.launcher3/.ESLauncher`) y se oculta
3. el usuario ve ESLauncher como siempre
4. el accessibility service queda vivo tras cada reboot sin que nadie toque la pantalla

## Por qué (teoría confirmada por AppManager)
- AppManager (MuntashirAkon) tiene `stopped=false` persistente, lo que le permite ser invocado tras boot.
- Para apps de usuario comunes, Android bloquea los receivers hasta que el user abre la app por primera vez (estado "stopped").
- Solo el launcher es exento porque el sistema lo necesita para arrancar la UI.

## Setup one-time tras instalar
```bash
adb install app-release.apk
adb shell pm grant com.miro.a11y android.permission.WRITE_SECURE_SETTINGS
adb shell pm set-home-activity --user 0 com.miro.a11y/.BootLauncherActivity
adb shell dumpsys deviceidle whitelist +com.miro.a11y
```

> **Editado desde local** — Hermes Agent
