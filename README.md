# PC Remote

Appli Android pour allumer (Wake-on-LAN) et éteindre un PC Windows depuis le téléphone, sur le réseau local.

- `app/` : l'appli Android (Kotlin, Jetpack Compose). Guide de configuration, recherche du PC sur le réseau, allumer, éteindre.
- `pc-agent/agent.ps1` : petit agent Windows qui reçoit l'ordre d'extinction (protégé par un jeton).
- `dist/PcRemote.apk` : APK prêt à installer.

## Compiler
```
./gradlew assembleDebug
```

## Messages (appli SMS)
Module `messages/` : remplaçant de l'appli SMS d'Android (conversations, recherche, épingler, sourdine, blocage,
réponse rapide depuis la notification, accusés de réception, thèmes, archivage, envoi programmé,
MMS et groupes via la bibliothèque com.klinkerapps:android-smsmms). Pas de RCS (impossible pour une appli tierce).
APK : `dist/Messages.apk` (version optimisée : `./gradlew :messages:assembleRelease`).

## Jarvis (visuel de bureau Windows)
Dossier `jarvis-visual/` : orbe animé par-dessus le bureau, qui se pose sur les fenêtres et réagit à la souris.
Visuel uniquement, pilotable par commandes UDP locales. Zip prêt à l'emploi : `dist/jarvis-visual.zip`.
