# Arnit Music Player v1.7.1

An Android music player built with Kotlin and Jetpack Compose.

## Features

- Offline music library and direct HTTPS audio stream playback
- Album art and selectable light/dark backgrounds
- Favorites, playlists, playback queue and folder browser
- Equalizer, Bass Boost and Sleep Timer
- Album and artist filters
- Recently Played list
- JSON backup export and restore
- User-initiated online lyrics lookup via LRCLIB

## Build information

- Application ID: `online.arnitcreation.musicplayer`
- Version name: `1.7.1`
- Version code: `14`
- Minimum Android: 8.0 (API 26)
- Compile SDK: 36
- Target SDK: 35
- Java/Kotlin target: 17

Open the project in Android Studio or a compatible Android Gradle environment and build the `:app:assembleDebug` task. A successful build in one environment does not guarantee reproducible builds; release builds must be independently verified.

## Privacy

Online lyrics lookup happens only when the user taps **Search online**. The app sends the current track title and artist, and album/duration when available, to LRCLIB over HTTPS. Local audio files are not uploaded. Backup files are created locally through Android's document picker.

## F-Droid status

This repository is source code for the project. It is **not yet approved or published on F-Droid**. Before submitting, verify all bundled assets and dependency licenses, confirm the source builds reproducibly with F-Droid's build server, and prepare and validate the official F-Droid metadata.

## License and contact

Copyright © 2026 Abhijit Das. Project-owned source code is licensed under GNU GPL-3.0; see [LICENSE](LICENSE). Third-party components and assets remain under their respective licenses.

Contact: abhijitdas021984@gmail.com
