# Sonora: offline-first music player for Android

Kotlin · Jetpack Compose · Media3 (ExoPlayer) · Room · Glance widgets. Min Android 8.0, targets Android 16.

## What's in this first version

**Playback engine** (`playback/PlaybackService.kt`)
- Media3 ExoPlayer in a `MediaLibraryService`: background play, media notification with a ❤ favourite button, lock screen, headset/Bluetooth buttons, **Android Auto** browsing, Wear OS controls
- Gapless playback, audio focus, pauses when headphones are unplugged
- **Hi-Res pipeline**: 32-bit float output keeps 24-bit / 96–192 kHz files intact
- **Bit-perfect USB DAC** mode (Android 14+)
- Real source format read from FLAC/WAV headers → "Hi-Res · FLAC · 24-bit / 96 kHz" badge
- Shows the actual output format (float/16-bit, sample rate, offload)
- Remembers the queue and position across restarts; Bluetooth "play" resumes the last session
- Sleep timer with a 10-second fade-out, or "stop at end of song"
- Playback speed and pitch, skip silence, battery-saver audio offload

**Bluetooth & LDAC** (`playback/AudioOutputMonitor.kt`)
- Detects speaker / wired / Bluetooth / USB DAC and the device name
- Reads the negotiated Bluetooth codec (LDAC 990/660/330 kbps, aptX HD, AAC, SBC, LC3…) where Android allows it
- Links straight to Developer options to set LDAC to 990 kbps / 96 kHz / 24-bit.
  *No app can force LDAC: the phone and headphones negotiate it. What an app can do is feed it an untouched hi-res stream, which Sonora does.*

**Equalizer & effects** (`playback/AudioEffectsController.kt`)
- Device equalizer bands + presets, bass boost, virtualizer, loudness enhancer
- Exposes the audio session to Wavelet / Dolby / system EQs

**Lyrics** (`lyrics/Lyrics.kt`)
- Time-synced lyrics with auto-scroll, tap a line to seek, ±0.5 s offset correction
- Sources, in order: saved, embedded tags (ID3 USLT/TXXX, FLAC Vorbis), sidecar `.lrc`, then **LRCLIB** online (free, no key)
- Automatic matching by title + artist + album + duration, with title cleanup ("Remastered", "feat."…)
- "Match lyrics" dialog to pick a different result manually, and import of `.lrc` files

**Library & discovery**
- Songs / Albums / Artists / Playlists / Genres / Folders, sorting, auto-rescan when files change
- Home: daily "Made for you" mixes, Jump back in, Recently added, Your artists, On repeat, Rediscover
- Smart playlists: Favorites, Most played, Recently played, Recently added, Never played
- Playlists with drag-to-reorder; play-count tracking
- Accent-insensitive multi-word instant search
- "Open with Sonora" for audio files from file managers

**Look & feel**
- Material You colours, light/dark/system, pure-black AMOLED mode
- Now Playing tinted by album-art colours, blurred artwork backdrop, swipe the cover to skip
- Queue sheet with drag-to-reorder, remove and clear-upcoming

**Widgets** (`widget/Widgets.kt`)
- *Sonora player*: resizable compact, wide and large layouts with controls
- *Sonora cover*: artwork tile with play button

## Build & install

Open the folder in Android Studio, or from a terminal:

```powershell
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

`assembleRelease` produces a shrunk ~3.5 MB APK, signed with the debug key. Replace the signing config in `app/build.gradle.kts` before publishing.

## Roadmap

1. **YouTube Music**: needs a decision between an unofficial full client and the official API only (see below)
2. ReplayGain / volume normalisation, crossfade
3. Tag editor, embedded-art extraction for files without MediaStore art
4. Lyrics widget, Last.fm scrobbling, Chromecast
5. Library backup/restore of playlists and stats

### YouTube Music: the two options

| | Unofficial client (InnerTube + NewPipe Extractor, like ViMusic/Metrolist) | Official YouTube Data API |
|---|---|---|
| Stream the YT Music catalogue in-app | ✅ background, audio-only | ❌ (only the official embedded video player) |
| Your library, likes, playlists | ✅ via sign-in | ✅ read playlists/likes |
| Play Store | ❌ violates YouTube ToS; sideload only | ✅ |
| Stability | Breaks when YouTube changes; needs updates | Stable |
