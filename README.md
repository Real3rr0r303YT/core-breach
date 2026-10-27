# CORE//BREACH

```
 ___ ___  ___ ___   //  ___ ___ ___   _   ___ _  _
/ __/ _ \| _ \ __| //  | _ ) _ \ __| /_\ / __| || |
| (_| (_) |   / _| //   | _ \   / _| / _ \ (__| __ |
\___\___/|_|_\___|//    |___/_|_\___/_/ \_\___|_||_|
```

**Physik-Platformer für Android.** Du bist CORE-0, ein roter Energie-Core im kompromittierten Netz von OMNIGRID.
Roll, spring, zerquetsch System-Daemons, knack Firewalls und flip die Gravitation, bis du am Kernel bist.

OLED-Schwarz `#000000` · Dot-Matrix-Weiß · Signal-Rot `#DA291C` · Glitch-Shader · Haptik · null Bitmaps.

[![Build](https://img.shields.io/badge/build-GitHub%20Actions-000000?style=flat-square&logo=githubactions&logoColor=white)](../../actions)
[![License: MIT](https://img.shields.io/badge/license-MIT-DA291C?style=flat-square)](LICENSE)
[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-3rr0r303yt-DA291C?style=flat-square&logo=buymeacoffee&logoColor=white)](https://buymeacoffee.com/3rr0r303yt)

> Inspiriert von klassischen Rolling-Ball-Platformern. Eigenständiges Projekt, keine Assets oder Namen Dritter.

---

## APK bauen

### Variante A: GitHub Actions (kein PC-Setup nötig)

```bash
git init
git add .
git commit -m "init: CORE//BREACH"
git branch -M main
git remote add origin https://github.com/<DEIN-USER>/core-breach.git
git push -u origin main
```

Der Build startet automatisch. Tab **Actions** → letzter Run `CORE//BREACH APK Builder` (~4 min) → unten bei **Artifacts** → `CORE-BREACH-apk` laden → ZIP entpacken → APK installieren.

Release mit Download-Link für alle:

```bash
git tag v0.1.0
git push origin v0.1.0
```

→ APK hängt unter **Releases** als `CORE-BREACH-v0.1.0.apk`.

> Upload über die GitHub-Weboberfläche geht auch, aber dann den Ordner `.github` unbedingt mit hochladen (versteckter Ordner!).

### Variante B: Lokal mit Docker

```bash
./build.sh            # baut im Container, APK landet in dist/CORE-BREACH.apk
./build.sh install    # per adb aufs Handy
```

### Variante C: Lokal mit Android SDK

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./build.sh native
```

### Am PC testen

```bash
./build.sh desktop    # A/D oder Pfeile = rollen, Space/W = springen, Esc = zurück
```

---

## Eigener Signier-Key (empfohlen für Releases)

Ohne Key signiert der Workflow mit einem Debug-Key. Funktioniert, aber jedes Update erfordert Neuinstallation.

```bash
keytool -genkeypair -v -keystore release.jks -alias corebreach \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks > release.jks.b64
```

Repo → **Settings → Secrets and variables → Actions** → vier Secrets anlegen:

| Secret | Wert |
|---|---|
| `KEYSTORE_B64` | Inhalt von `release.jks.b64` |
| `KEYSTORE_PASS` | Keystore-Passwort |
| `KEY_ALIAS` | `corebreach` |
| `KEY_PASS` | Key-Passwort |

`release.jks` niemals committen (steht in `.gitignore`).

---

## Steuerung

| Aktion | Touch | Tastatur |
|---|---|---|
| Rollen | Dot-Ringe unten links | `A` `D` / Pfeile |
| Springen | rechte Bildschirmhälfte | `Space` `W` `↑` |
| Kurzer Sprung | Finger früh loslassen | Taste früh loslassen |
| Zurück / Pause | `‖` oben rechts / Back | `Esc` |

## Spielelemente

| | Element | Verhalten |
|---|---|---|
| ● | **CORE-0** | Rollt per Drehmoment, 3 Integrity-Punkte |
| ■ | **Daemon** | Patrouilliert. Von der freien Seite treffen = Kill. Seitlich = Schaden |
| ▲ | **Laser-Spikes** | Schaden + Respawn |
| ◆ | **Data-Shard** | 3 pro Level, für 100 % |
| ◎ | **Gravity-Switch** | Invertiert die Gravitation. Springen geht dann „nach unten“ |
| ▣ | **Terminal** | Öffnet die nächste Firewall-Spalte rechts davon |
| ‖ | **Firewall** | Rote Laserwand, blockiert bis zum Terminal-Hack |
| ◇ | **Exit** | Level geschafft, nächstes wird freigeschaltet |

Daemons an der Decke gibt es auch. Die erwischst du von unten.

---

## Level bauen

Level sind ASCII in [`core/src/main/kotlin/dev/corebreach/level/Levels.kt`](core/src/main/kotlin/dev/corebreach/level/Levels.kt).
1 Zeichen = 1 Meter, oberste Zeile = Decke.

```
#  Block        P  Spawn (1x)      X  Exit
D  Daemon       ^  Spikes          S  Shard (genau 3x)
G  Gravity      T  Terminal        F  Firewall
.  leer
```

Sprungphysik als Faustregel: **2 Blöcke hoch**, **bis 4 Blöcke weit**.

```bash
./gradlew core:test    # Level-Validator: Spawn, 3 Shards, geschlossener Rand, Spikes befestigt, ...
```

Der Validator läuft in CI vor jedem Build. Kaputtes Level = roter Build, keine APK.

---

## Tech

| Layer | Stack |
|---|---|
| Sprache | Kotlin 2.0 (JVM 17) |
| Engine | libGDX 1.13 |
| Physik | Box2D (fixed timestep 1/60 s) |
| Rendering | 100 % `ShapeRenderer` + GLSL Post-FX (Glitch, Chromatic Aberration, Scanlines, Vignette) |
| Font | Prozedurale 5×7 Dot-Matrix (`ui/DotFont.kt`) |
| Audio | Prozedural generierte 8-bit SFX (`assets/sfx`) |
| Build | Gradle 8.10 · AGP 8.7 · minSdk 26 · targetSdk 35 |
| CI | GitHub Actions → APK-Artifact + Release |

```
core-breach/
├── .github/workflows/build.yml   APK-Pipeline
├── builder/Dockerfile            Android SDK im Container
├── build.sh                      lokaler Builder
├── assets/sfx/                   Sounds
├── core/                         Spiellogik (plattformunabhängig)
│   └── src/main/kotlin/dev/corebreach/
│       ├── CoreBreachGame.kt     Einstieg
│       ├── Services.kt           Sfx, Haptics, Save
│       ├── level/                LevelData, Levels (ASCII)
│       ├── world/                GameWorld (Box2D), WorldRenderer
│       ├── screens/              Boot, Select, Play
│       ├── fx/                   PostFx (Glitch-Shader)
│       └── ui/                   Palette, DotFont, Controls
├── android/                      Launcher, Manifest, Icon
└── desktop/                      LWJGL3-Launcher zum Testen
```

## Roadmap

- [x] **M0** Pipeline: GitHub Actions, Docker-Builder, Signing
- [x] **M1** Core-Physik: Rollen, Sprung, Coyote-Time, Daemons, Stomp
- [x] **M2** Welt 1 BOOTLOADER: 5 Level, Gravity, Firewalls, Savegame
- [ ] **M3** Juice: Musik-Loops, Partikel, Boss KERNEL_PANIC.EXE
- [ ] **M4** Welt 2 USERSPACE: Overclock-Ability, Watchdogs
- [ ] **M5** Welt 3–5, F-Droid

## Support

CORE//BREACH ist kostenlos, werbefrei und ohne Tracking. Wenn dir das Spiel gefällt, halt den Core am Laufen:

### ☕ [buymeacoffee.com/3rr0r303yt](https://buymeacoffee.com/3rr0r303yt)

[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-Support%20CORE%2F%2FBREACH-DA291C?style=for-the-badge&logo=buymeacoffee&logoColor=white)](https://buymeacoffee.com/3rr0r303yt)

## Lizenz

Code: **MIT** (siehe [`LICENSE`](LICENSE)). Sounds in `assets/sfx`: prozedural generiert, CC0.
