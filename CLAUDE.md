# Free My TV: notes for Claude

A home screen for Google TV and Android TV that replaces the stock one, plus a settings app that switches between
Free My TV and the TV's original home screen. A plain Android app in Java, built without Gradle by `build.sh`.
The website (freemytv.io) is in `docs/`: static HTML, CSS and JS, no build step. A push to `main` that changes it
publishes it to GitHub Pages (`.github/workflows/pages.yml`). `docs/index.html` (English) and `docs/pt/index.html`
(Brazilian Portuguese) are the same page in two languages, so change both; visitors from Brazil land on the
Portuguese one by themselves, and the PT/EN switch in the menu is remembered.

The person you're helping may not be technical. Keep it short and friendly, say what you're about to do on their TV
before you do it, and let them do the taps on the TV.

## Installing it for someone

1. **Ask** for the TV's IP address and their city, if they didn't say.
2. **Tools:** a JDK 17+, the Android SDK command-line tools (build-tools 34+, platform android-34) and adb. Check
   what's already there (`java -version`, `$ANDROID_HOME`, `adb version`) and install only what's missing.
   - macOS: `brew install --cask temurin android-commandlinetools android-platform-tools`, then
     `sdkmanager "build-tools;34.0.0" "platforms;android-34"`.
   - Linux: OpenJDK 17, Android's command-line tools and adb from the package manager, then the same `sdkmanager` line.
   - Windows: WSL, then as on Linux.
3. **Settings:** run `./build.sh` once; it creates `config.properties`. Fill in `latitude` and `longitude` (decimal
   degrees) and `city` for their city, `tmdb_region` (two-letter country), `show_crypto`, `currency` and `temperature` to match.
4. **The TMDB key is theirs and secret.** Ask them to create one (free: themoviedb.org → Settings → API) and paste it
   after `tmdb_key=` in `config.properties` themselves. Don't ask for it in the chat, never print it, never commit
   it. If they paste it in the chat anyway, put it only in `config.properties`.
   Without a key everything works except the picks and the screensaver photos.
5. **Build:** `./build.sh` → `FreeMyTV.apk`. Keep `freemytv.keystore`: updates must be signed with the same key.
6. **Install:** `adb connect <IP>:5555`. The TV asks *Allow USB debugging?*: tell them to accept and tick *Always
   allow*. Then `adb install -r FreeMyTV.apk`. On TCL, also `adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"`
   (some firmware calls the operation `APP_AUTO_START`; this tries both):
   TCL's `TclAppBoot` blocks background jobs by default, so the switch (and the watchdog) never run. The log shows
   `forbid bind service ... ModeJob`.
7. **OpenTV, the IPTV player** (third on the shelf, next to YouCine): `tools/install-opentv.sh <IP>`. The script finds
   the APK in `apps/OpenTV.apk` or in a sibling checkout of the OpenTV project (`../opentv/dist`, where it is built
   with `./gradlew assembleRelease`). The APK has to be signed with the same key as the one already on the TV, or
   Android refuses to replace it. On TCL, also `adb shell "appops set app.opentv AUTO_START allow || appops set app.opentv APP_AUTO_START allow"`
   — OpenTV records to disk and keeps a wake lock while it does, and TCL's `TclAppBoot` stops background jobs.
8. **Tell them what the switch does** before they make it: the home screen and screensaver change, a short list of
   promo apps is turned off (never uninstalled), and *Original* brings it all back. On TCL, the remote's Netflix,
   YouTube and Prime buttons keep working through a small accessibility service that only reads those buttons
   (*Free My TV* → *Remote Buttons* picks what each opens); *Original* turns it off.
