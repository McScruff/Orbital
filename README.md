# Orbital

A full-featured IPTV player for Android, built for Xtream Codes providers. Works on phones, tablets, Fire TV Stick, Chromecast with Google TV, and the Google TV Streamer.

**Discord:** [discord.gg/WCdKAA2A7N](https://discord.gg/WCdKAA2A7N)

---

## Download

Get the latest APK from the [Releases](https://github.com/McScruff/Orbital/releases/latest) page.

---

## Features

### Live TV
- Browse channels by category with instant switching
- Electronic Programme Guide (EPG) with now/next info
- Full 7-day EPG grid view
- Set reminders for upcoming programmes
- Catchup TV — replay past broadcasts where your provider supports it
- Live stream format toggle (TS / HLS)
- Audio track picker for multi-language streams

### Box Office
- **Movies** — browse, search and stream your VOD library
- **Series** — full series and episode browser with artwork
- **Catchup** — catch up on missed live TV
- **Continue Watching / Favourites** — resume where you left off

### Radio
- Built-in radio station playlist
- Quick popup picker in both normal and TV mode

### Sports & News
- Live football scores ticker with league selection
- Live scores from ESPN — updates automatically during matches
- News headlines ticker fed from RSS sports feeds
- Full league tables and fixture lists

### Recording
- Record live TV directly to device storage
- Scheduled and instant recording
- Background recording while watching another channel

### TV Mode
Optimised D-pad interface for big-screen devices (Fire TV Stick, Chromecast, Google TV Streamer):
- **LEFT** — open channel list → category list → main menu
- **RIGHT** — step back through panels
- **UP / DOWN** — change channel while watching
- Now & Next EPG shown alongside every channel
- Vertical menu: Live TV, Box Office, Radio, Interactive, Settings
- Press **BACK** to exit, dismiss overlays, or step back through menus

### Interactive
- Emby server browser — connect and stream your Emby library
- Plex server browser — connect and stream your Plex library
- Sports standings and fixtures
- Teletext viewer
- Bubble Shooter game

### Player
- ExoPlayer with FFmpeg extension — handles AC3/EAC3, HEVC and unusual codec profiles
- OpenSubtitles integration for automatic subtitle search
- Picture-in-Picture support
- Screen stays on during playback on all devices

### General
- Multiple Xtream server profiles — switch without logging out
- Global search across live, movies and series
- Show/hide categories per server
- Multiple colour themes
- In-app update checker and one-tap installer
- Firestick, Chromecast and Google TV Streamer compatible

---

## Setup

1. Install the APK (enable *Install from unknown sources* if prompted)
2. Enter your Xtream Codes server URL, username and password
3. Orbital loads your channels, categories and EPG automatically

---

## Requirements

- Android 6.0 (API 23) or higher
- An active Xtream Codes IPTV subscription

---

## Building from Source

```bash
# Requires JDK 17+
JAVA_HOME="path/to/jdk17" ./gradlew assembleRelease
```

Create a `local.properties` file in the project root with your signing credentials (not committed):
```
sdk.dir=/path/to/android/sdk
ORBITAL_STORE_PASS=...
ORBITAL_KEY_PASS=...
```

---

## Privacy

- Credentials are stored locally on-device only
- No analytics, no ads, no tracking
- All data stays between your device and your IPTV provider

---

## Community

Questions, bugs, or feature requests — join the Discord:

**[discord.gg/WCdKAA2A7N](https://discord.gg/WCdKAA2A7N)**
