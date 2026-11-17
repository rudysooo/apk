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
réponse rapide depuis la notification, accusés de réception, thèmes). SMS uniquement, pas de MMS ni RCS.
APK : `dist/Messages.apk`.