9. **They switch on the TV:** *Free My TV* → *Home Screen* → *Free My TV*. The app uses the TV's own ADB on
   127.0.0.1 with its own key, so the TV asks once more to allow debugging. Don't switch modes from the computer.

   **If the switch stops and asks them to forget the Wi-Fi**, it is the app protecting the network, not a bug. When
   Android turns off an app, it also deletes the Wi-Fi networks that app saved. The switch turns off the TV's first
   setup (Google TV's setup wizard), and that is the app that saved the network during the setup — so the switch
   would take the TV offline for good, and *Original* re-enables apps but cannot bring a deleted network back. The
   app therefore stops before changing anything.

   The fix is to make the network belong to someone else. On the TV: *Settings* → *Network & Internet* → the
   network → *Forget*, then connect to it again with the password. Now the Settings app owns it, and the Settings
   app is never turned off (it is on the protected list), so the network survives the switch. Then try again.

   A saved network that is out of range doesn't show in the TV's list — the TV only lists the ones it can see. Those
   have to go from the computer: `adb shell dumpsys wifi` shows every saved network as a block that starts with
   `* ID: <n> SSID: "<name>"` and ends with `cname=<the package that saved it>`; the ones from the TV's first
   setup say `cname=com.google.android.tungsten.setupwraith`. With their OK, `adb shell cmd wifi forget-network <n>`.
10. **Check:** `adb logcat -s FreeMyTV` shows what the switch did.

## Adapting it to another TV

Read first, change after. Read-only commands that tell you what the TV has:

```bash
adb shell getprop ro.product.brand; adb shell getprop ro.product.model; adb shell getprop ro.build.version.release
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd role get-role-holders android.app.role.HOME
adb shell settings get secure screensaver_components; adb shell settings get secure screensaver_default_component
adb shell pm list packages -s     # system apps (-3 for the ones they installed)
```

Then, in the code:

- `HomeMode.DEBLOAT`: add the brand's promo, demo, store-mode and telemetry apps, one comment per line. Only those.
- `HomeMode.PROTECTED`: add the brand's system pieces: picture, sound, inputs and HDMI-CEC, the tuner and live TV,
  remote and Bluetooth pairing, setup, updates, system UI. When in doubt, protect it.
- `HomeApps.SHELF`, `LIVE_TV_APPS` and `HIDDEN`: the shelf apps, the brand's live TV app, apps to hide from All Apps.
- Build, install, have them switch on the TV, read `adb logcat -s FreeMyTV`, then have them switch back to
  *Original* once and confirm everything returned.

TVs that don't run Google TV or Android TV (Samsung, LG and others): the way is a Google TV streamer on HDMI
(Chromecast with Google TV, Google TV Streamer, onn 4K, NVIDIA Shield). Set Free My TV up on the streamer.

**Uninstalled while in Free My TV mode?** The apps it turned off stay off. Bring them back with
`adb shell pm enable <package>` (the `DEBLOAT` list and the stock home screen), then
`adb shell cmd package set-home-activity <stock home activity>` and
`adb shell settings put secure screensaver_components <stock screensaver>`.

## The code

- Java 8 language level, minSdk 28 (Android 9), targetSdk 33. No Gradle, no libraries: only the Android SDK.
  Android 9 has no RenderNode (`Recording` wraps it, with a Picture there) and no `cmd role`. On Android 9 to 11
  (`Ui.LEGACY`) the home screen skips the GPU layers, draws blurred shadows from cached bitmaps (`Shadows`) and
  rounds images with a shader instead of clipPath: their GPUs (a Mi Box 3's Mali-450) choked on those.
  Android 11 runs the screensaver as `ScreensaverAlone`, in the main process (its DreamActivity needs the dream in the
  app's default process, without a home activity); `res/values-v30/bools.xml` picks it, every other version keeps
  `Screensaver` in `:home`. Keep both in step.
  To check for newer APIs, compile against `platforms/android-28/android.jar`: only AGSL (`RuntimeShader`, guarded
  by a try/catch), `BitmapShader.setFilterMode` (guarded) and `Recording.Node` should come up.
- The home screen is drawn by hand on one Canvas (`HomeView`); the glass and the sky are AGSL shaders (`Shaders`,
  Android 13+, with a fallback for 9 to 12). Keep it at 60 fps: no allocations in `onDraw`, no work on the UI thread.
- `HomeMode` runs every switch as a background job (`ModeJob`) in the main process; the UI is in the `:home`
  process. `Watchdog` goes back to the stock home screen after 3 crashes in 5 minutes.
- On-screen text: `Ui.tr("pt", "en")`, with the Spanish in `Spanish.java` (keyed by the English); text built at
  runtime uses `Ui.tr(pt, en, es)`. Add all three. Comments in English, short, only where the why
  isn't obvious.
- Settings come from `config.properties` through the generated `out/gen/io/freemytv/Config.java` (never edit that).
  What the person changes on the TV (`Prefs`: Home Settings, Shelf, Remote Buttons) wins over those first values.
- The remote's app buttons (`RemoteKeys`, `RemoteKeyService`) use TCL's key codes: they run only on TCL TVs, the
  service is on only in Free My TV mode, and *Original* takes it out of the enabled accessibility services.
- The remote's app buttons (`RemoteKeys`, `RemoteKeyService`, `RemoteKeyReceiver`): the TV's IR receiver says it is a
  gamepad ("KEYBOARD | DPAD | GAMEPAD"), so the source of the key says nothing about it — do not filter by the source,
  only by the key code (the controller's own buttons). The key codes are the TV's: on this TCL they are 265 (Netflix),
  4065 (YouTube), 4099 (Prime Video), 4141, 4097 and 4020 (TCL Channel) — not the 4062/190/4099 the defaults have.
  The app learns them: a button it does not know is remembered and shows up in *Botões do Controle* ("Botão 265"),
  where the person picks the app for it. Two ways in: the keys that reach the app layer (the accessibility service)
  and the ones the TV's own window manager grabs first (it says so in a broadcast, "com.tcl.TCLkeyevent" and
  "tcl.sys.intent.action.USERSTATE_KEYDOWN" — `RemoteKeyReceiver` listens, registered at runtime, because a
  receiver in the manifest does not get implicit broadcasts since Android 8).
- The picks open Nuvio on the title (`nuvio://meta?type=movie&id=tt…`). Nuvio comes in two builds: the Play Store
  one is `com.nuvio.app`, the one from GitHub is `com.nuvio.tv` — the app looks for both (`HomeApps.nuvioPackage`).
- How many titles the home screen shows in each tab: *Ajustes do Início* → *Títulos na tela* (3, 5 or 8). The row of
  cards slides sideways when there are more than fit; only the cards on screen get their picture (each one is a 4K
  download and a blur).
- The games of the day (`Games.java`): two sources. API-Football (the key in `config.properties`, `api_football_key`)
  for the football — the teams' crests, the league and its country; it wants the date as "2026-10-21" and the
  timezone, and the free plan only serves a few days around today. ESPN's public scoreboard (no key) for the TV
  channel — it publishes the network of each game, mostly the American one, so the country is shown with a flag
  (from flagcdn.com; the flag emoji does not render on the TV) — and for the sports API-Football does not serve
  here (American football and MMA). The two are matched by the league and the closest time (the channel is the
  same for the whole round). The crests come from ESPN (its host resolves everywhere; the API's does not).
  The list is kept in a file (`games.json`, with a version number: bump it when the data changes, or the old file
  is kept).
- The games have their own focus springs (`gameFocus`): without them the screen is not redrawn when the focus
  moves in the list, and it feels frozen. The texts are measured once, when the list changes (no allocations while
  drawing).
- The surprise (`HomeApps.SHUFFLE`): the button in the corner opens a small panel (movie or series) and opens a random
  title of that kind in Nuvio, on the title itself.
- After any change, `./build.sh` must end with `Built FreeMyTV.apk`.

## Always

- Never turn off an app that saved a Wi-Fi network: Android deletes its networks and Original can't bring them
  back (`wifiOwners` in `HomeMode`).
- Never commit `config.properties`, `*.keystore` or any key.
- Never uninstall or clear the data of system apps; only `pm disable-user --user 0`, through `HomeMode`, so
  *Original* can bring it back.
- Don't change the TV's picture, sound, network or account settings.
- Keep the way back working: the *Original* mode and the watchdog.
