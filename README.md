# Arnit Music Player v2.0.0

Arnit Music Player is an Android music player with an offline library and an online discovery tab.

## What's new in v2.0.0

- Online Music Hub powered by the public Audius discovery API.
- Search independent tracks by song, artist, genre, or language keyword.
- Genre filters for Electronic, Hip-Hop/Rap, Rock, Pop, Jazz, and Classical.
- Play Audius tracks through the existing AndroidX Media3 player and media session.
- Retains direct HTTPS audio stream playback as an advanced option.
- Existing local library, folders, album/artist filters, Favorites, playlists, Recently Played, queue, equalizer, Bass Boost, Sleep Timer, lyrics lookup, background themes, and JSON backup/restore are retained.

## Build configuration

- Package/application ID: `online.arnitcreation.musicplayer`
- Minimum Android: 8.0 (API 26)
- Target SDK: 35
- Compile SDK: 36
- Version code: 15
- Version name: 2.0.0
- Java/Kotlin target: 17

## Online music provider

Online discovery uses the Audius public API (`https://api.audius.co`). The app sends the search term and selected genre to Audius when the user taps Search. Playback uses the stream endpoint returned by the Audius API.

Audius hosts independent creator uploads. The app does not scrape YouTube or Spotify, bypass DRM, or unlock paid catalogs. Track availability, regional access, and reuse permissions depend on the provider and the individual track. Streaming is not the same as permission to download, redistribute, or use a track commercially. The app does not promise access to every commercial song, language, or catalog.

## Privacy

- Local library scanning and playback are performed on the device.
- An online search sends the entered search text and selected genre to Audius over HTTPS.
- Playback requests the selected track stream from Audius over HTTPS.
- Lyrics search remains user-initiated and sends track title/artist metadata to LRCLIB over HTTPS.
- No Audius API secret or user account credential is embedded in the app.
- Online features require an internet connection and may use mobile data.

## Build in CodeOnTheGo

1. Extract the ZIP to a folder on your Android device.
2. Open CodeOnTheGo and choose **Open Project**.
3. Select the extracted `ArnitMusicPlayer-v2.0` folder (the folder containing `settings.gradle.kts`).
4. Wait for Gradle sync and dependency downloads to finish.
5. Build the debug APK using the app's Build/Gradle task for `:app:assembleDebug`.
6. Install and test the APK on your device.

If CodeOnTheGo reports an Android Gradle Plugin, Gradle, or Kotlin compatibility error, share the complete error log. Do not change signing keys or application ID when updating an existing installation.

## Release checklist

- Test local audio scanning and playback.
- Test Audius search and streaming on Wi-Fi and mobile data.
- Test playback controls, notification, lock-screen controls, and background playback.
- Test backup/restore, favorites, playlists, lyrics, equalizer, and sleep timer.
- Verify third-party API terms, track-specific licenses, dependency licenses, and release signing before distribution.

## Important build status

This ZIP is a source project, not an APK. It has not been compiled or tested in CodeOnTheGo in this environment. Build and test it on-device before publishing.
