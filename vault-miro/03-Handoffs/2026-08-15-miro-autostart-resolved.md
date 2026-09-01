---
type: handoff
created: 2026-08-15
status: completed
tags: [miro, android, accessibility-service, autostart, launcher, orax-magic-q1, no-root]
summary: El servicio de miro vuelve solo tras reboot convirtiéndolo en HOME launcher wrapper. La ROM OLAX/Allwinner bloquea BroadcastReceiver, JobScheduler, AlarmManager y WorkManager para apps de usuario tras boot; solo el launcher arranca solo.
---

# RESUELTO — Servicio de miro se inicia solo tras reboot

## Problema
El `AccessibilityService` de miro no re-bindeaba tras reiniciar la tablet OLAX
Magic Q1 (Android 12, sin root). El usuario lo habilita una vez en
Ajustes→Accesibilidad, pero cada reboot lo dejaba muerto.

## Diagnóstico (medido, no supuesto)
Tras reboot el sistema:
- **conserva** `enabled_accessibility_services` (miro sigue en la lista)
- **pone** `accessibility_enabled = 0`

El re-bind que funciona es el toggle completo: sacar miro de la lista, flag 0,
esperar 2 s, re-agregar miro, flag 1.

## Vías que no sirven en esta ROM (OLAX/Allwinner)
Medido en la tablet, no en teoría. La ROM corta todos estos mecanismos para
apps de usuario tras boot:
- `BroadcastReceiver` (BOOT_COMPLETED / LOCKED_BOOT / USER_PRESENT / SCREEN_ON) → no llega
- `JobScheduler` persistido → no corre tras boot
- `AlarmManager.setAlarmClock()` → la alarma no sobrevive al reboot
- `WorkManager` (AndroidX) → el `RescheduleReceiver` queda registrado pero el
  sistema no lo dispara para miro tras boot (sí para apps de sistema como
  Google Assistant/Kids Home y para el launcher bitpit)

## Lo que funciona: miro es un HOME launcher
El único componente de usuario que Android arranca solo tras boot es el launcher.
miro declara `HOME` + `MAIN` + `DEFAULT` + `LAUNCHER` y actúa como wrapper:

1. al arrancar (boot o manual) hace el toggle completo de a11y
2. lanza el launcher real (`com.android.launcher3/.ESLauncher`) y se oculta

El usuario ve ESLauncher como siempre. El servicio de miro queda vivo tras cada
reboot sin que nadie toque la pantalla.

### Archivos clave
- `MiroLauncherActivity.kt`: intent-filter HOME en manifest + `reenableAccessibility()`
  (toggle en hilo aparte) + `launchRealLauncher()` (lanza ESLauncher y `finish()`)
- `AndroidManifest.xml`: `<category android:name="android.intent.category.HOME" />`

## Setup (ADB, una vez tras instalar)
```bash
adb install app-release.apk
# permiso para escribir secure settings (lo necesita el toggle)
adb shell pm grant com.miro.a11y android.permission.WRITE_SECURE_SETTINGS
# dejar miro como launcher por defecto SIN tocar pantalla
# (pm set-home-activity funciona porque miro ya declara el intent-filter HOME)
adb shell pm set-home-activity --user 0 com.miro.a11y/.MiroLauncherActivity
# sacarlo de Doze (opcional, recomendado)
adb shell dumpsys deviceidle whitelist +com.miro.a11y
```

## Verificación (3 reboots seguidos, los 3 pasaron)
```bash
adb shell settings get secure accessibility_enabled   # → 1
adb shell dumpsys accessibility | grep "Bound services"  # → Service[label=miro...]
adb shell logcat -d -s miro:V
# launcher activity started → re-bind triggered via launcher toggle
# → launched real launcher → miro accessibility service connected
# → miro socket listening on @miro
```

## Punto débil (dejar registrado)
Si se **desinstala y reinstala** miro se pierden:
1. el permiso `WRITE_SECURE_SETTINGS` (el toggle falla: "cannot write secure settings")
2. el launcher por defecto (vuelve a ESLauncher)

La recuperación son los 3 comandos ADB de arriba. El selector de launcher no
vuelve a aparecer una vez fijado por `pm set-home-activity` (Mori lo confirmó:
"ya no aparece el selector").

## Commits (repo MoriNo23/miro)
- `c6e0d7a` — make miro a HOME launcher
- `e34337a` — restore full a11y toggle in launcher
- `87c4460` — cleanup: remove WorkManager (launcher covers post-boot)

## Investigación
- `Olauncher` (F-Droid) clonado en `/home/extra/repositorios/Olauncher` como
  referencia del intent-filter de launcher.
- `AppManager` (MuntashirAkon) clonado en `/home/extra/repositorios/AppManager`.
  Su `BootReceiver` es `exported=false` + solo BOOT_COMPLETED; por eso no
  sobrevive en esta ROM. Su `stopped=false` persistente viene de ser app de
  sistema/preinstalada, no de un mecanismo que podamos copiar.

## Skills y notas relacionadas
- Skill: [[android-launcher-autostart]] — workflow reutilizable para arrancar
  servicios tras boot sin root vía launcher wrapper.
- Fundamento: [[olax-rom-post-boot-block]] — por qué esta ROM corta los
  mecanismos habituales y deja vivo solo al launcher.
- Herramienta: [[pm-set-home-activity-no-touch]] — fijar launcher por defecto
  por ADB sin tocar la pantalla.

> **Editado desde local** — Hermes Agent
