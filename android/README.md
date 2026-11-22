# LocalDrop – transfert P2P hors-ligne (Android, Kotlin)

Ouvrir le dossier `android/` dans Android Studio (JDK 17), puis :

    ./gradlew :app:testDebugUnitTest     # tests crypto / anti-bruteforce / streaming / chemins
    ./gradlew :app:assembleRelease       # APK universel unique (aucun split)

Modules (packages) : `crypto` · `protocol` · `network` · `web` · `ui`.

Décisions notables
- PAKE : BouncyCastle ne fournit pas SPAKE2 ; on utilise **J-PAKE** (implémentation BC éprouvée) – aucune crypto maison.
- Canal : AES-256-GCM, nonce 96 bits = préfixe de direction + compteur strictement croissant, clé distincte par direction et par connexion.
- `INTERNET` n'est déclarée que pour ouvrir des sockets locaux ; aucun appel Cloud.
- Wi-Fi Direct : la constante réelle est `WifiP2pConfig.GROUP_OWNER_BAND_5GHZ` (API 29+).
- Stockage : le récepteur écrit via `java.io.File` (dossier configurable). Pour écrire dans Download/ public en
  API 29+, adapter `FileReceiver` avec MediaStore ou SAF (à brancher avant release).
- Le code Kotlin n'a pas pu être compilé dans l'environnement de génération : première étape = `./gradlew build`.
