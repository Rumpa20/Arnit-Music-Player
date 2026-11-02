# Arnit Music Player v1.7.0

New features:
- Automatic online lyrics lookup via LRCLIB, triggered only when the user taps Search online. The app sends the current track title and artist (and album/duration when available) over HTTPS. No account or API key is required. User can review/edit and save lyrics locally.
- Album and artist filters in the local music library.
- Recently Played tab (last 50 locally played tracks).
- Improved playlist management: remove individual tracks and clear the playlist.
- Settings page with JSON Export Backup and Restore Backup using Android document picker.

Existing features retained:
- Offline library and direct HTTPS audio stream playback
- Album art and selectable light/dark backgrounds
- Favorites, playlist and Media3 playback queue
- Equalizer, Bass Boost and Sleep Timer
- Folder browser, search, app logo and adaptive launcher icon

Package: `online.arnitcreation.musicplayer`
Minimum Android: 8.0 (API 26)

Privacy note: Online lyrics lookup is user-initiated. When requested, song title and artist (plus album and duration if available) are sent to LRCLIB over HTTPS. Library audio files are not uploaded. Backup files are created locally through Android's document picker.

F-Droid preparation note: This is an open-source-oriented source project, not yet F-Droid-ready. Before submission, choose and include a compatible open-source license, verify all dependency licenses and build reproducibility, provide source repository and metadata, and test release builds. No binary APK is included in this source ZIP.

This source has not been compiled or tested in the user's CodeOnTheGo environment. Build and test on-device before release.


## License and contact

Copyright © 2026 Abhijit Das. Project-owned source code: GNU GPL-3.0; see [LICENSE](LICENSE). Third-party components remain under their respective licenses. Contact: abhijitdas021984@gmail.com.
