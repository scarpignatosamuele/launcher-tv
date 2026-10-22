# Launcher Sala – note di progetto

Launcher personalizzato per il **Chromecast con Google TV "Sala"** (`192.168.1.7:5555`). È un fork di FreeMyTV (pacchetto `io.freemytv`, firma invariata), rinominato **"Launcher"**. Branch locale: `launcher-sala`.

## Build e installazione
```sh
cd ~/src/free-my-tv
./build.sh
~/platform-tools/adb connect 192.168.1.7:5555
~/platform-tools/adb -s 192.168.1.7:5555 install -r FreeMyTV.apk
```
Ripristinare il launcher di Google TV: sulla TV, Impostazioni > Schermata Home (scegliere Google TV), oppure disinstallare `io.freemytv`. Il launcher di riserva `com.google.android.tvlauncher` resta sempre installato.

## Chiavi
In `config.properties` (escluso da git tramite `.gitignore`): `tmdb_key`, `twitch_client_id`, `twitch_client_secret`, eventuali `youtube_key` / `google_client_id` / `google_client_secret`. Modello senza valori: `config.example.properties`. **Mai committare le chiavi.**

## Decisioni di design
- Card tutte uguali, 16:9 larghe 760x427 in un carosello; quella a fuoco +6%. Le app musicali (YouTube Music, Spotify, Amazon Music…) usano solo tile quadrate stile album da ~320 px, più numerose.
- Il fuoco sul dock cambia il carosello con i contenuti di quell'app: YouTube, Prime, Twitch (top live italiane via Helix), Play Store (app consigliate), YouTube Music con chip "Brani che ti piacciono", "Le tue playlist", "Top Italia" (ultima). L'intestazione mostra il nome dell'app; "Per te" solo senza app selezionata. Mai scrivere "nessun suggerimento".
- OK su un brano: `ACTION_VIEW https://www.youtube.com/watch?v=<id>&list=<pl>` con package `com.google.android.youtube.tv` (i link tvmusic vengono ignorati).
- Mini-player rimosso (la riproduzione si ferma uscendo dall'app); nessun player extra a schermo intero.
- Sfondo con tinta leggera del colore dominante della copertina (leggermente enfatizzata, mai a tutto schermo), integrata nel backdrop 192x108 per prestazioni; copertine quadrate ritagliate al centro nel backdrop.
- Dock abbastanza opaco; impostazione "Effetto vetro" a 6 livelli (Opaco, Minimo, Leggero, Standard predefinito, Marcato, Massimo) su dock, pulsanti e chip. I pulsanti Tutte le app / Impostazioni Home / Impostazioni TV hanno il vetro del dock.
- Tile YouTube chiara; tile YouTube Music chiara.
- Solo tema scuro, nessuna impostazione Tema; impostazioni a schermo intero, minimali e ben contrastate; niente crypto, niente "confronto", "Barra" rinominata "Dock"; nessuno sfondo animato nelle impostazioni; nessuna riga "Launcher 1.0".
- Screensaver: orologio pieno (non vetro), mai tagliato; testo bianco di default, quasi nero solo su copertine molto chiare (lum>0.52 e bianco <3:1), scrim sfumato sotto 3.5:1, blur quasi invisibile 0.6 dietro il testo; indipendente dal tema.
- Nessun timer di spegnimento (c'è quello della TV).
- Prestazioni: priorità alla velocità di caricamento copertine; 3 thread BACKGROUND, decodifica alla dimensione della card, prefetch delle prime 5 per sorgente, fade 120 ms; obiettivo RAM ~115 MB PSS in Home.
- YouTube Music (`com.google.android.youtube.tvmusic`) è **PROTETTO**: non disattivarlo mai (il self-debloat lo disattivava).

## Debloat del sistema (22/10/2026)
Rimossi per l'utente 0 (`pm uninstall -k --user 0`): `android.autoinstalls.config.google.sabrina`, `com.netflix.tokenmanager`, `com.google.android.feedback`, `com.android.tv.feedbackconsent`, `com.google.android.apps.tv.netoscope`, `com.google.android.backdrop`, `com.android.printspooler`, `com.google.android.onetimeinitializer`.
Ripristino: `adb shell cmd package install-existing <pkg>`.
Lasciati: telemetria del telecomando, sincronizzazione Calendar, trasmissione/Cast, voce, CEC, `tvlauncher` come riserva.

## Da verificare
- Il tile ingranaggio (Impostazioni TV) a volte manca dal dock.
- Provare i brani di "Top Italia" sulla TV.
- Livelli dell'effetto vetro non ancora visti sulla TV.
