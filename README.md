# 📺 Orbital — Android App

A faithful recreation of the **Sky Digital Guide UI from the 1990s**, built as a fully functional Android IPTV app using **Xtream Codes** for live TV streaming.

---

## 🎨 UI Design

Inspired by the original Sky Digital EPG (Electronic Programme Guide) interface:

- **Deep navy blue** background (`#0D1B35`)
- **Cyan** (`#00CCFF`) and **gold/yellow** (`#FFCC00`) accent colours
- `sans-serif-condensed` font throughout — matching the 90s Sky aesthetic
- Numbered category menu (1–8) exactly matching the original Orbital layout
- Top navigation bar with TV GUIDE / BOX OFFICE / SERVICES / INTERACTIVE tabs

---

## 📂 Project Structure

```
Orbital/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/orbital/iptv/
│   │   ├── data/
│   │   │   ├── api/
│   │   │   │   ├── ApiClient.kt          # Retrofit setup
│   │   │   │   └── XtreamApiService.kt   # Xtream Codes endpoints
│   │   │   ├── model/
│   │   │   │   └── Models.kt             # Data classes + Sky category mapping
│   │   │   └── repository/
│   │   │       └── XtreamRepository.kt   # Data layer
│   │   ├── ui/
│   │   │   ├── login/
│   │   │   │   └── LoginActivity.kt      # Xtream login screen
│   │   │   ├── home/
│   │   │   │   ├── HomeActivity.kt       # Main Orbital UI
│   │   │   │   ├── HomeViewModel.kt      # Category + channel logic
│   │   │   │   └── ChannelAdapter.kt     # RecyclerView adapter
│   │   │   └── player/
│   │   │       └── PlayerActivity.kt     # ExoPlayer/Media3 live TV
│   │   └── utils/
│   │       └── PrefsManager.kt           # Credential storage
│   └── res/
│       ├── layout/
│       │   ├── activity_login.xml        # Login screen
│       │   ├── activity_home.xml         # Orbital main screen
│       │   ├── activity_player.xml       # Full-screen player
│       │   └── item_channel.xml          # Channel list row
│       └── values/
│           ├── colors.xml                # Sky UK colour palette
│           ├── strings.xml
│           └── themes.xml
```

---

## 🔧 Setup & Build

### Requirements
- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 34
- Kotlin 1.9+
- JDK 17

### Steps

1. **Open in Android Studio**
   ```
   File → Open → Select the Orbital folder
   ```

2. **Sync Gradle**
   Android Studio will prompt you — click **Sync Now**

3. **Build & Run**
   - Connect an Android device (API 21+) or start an emulator
   - Press **▶ Run** or `Shift+F10`

---

## 📡 Xtream Codes Configuration

On first launch, you'll see the **login screen** asking for:

| Field | Example |
|-------|---------|
| Server URL | `http://yourprovider.com:8080` |
| Username | `your_username` |
| Password | `your_password` |

The app calls:
- `GET /player_api.php?username=X&password=Y` — verify auth
- `GET /player_api.php?action=get_live_categories` — fetch categories
- `GET /player_api.php?action=get_live_streams` — fetch all channels

Stream URLs are built as:
```
{server}/live/{username}/{password}/{stream_id}.ts
```

---

## 📺 Orbital Category Mapping

The app automatically maps your provider's categories to Sky's iconic 8-slot menu:

| # | Sky Category | Provider Keywords Matched |
|---|-------------|--------------------------|
| 1 | TV GUIDE LISTINGS | *(header only)* |
| 2 | ENTERTAINMENT | entertainment, drama, comedy |
| 3 | MOVIES | movie, film, cinema |
| 4 | SPORTS | sport, football, cricket |
| 5 | NEWS & DOCUMENTARIES | news, documentary |
| 6 | CHILDREN | child, kid, cartoon, junior |
| 7 | MUSIC & SPECIALIST | music, radio, mtv |
| 8 | OTHER CHANNELS | *(everything else)* |

---

## 🎬 Player

- Powered by **Jetpack Media3 / ExoPlayer**
- Supports **HLS (.m3u8)** and **MPEG-TS (.ts)** streams
- Full-screen landscape playback
- Retro Sky-styled on-screen overlay with channel name and LIVE indicator
- Screen kept on during playback

---

## 🔒 Privacy

- Credentials are stored locally in `SharedPreferences` (not transmitted anywhere except your IPTV server)
- No analytics, no ads, no tracking

---

## 📝 Dependencies

| Library | Purpose |
|---------|---------|
| Retrofit2 + Gson | Xtream Codes API calls |
| OkHttp3 | HTTP client |
| Media3 ExoPlayer | Live stream playback |
| Glide | Channel logo loading |
| AndroidX Lifecycle | ViewModel + LiveData |
| Kotlin Coroutines | Async API calls |

---

*Designed with love for the golden era of Sky Digital. Press the red button.*  📡
