# Coldshift Dash

A car dashboard for old Android phones. Put a phone you no longer use in a
cradle, and it becomes the car's head unit: offline maps and turn-by-turn
navigation, engine readings, music, a dashcam and driving together with
friends. It runs on phones as old as Android 4.4.

> Website: **https://dash.coldshift.racing**

## What it does

- **Maps and navigation.** Offline vector maps (Mapsforge) in a calm dark
  theme, offline routing (BRouter), spoken prompts, place search, and Home,
  Work and saved places. No mobile data needed once the maps are on the phone.
- **Engine.** Live readings and fault codes from a Bluetooth or Wi-Fi ELM327
  OBD-II adapter.
- **Sensors.** Bluetooth tyre-pressure sensors, an external GPS receiver and a
  talk button can each take a role.
- **Media.** A music player for the songs on the phone's card.
- **Dashcam.** Records with the camera, keeps marked clips.
- **Logbook and track.** Trips are logged (exportable as GPX and CSV), and a lap
  timer runs on a track.
- **Convoy.** Cars in a convoy see each other on the map and send each other
  messages and voice notes, through a Convoy server (see
  [Convoy](#convoy)) or, with no internet, through one phone acting as the
  host over its hotspot.
- **Your car's look.** Each vehicle gets its own title, badges, background,
  splash screen and top-down car picture.

The app is the phone's home screen while it is in the car, and starts on boot.

## The two builds

One source tree builds two APKs:

| APK | For | Notes |
| --- | --- | --- |
| `dash-legacy.apk` | Android 4.4 (API 19) and up | Targets API 19, so it gets its permissions at install. For the oldest phones. |
| `dash-modern.apk` | Android 6.0 (API 23) and up | Targets API 35 and asks for permissions in the app. For newer phones. |

Both are compiled from the same code against a current SDK; anything newer
than Android 4.4 sits behind version checks (`Api.java`). Only the SDK versions
in the manifest differ.

## Building

The build uses the Android SDK's command-line tools directly (no Gradle).

You need:

- The Android SDK, with **build-tools 36.1.0** and **platform android-35**.
  `ANDROID_HOME` points at it (default `~/Library/Android/sdk`).
- A JDK 17 or newer. `JAVA_HOME` points at it (default: the JDK that comes
  with Android Studio on macOS).
- `curl`, `unzip`, `zip` and `shasum` (or `sha1sum`).

Then:

```bash
android/car/libs/fetch.sh
```

```bash
android/car/build.sh
```

`fetch.sh` downloads Mapsforge, ZXing and AndroidSVG from Maven Central, builds
BRouter's routing core from its release source, and unpacks the map theme
resources into `assets/`. None of these are committed. Run it once; it skips
what is already there.

`build.sh` compiles, dexes and signs both APKs into `android/car/build/`. It
signs with your debug keystore (`~/.android/debug.keystore`, made if missing).
Environment variables change the SDK paths, the signing key and the version;
they are listed at the top of `build.sh`.

Install on a phone with:

```bash
adb install -r android/car/build/dash-modern.apk
```

## Putting maps on the phone

The app reads everything from a `coldshift.dash` folder on the phone's SD card
(or its internal storage):

| Folder | What |
| --- | --- |
| `coldshift.dash/maps/` | A Mapsforge `.map` file for your region, from [download.mapsforge.org](https://download.mapsforge.org/maps/v5/) |
| `coldshift.dash/routing/` | BRouter routing tiles (`*.rd5`) covering it, from [brouter.de](https://brouter.de/brouter/segments4/), for offline navigation |
| `coldshift.dash/Music/` | Your music, in any folder layout |

[Coldshift Dash desktop](https://github.com/thefutureisnownow/coldshift-dash-desktop) does this for you, from a Mac, Windows or Linux computer.

## Releases

Signed APKs are on the [Releases](https://github.com/thefutureisnownow/coldshift-dash/releases) page, each with a
`SHA256SUMS`. They are signed with the project's release key; its certificate's SHA-256 is
`E2:31:5C:67:F0:26:B9:1B:A5:E6:44:38:D2:84:06:D5:E4:58:11:04:DE:64:8B:72:C8:EC:C2:64:61:26:E0:88`.

A copy you build yourself (`android/car/build.sh`) is signed with your debug key, so it installs only after
removing a release one. `tools/release.sh <version>` builds a release with a key of your own (see its first lines).

## Repository layout

| Path | What |
| --- | --- |
| `android/car/` | The app: `src/`, `assets/`, `res/`, `build.sh`, `libs/fetch.sh` |
| `tools/` | `release.sh`, and making the app's content: the brand icons (`gen_dash_brand.py`) and the voice pack (`make_voice_pack.py`); `car-adb.sh` watches the app in the car over Wi-Fi adb |
| `brand/` | The logos the icons are made from |

## License

MIT, see [LICENSE](LICENSE). Third-party software, the map theme derived from
Mapsforge (LGPL-3.0) and notes on the art and brand are in [NOTICE](NOTICE).

Coldshift Dash is a TFINN production, by The Future is Now-Now (PTY) Ltd.
