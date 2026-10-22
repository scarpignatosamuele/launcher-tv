# Launcher TV

Un launcher personalizzato per **Chromecast con Google TV** (e altri Google TV / Android TV). È un fork di [Free My TV](https://github.com/simbasjungles/free-my-tv) (licenza MIT).

> ⚠️ **Questo repository NON contiene chiavi API.** Ognuno deve creare le proprie (sono gratuite) e metterle nel proprio `config.properties`, che resta sul suo computer e non finisce mai su git. Le istruzioni sono qui sotto.

---

## Cosa cambia rispetto a Free My TV

- **Carosello uniforme:** card 16:9 tutte uguali; quella selezionata si ingrandisce appena (+6%).
- **Il carosello segue il dock:** quando selezioni un'app nel dock, sopra compaiono i suoi contenuti.
  - **YouTube:** video in tendenza oppure, se colleghi l'account, le ultime uscite dei tuoi iscritti
  - **Twitch:** le dirette italiane più seguite
  - **Play Store:** app consigliate
  - **Prime Video** e le altre app di streaming: i titoli del momento (TMDB)
  - **YouTube Music:** "Brani che ti piacciono", "Le tue playlist", "Top Italia". Le app musicali (YouTube Music, Spotify, Amazon Music…) usano tile quadrate in stile copertina d'album.
- **Lo sfondo prende una leggera tinta** del colore dominante della copertina selezionata.
- **Effetto vetro regolabile** su 6 livelli (Opaco → Massimo) per dock, pulsanti e chip. Il dock è abbastanza opaco.
- **Solo tema scuro.** Impostazioni a schermo intero, poche e ben contrastate. Niente criptovalute, niente sfondi animati.
- **Screensaver con orologio leggibile:** testo bianco, che diventa scuro solo su copertine molto chiare.
- **Prestazioni:** le copertine si caricano velocemente (3 thread in background, decodifica alla dimensione della card, prefetch). Circa 115 MB di RAM in Home.

---

## 1. Cosa ti serve

- Un computer (macOS, Linux o Windows con Git Bash)
- **JDK 17** o superiore
- **Android SDK command-line tools** con `build-tools` 34+ e `platforms;android-34`
- **adb** (dal pacchetto [platform-tools](https://developer.android.com/tools/releases/platform-tools))
- La TV con **Opzioni sviluppatore** attive. Le attivi da Impostazioni → Sistema → Informazioni → premi 7 volte su "Build sistema operativo Android TV". Poi accendi **Debug USB** (o **Debug wireless**).

Su Mac, per esempio:
```sh
brew install openjdk@17 android-commandlinetools android-platform-tools
sdkmanager "build-tools;34.0.0" "platforms;android-34"
```

## 2. Scarica il progetto
```sh
git clone https://github.com/scarpignatosamuele/launcher-tv.git
cd launcher-tv
```

## 3. Crea il tuo file di configurazione
```sh
cp config.example.properties config.properties
```
Apri `config.properties` con un editor di testo e compila i campi. **Questo file è già in `.gitignore`: non caricarlo mai online.**

Impostazioni base:
| Campo | Cosa mettere |
|---|---|
| `tmdb_region` | `IT` |
| `language` | `auto` (segue la lingua della TV) |
| `latitude`, `longitude`, `city` | la tua città, per il meteo e il tramonto (es. Milano `45.4642`, `9.1900`, `Milano`) |
| `temperature` | `C` |
| `show_crypto` | `false` |
| `disable_assistant` | `false` (con `true` spegni anche "Ok Google") |

## 4. Le chiavi API (ognuno le sue, tutte gratuite)

Ogni chiave è **facoltativa**: se lasci un campo vuoto, la sezione corrispondente non compare e il resto del launcher funziona lo stesso.

### TMDB (titoli consigliati di film e serie): `tmdb_key`
1. Crea un account su [themoviedb.org](https://www.themoviedb.org/signup).
2. Vai su [Impostazioni → API](https://www.themoviedb.org/settings/api) e richiedi una chiave "Developer" (uso personale).
3. Copia l'**API Read Access Token** (quello lungo) oppure l'**API Key** e incollalo dopo `tmdb_key=`.

### YouTube (scheda YouTube): `youtube_key`
1. Vai su [Google Cloud Console](https://console.cloud.google.com/) e crea un progetto (es. "launcher-tv").
2. In **API e servizi → Libreria**, cerca **YouTube Data API v3** e premi **Abilita**.
3. In **API e servizi → Credenziali** premi **Crea credenziali → Chiave API**.
4. (Consigliato) Premi su "Limita chiave" e consenti solo "YouTube Data API v3".
5. Incolla la chiave dopo `youtube_key=`.

### Account YouTube / YouTube Music (iscrizioni, brani che ti piacciono, playlist): `google_client_id`, `google_client_secret`
Ti serve solo se vuoi vedere i **tuoi** contenuti.
1. Nello stesso progetto Google Cloud apri **API e servizi → Schermata consenso OAuth**. Scegli tipo **Esterno**, compila nome app ed email e aggiungi te stesso fra gli **utenti di test**.
2. In **Credenziali → Crea credenziali → ID client OAuth** scegli il tipo **"TV e dispositivi con input limitato"**.
3. Copia l'**ID client** in `google_client_id=` e il **Client secret** in `google_client_secret=`.
4. Sulla TV, la prima volta, il launcher ti mostra un codice da inserire su google.com/device con il tuo account (permesso di sola lettura, `youtube.readonly`).

### Twitch (dirette italiane più seguite): `twitch_client_id`, `twitch_client_secret`
1. Accedi a [dev.twitch.tv/console/apps](https://dev.twitch.tv/console/apps) (serve l'autenticazione a due fattori sull'account Twitch).
2. Premi **Registra la tua applicazione**:
   - Nome: quello che vuoi
   - OAuth Redirect URL: `http://localhost`
   - Categoria: "Application Integration"
   - Tipo di client: **Riservato**
3. Apri l'app che hai creato e copia il **Client ID** in `twitch_client_id=`.
4. Premi **Nuovo segreto** e copia il valore in `twitch_client_secret=`.

Puoi verificare che le chiavi funzionino con:
```sh
./tools/test-streams.sh
```

## 5. Compila
```sh
./build.sh
```
Viene creato `FreeMyTV.apk`. La prima volta viene creato anche `freemytv.keystore`, la tua chiave di firma. **Tienilo**: gli aggiornamenti futuri devono essere firmati con lo stesso file, altrimenti devi disinstallare e reinstallare.

> 🔒 **Importante:** le chiavi API vengono inserite *dentro* l'APK al momento della compilazione. Quindi **non condividere il tuo APK**: chi lo riceve avrebbe le tue chiavi. Ognuno si compila il proprio.

## 6. Installa sulla TV
Trova l'indirizzo IP della TV (Impostazioni → Rete), poi:
```sh
adb connect 192.168.1.X:5555        # con Debug wireless (Android 14) usa IP:porta mostrati in "Debug wireless"
adb install -r FreeMyTV.apk
```
La prima volta, sulla TV compare "Consentire il debug?": premi **Consenti** e spunta "Consenti sempre".

Poi premi il tasto Home del telecomando e scegli **Launcher** come schermata Home.

## 7. Tornare al launcher originale
Sulla TV: Impostazioni → Schermata Home → scegli Google TV. Oppure da computer:
```sh
adb uninstall io.freemytv
```
Il launcher originale di Google TV (`com.google.android.tvlauncher`) non viene mai rimosso.

---

## Debloat facoltativo (avanzato)
Sul Chromecast originale abbiamo disattivato alcune app di sistema inutili, solo per l'utente e in modo **reversibile**:
```sh
adb shell pm uninstall -k --user 0 <pacchetto>
```
I pacchetti disattivati sono:
- `android.autoinstalls.config.google.sabrina`
- `com.netflix.tokenmanager`
- `com.google.android.feedback`
- `com.android.tv.feedbackconsent`
- `com.google.android.apps.tv.netoscope`
- `com.google.android.backdrop`
- `com.android.printspooler`
- `com.google.android.onetimeinitializer`

Per ripristinarne uno:
```sh
adb shell cmd package install-existing <pacchetto>
```
⚠️ **Non disattivare mai** YouTube Music (`com.google.android.youtube.tvmusic`), Cast, la voce, CEC o `tvlauncher`.

---

Licenza MIT: vedi [LICENSE](LICENSE). Progetto originale: [Free My TV](https://github.com/simbasjungles/free-my-tv).
