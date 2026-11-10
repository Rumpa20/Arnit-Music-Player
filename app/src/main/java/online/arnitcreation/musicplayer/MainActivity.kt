package online.arnitcreation.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.audiofx.Equalizer
import android.media.audiofx.BassBoost
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

data class Song(
    val title: String,
    val artist: String,
    val uri: Uri,
    val albumArtUri: Uri?,
    val folder: String = "Music",
    val album: String = "Unknown album",
    val durationMs: Long = 0L
)

/** A track returned by the Audius public discovery API. */
data class OnlineTrack(
    val id: String,
    val title: String,
    val artist: String,
    val genre: String,
    val artworkUrl: String?,
    val durationSeconds: Long,
    val streamUrl: String,
    val permalink: String?
)

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        setContent {
            var dark by remember { mutableStateOf(false) }
            val controller = remember { mutableStateOf<MediaController?>(null) }
            LaunchedEffect(Unit) {
                controllerFuture?.addListener({
                    try { controller.value = controllerFuture?.get() } catch (_: Exception) { }
                }, ContextCompat.getMainExecutor(this@MainActivity))
            }
            ArnitTheme(dark) {
                MusicHome(dark, { dark = !dark }, controller.value)
            }
        }
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

@Composable
private fun ArnitTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicHome(dark: Boolean, onTheme: () -> Unit, player: MediaController?) {
    val context = LocalContext.current
    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var url by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    val prefs = remember { context.getSharedPreferences("arnit_music", android.content.Context.MODE_PRIVATE) }
    // State declarations must precede backup/restore callbacks that update them.
    var status by remember { mutableStateOf("Choose a song or add an online audio URL") }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
    var playlist by remember { mutableStateOf(prefs.getStringSet("playlist", emptySet())?.toSet() ?: emptySet()) }
    var recentlyPlayed by remember { mutableStateOf(prefs.getStringSet("recently_played", emptySet())?.toList() ?: emptyList()) }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            try {
                val json = JSONObject()
                prefs.all.forEach { (key, value) ->
                    when (value) {
                        is String -> json.put(key, value)
                        is Boolean -> json.put(key, value)
                        is Int -> json.put(key, value)
                        is Long -> json.put(key, value)
                        is Float -> json.put(key, value.toDouble())
                        is Set<*> -> json.put(key, JSONArray(value.filterIsInstance<String>()))
                    }
                }
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json.toString(2)) }
                status = "Backup exported successfully"
            } catch (e: Exception) { status = "Backup failed: ${e.message}" }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: "{}"
                val json = JSONObject(raw)
                val editor = prefs.edit().clear()
                json.keys().forEach { key ->
                    when (val value = json.get(key)) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Double -> editor.putFloat(key, value.toFloat())
                        is JSONArray -> editor.putStringSet(key, (0 until value.length()).map { value.getString(it) }.toSet())
                    }
                }
                editor.apply()
                favorites = prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
                playlist = prefs.getStringSet("playlist", emptySet())?.toSet() ?: emptySet()
                recentlyPlayed = prefs.getStringSet("recently_played", emptySet())?.toList() ?: emptyList()
                status = "Backup restored. Restart app if settings do not refresh."
            } catch (e: Exception) { status = "Restore failed: ${e.message}" }
        }
    }

    fun toggleSaved(key: String, uri: String) {
        val updated = if (uri in key.let { if (it == "favorites") favorites else playlist })
            (if (key == "favorites") favorites else playlist) - uri
        else (if (key == "favorites") favorites else playlist) + uri
        if (key == "favorites") favorites = updated else playlist = updated
        prefs.edit().putStringSet(key, updated).apply()
    }
    var showPlayer by remember { mutableStateOf(false) }
    var permissionGranted by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("Nothing playing") }
    var artist by remember { mutableStateOf("") }
    var currentArtUri by remember { mutableStateOf<Uri?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    var shuffle by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var showBackgroundDialog by remember { mutableStateOf(false) }
    var lightBg by remember { mutableIntStateOf(prefs.getInt("light_bg", 0).coerceIn(0, 2)) }
    var darkBg by remember { mutableIntStateOf(prefs.getInt("dark_bg", 0).coerceIn(0, 2)) }
    var searchQuery by remember { mutableStateOf("") }
    var onlineQuery by remember { mutableStateOf("") }
    var onlineTracks by remember { mutableStateOf(emptyList<OnlineTrack>()) }
    var onlineLoading by remember { mutableStateOf(false) }
    var onlineMessage by remember { mutableStateOf("Search independent music on Audius. Results stream from the provider.") }
    var onlineGenre by remember { mutableStateOf("All genres") }
    var selectedFolder by remember { mutableStateOf("All folders") }
    var selectedAlbum by remember { mutableStateOf("All albums") }
    var selectedArtist by remember { mutableStateOf("All artists") }
    var showSettings by remember { mutableStateOf(false) }
    var lyricsLoading by remember { mutableStateOf(false) }
    var lyricsStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var lyricsText by remember { mutableStateOf("") }
    var lyricsDraft by remember { mutableStateOf("") }
    var showSoundSettings by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var sleepJob by remember { mutableStateOf<Job?>(null) }
    var sleepRemaining by remember { mutableStateOf("") }
    var equalizer by remember { mutableStateOf<Equalizer?>(null) }
    var bassBoost by remember { mutableStateOf<BassBoost?>(null) }
    var eqEnabled by remember { mutableStateOf(false) }
    var bassEnabled by remember { mutableStateOf(false) }
    var soundEffectMessage by remember { mutableStateOf("") }
    var eqPreset by remember { mutableStateOf("Normal") }
    var eqBands by remember { mutableStateOf(listOf(0f, 0f, 0f, 0f, 0f)) }

    fun loadSongs() {
        val result = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = if (Build.VERSION.SDK_INT >= 29)
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION)
        else arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        context.contentResolver.query(collection, projection, selection, null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val folderCol = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) else -1
            val albumNameCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val durationCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val albumId = c.getLong(albumCol)
                val artUri = Uri.parse("content://media/external/audio/albumart/$albumId")
                result += Song(c.getString(titleCol) ?: "Unknown title",
                    c.getString(artistCol) ?: "Unknown artist",
                    Uri.withAppendedPath(collection, id.toString()), artUri,
                    if (folderCol >= 0) (c.getString(folderCol)?.trimEnd('/')?.substringAfterLast('/') ?: "Music") else "Music",
                    if (albumNameCol >= 0) (c.getString(albumNameCol) ?: "Unknown album") else "Unknown album",
                    if (durationCol >= 0) c.getLong(durationCol) else 0L)
            }
        }
        songs = result
        status = if (result.isEmpty()) "No music found on this device" else "${result.size} songs found"
    }

    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (granted) loadSongs() else status = "Music permission was not granted"
    }

    LaunchedEffect(Unit) {
        permissionGranted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        if (permissionGranted) loadSongs()
    }

    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                title = mediaItem?.mediaMetadata?.title?.toString() ?: "Unknown title"
                artist = mediaItem?.mediaMetadata?.artist?.toString() ?: ""
                currentArtUri = mediaItem?.mediaMetadata?.artworkUri
                duration = player.duration.coerceAtLeast(0L)
                position = player.currentPosition.coerceAtLeast(0L)
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(playbackState: Int) {
                duration = player.duration.coerceAtLeast(0L)
                position = player.currentPosition.coerceAtLeast(0L)
            }
            override fun onShuffleModeEnabledChanged(enabled: Boolean) { shuffle = enabled }
            override fun onRepeatModeChanged(mode: Int) { repeatMode = mode }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player) {
        while (true) {
            if (player != null && !dragging) {
                position = player.currentPosition.coerceAtLeast(0L)
                duration = player.duration.coerceAtLeast(0L)
                isPlaying = player.isPlaying
                player.currentMediaItem?.let {
                    title = it.mediaMetadata.title?.toString() ?: title
                    artist = it.mediaMetadata.artist?.toString() ?: artist
                    currentArtUri = it.mediaMetadata.artworkUri
                }
            }
            delay(500)
        }
    }

    fun playSong(index: Int) {
        val p = player ?: run { status = "Player is connecting. Try again shortly."; return }
        val items = songs.map { song ->
            MediaItem.Builder().setUri(song.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist)
                    .setArtworkUri(song.albumArtUri).build())
                .build()
        }
        p.setMediaItems(items, index, 0L)
        val recentUri = songs[index].uri.toString()
        recentlyPlayed = (listOf(recentUri) + recentlyPlayed.filterNot { it == recentUri }).take(50)
        prefs.edit().putStringSet("recently_played", recentlyPlayed.toSet()).apply()
        p.prepare()
        p.play()
        title = songs[index].title
        artist = songs[index].artist
        currentArtUri = songs[index].albumArtUri
        status = "Playing from your library"
        showPlayer = true
    }

    fun playUrl() {
        val p = player ?: run { status = "Player is connecting. Try again shortly."; return }
        val uri = Uri.parse(url.trim())
        if (uri.scheme == "https" && !uri.host.isNullOrBlank()) {
            val item = MediaItem.Builder().setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(uri.host ?: "Online stream").build()).build()
            p.setMediaItem(item)
            p.prepare()
            p.play()
            title = uri.host ?: "Online stream"
            artist = "Online audio"
            status = "Connecting to stream"
            showPlayer = true
        } else status = "Enter a valid HTTPS audio URL"
    }

    fun searchOnlineMusic() {
        val query = onlineQuery.trim()
        if (query.isBlank()) {
            onlineMessage = "Enter a song, artist, or genre to search."
            return
        }
        scope.launch {
            onlineLoading = true
            onlineMessage = "Searching Audius…"
            val result = withContext(Dispatchers.IO) {
                searchAudiusTracks(query, onlineGenre)
            }
            onlineLoading = false
            onlineTracks = result.first
            onlineMessage = result.second
        }
    }

    fun playOnlineTrack(track: OnlineTrack) {
        val p = player ?: run { status = "Player is connecting. Try again shortly."; return }
        val uri = Uri.parse(track.streamUrl)
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) {
            onlineMessage = "This provider returned an invalid stream URL."
            return
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setArtworkUri(track.artworkUrl?.let { Uri.parse(it) })
            .build()
        p.setMediaItem(MediaItem.Builder().setUri(uri).setMediaMetadata(metadata).build())
        p.prepare()
        p.play()
        title = track.title
        artist = track.artist
        currentArtUri = null
        status = "Streaming from Audius"
        showPlayer = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(
                            painter = painterResource(id = R.drawable.arnit_music_logo),
                            contentDescription = "Arnit Music Player logo",
                            modifier = Modifier.size(48.dp),
                            contentScale = ContentScale.Crop
                        )
                        Column {
                            Text("Arnit Music Player")
                            Text("Music, your way", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showBackgroundDialog = true }) {
                        Icon(Icons.Default.Wallpaper, "Choose background")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                    IconButton(onClick = onTheme) {
                        Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, "Toggle theme")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0; showPlayer = false },
                    icon = { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Library") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1; showPlayer = false },
                    icon = { Icon(Icons.Default.Podcasts, null) }, label = { Text("Online") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2; showPlayer = false },
                    icon = { Icon(Icons.Default.Favorite, null) }, label = { Text("Favorites") })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3; showPlayer = false },
                    icon = { Icon(Icons.Default.QueueMusic, null) }, label = { Text("Playlist") })
                NavigationBarItem(selected = tab == 4, onClick = { tab = 4; showPlayer = false },
                    icon = { Icon(Icons.Default.History, null) }, label = { Text("Recent") })
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(
                    id = if (dark) when (darkBg) {
                        0 -> R.drawable.bg_night
                        1 -> R.drawable.bg_forest_night
                        else -> R.drawable.bg_ocean_night
                    } else when (lightBg) {
                        0 -> R.drawable.bg_sunrise
                        1 -> R.drawable.bg_forest
                        else -> R.drawable.bg_sunset
                    }
                ),
                contentDescription = "Selected app background",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = if (dark) 0.55f else 0.48f
            )
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (showPlayer) {
                FullPlayer(
                    title, artist, currentArtUri, isPlaying, position, duration, shuffle, repeatMode,
                    onBack = { showPlayer = false },
                    onPlayPause = { if (player?.isPlaying == true) player.pause() else player?.play() },
                    onPrevious = { player?.seekToPreviousMediaItem() },
                    onNext = { player?.seekToNextMediaItem() },
                    onShuffle = { player?.shuffleModeEnabled = !(player?.shuffleModeEnabled ?: false) },
                    onSoundSettings = { showSoundSettings = true },
                    onSleepTimer = { showSleepTimer = true },
                    onLyrics = {
                        lyricsText = prefs.getString("lyrics_" + title, "") ?: ""
                        lyricsDraft = lyricsText
                        showLyrics = true
                    },
                    onQueue = { showQueue = true },
                    onRepeat = {
                        val next = when (player?.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                        player?.repeatMode = next
                    },
                    sliderValue = if (dragging) dragPosition else position.toFloat(),
                    onSliderChange = { dragging = true; dragPosition = it },
                    onSliderFinish = {
                        player?.seekTo(dragPosition.toLong())
                        dragging = false
                    }
                )
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clickable { showPlayer = true },
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text("NOW PLAYING", style = MaterialTheme.typography.labelMedium)
                        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (artist.isNotBlank()) artist else status, style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { player?.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, "Previous") }
                            IconButton(onClick = { if (player?.isPlaying == true) player.pause() else player?.play() }) {
                                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause")
                            }
                            IconButton(onClick = { player?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, "Next") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { showPlayer = true }) { Text("Open player") }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (tab == 0 || tab == 2 || tab == 3 || tab == 4) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(when (tab) { 2 -> "Favorites"; 3 -> "My Playlist"; 4 -> "Recently Played"; else -> "Your music" },
                            style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if (tab == 0) TextButton(onClick = { if (permissionGranted) loadSongs() else permissionLauncher.launch(permission) }) { Text("Refresh") }
                        if (tab == 3 && playlist.isNotEmpty()) TextButton(onClick = {
                            playlist = emptySet(); prefs.edit().putStringSet("playlist", emptySet()).apply()
                        }) { Text("Clear") }
                    }
                    if (!permissionGranted) {
                        Button(onClick = { permissionLauncher.launch(permission) }) { Icon(Icons.Default.FolderOpen, null); Text("Allow music access") }
                    } else if (songs.isEmpty()) {
                        Text("No audio files found. Add music to your phone and refresh.")
                    } else {
                        if (tab == 0) {
                            val folders = listOf("All folders") + songs.map { it.folder }.distinct().sorted()
                            var folderMenuExpanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(expanded = folderMenuExpanded, onExpandedChange = { folderMenuExpanded = !folderMenuExpanded }) {
                                OutlinedTextField(
                                    value = selectedFolder, onValueChange = {}, readOnly = true,
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    label = { Text("Browse folders") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = folderMenuExpanded) }
                                )
                                ExposedDropdownMenu(expanded = folderMenuExpanded, onDismissRequest = { folderMenuExpanded = false }) {
                                    folders.forEach { folder ->
                                        DropdownMenuItem(text = { Text(folder) }, onClick = {
                                            selectedFolder = folder
                                            folderMenuExpanded = false
                                        })
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                var albumMenu by remember { mutableStateOf(false) }
                                var artistMenu by remember { mutableStateOf(false) }
                                Box(Modifier.weight(1f)) {
                                    OutlinedButton(onClick = { albumMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                        Text(selectedAlbum, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    DropdownMenu(expanded = albumMenu, onDismissRequest = { albumMenu = false }) {
                                        (listOf("All albums") + songs.map { it.album }.distinct().sorted()).forEach { value ->
                                            DropdownMenuItem(text = { Text(value) }, onClick = { selectedAlbum = value; albumMenu = false })
                                        }
                                    }
                                }
                                Box(Modifier.weight(1f)) {
                                    OutlinedButton(onClick = { artistMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                        Text(selectedArtist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    DropdownMenu(expanded = artistMenu, onDismissRequest = { artistMenu = false }) {
                                        (listOf("All artists") + songs.map { it.artist }.distinct().sorted()).forEach { value ->
                                            DropdownMenuItem(text = { Text(value) }, onClick = { selectedArtist = value; artistMenu = false })
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = searchQuery, onValueChange = { searchQuery = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Search songs or artists") },
                                leadingIcon = { Icon(Icons.Default.Search, null) },
                                singleLine = true
                            )
                        }
                        val tabSongs = when (tab) {
                            2 -> songs.filter { it.uri.toString() in favorites }
                            3 -> songs.filter { it.uri.toString() in playlist }
                            4 -> songs.filter { it.uri.toString() in recentlyPlayed }
                            else -> songs
                        }
                        val folderSongs = if (tab == 0 && selectedFolder != "All folders")
                            tabSongs.filter { it.folder == selectedFolder } else tabSongs
                        val albumSongs = if (tab == 0 && selectedAlbum != "All albums") folderSongs.filter { it.album == selectedAlbum } else folderSongs
                        val artistSongs = if (tab == 0 && selectedArtist != "All artists") albumSongs.filter { it.artist == selectedArtist } else albumSongs
                        val visibleSongs = if (tab == 0 && searchQuery.isNotBlank())
                            artistSongs.filter { it.title.contains(searchQuery, true) || it.artist.contains(searchQuery, true) || it.album.contains(searchQuery, true) }
                            else artistSongs
                        if (visibleSongs.isEmpty()) {
                            Text(if (tab == 2) "No favorites yet. Tap the heart beside a song."
                                else if (tab == 3) "Your playlist is empty. Tap the playlist icon beside a song."
                                else "No audio files found. Add music to your phone and refresh.")
                        }
                        LazyColumn {
                            itemsIndexed(visibleSongs) { _, song ->
                                ListItem(
                                    headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text("${song.artist} • ${song.album}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingContent = { Icon(Icons.Default.MusicNote, null) },
                                    trailingContent = {
                                        Row {
                                            IconButton(onClick = { toggleSaved("favorites", song.uri.toString()) }) {
                                                Icon(if (song.uri.toString() in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorite")
                                            }
                                            IconButton(onClick = { toggleSaved("playlist", song.uri.toString()) }) {
                                                Icon(if (song.uri.toString() in playlist) Icons.Default.PlaylistRemove else Icons.Default.PlaylistAdd, if (song.uri.toString() in playlist) "Remove from playlist" else "Add to playlist")
                                            }
                                        }
                                    },
                                    modifier = Modifier.clickable { playSong(songs.indexOf(song)) }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                } else {
                    Text("Online Music Hub", style = MaterialTheme.typography.titleLarge)
                    Text("Discover independent music from Audius. Search by song, artist, genre, or language.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = onlineQuery,
                        onValueChange = { onlineQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search songs, artists, genres or languages") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { searchOnlineMusic() }, enabled = !onlineLoading) {
                                Icon(Icons.Default.Search, "Search online music")
                            }
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    ) {
                        listOf("All genres", "Electronic", "Hip-Hop/Rap", "Rock", "Pop", "Jazz", "Classical").forEach { genre ->
                            FilterChip(
                                selected = onlineGenre == genre,
                                onClick = { onlineGenre = genre },
                                label = { Text(genre) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { searchOnlineMusic() },
                        enabled = onlineQuery.isNotBlank() && !onlineLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (onlineLoading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.Search, null)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (onlineLoading) "Searching…" else "Search Audius")
                    }
                    Text(onlineMessage, style = MaterialTheme.typography.bodySmall)
                    if (onlineTracks.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(onlineTracks) { _, track ->
                                ListItem(
                                    headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = {
                                        Text(
                                            listOf(track.artist, track.genre.ifBlank { "Independent music" },
                                                if (track.durationSeconds > 0) formatTime(track.durationSeconds * 1000L) else "")
                                                .filter { it.isNotBlank() }.joinToString(" • "),
                                            maxLines = 2, overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    leadingContent = { Icon(Icons.Default.MusicNote, null) },
                                    trailingContent = {
                                        IconButton(onClick = { playOnlineTrack(track) }) {
                                            Icon(Icons.Default.PlayCircle, "Play ${track.title}")
                                        }
                                    },
                                    modifier = Modifier.clickable { playOnlineTrack(track) }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Provider: Audius • Independent creator uploads • Internet connection required",
                        style = MaterialTheme.typography.labelSmall)
                    Text("Some tracks may be unavailable by region or creator settings. Availability and reuse rights depend on each track and its license.",
                        style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(10.dp))
                    Text("Direct HTTPS stream (advanced)", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Direct audio stream URL") }, singleLine = true
                    )
                    TextButton(onClick = { playUrl() }, enabled = url.isNotBlank()) {
                        Icon(Icons.Default.PlayArrow, null); Text("Play direct stream")
                    }
                }
            }
        }
        }
    }
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Arnit Music Player Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Library and playback preferences are stored locally on this device.")
                    OutlinedButton(onClick = { backupLauncher.launch("arnit-music-backup.json") }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Backup, null); Spacer(Modifier.width(8.dp)); Text("Export backup")
                    }
                    OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Restore, null); Spacer(Modifier.width(8.dp)); Text("Restore backup")
                    }
                    Text("Online lyrics use LRCLIB over HTTPS only when you tap Search online. Song title and artist are sent to LRCLIB. No account or API key is required.", style = MaterialTheme.typography.bodySmall)
                    Text("Arnit Music Player · Open-source project", style = MaterialTheme.typography.labelMedium)
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Done") } }
        )
    }
    if (showLyrics) {
        AlertDialog(
            onDismissRequest = { showLyrics = false },
            title = { Text("Lyrics · $title", maxLines = 2) },
            text = {
                Column(Modifier.heightIn(max = 520.dp)) {
                    Text("Search LRCLIB online or paste lyrics manually. Lyrics are saved on this device.")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                lyricsLoading = true
                                lyricsStatus = "Searching LRCLIB…"
                                val result = withContext(Dispatchers.IO) {
                                    fetchLrclibLyrics(title, artist, songs.firstOrNull { it.title == title && it.artist == artist }?.album, (duration / 1000L).takeIf { it in 1..3600 })
                                }
                                lyricsLoading = false
                                if (result.first.isNotBlank()) {
                                    lyricsDraft = result.first
                                    lyricsStatus = if (result.second) "Synced lyrics found. Review and save." else "Lyrics found. Review and save."
                                } else {
                                    lyricsStatus = "No lyrics found. Check the song title/artist or paste lyrics manually."
                                }
                            }
                        }, enabled = !lyricsLoading && title != "Nothing playing") {
                            if (lyricsLoading) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }
                            Text(if (lyricsLoading) "Searching" else "Search online")
                        }
                        TextButton(onClick = { lyricsDraft = ""; lyricsStatus = "Paste lyrics in the box below." }) { Text("Clear") }
                    }
                    if (lyricsStatus.isNotBlank()) Text(lyricsStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = lyricsDraft,
                        onValueChange = { lyricsDraft = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 340.dp),
                        placeholder = { Text("Lyrics will appear here…") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putString("lyrics_" + title, lyricsDraft).apply()
                    lyricsText = lyricsDraft
                    lyricsStatus = "Saved on this device"
                    showLyrics = false
                }, enabled = lyricsDraft.isNotBlank()) { Text("Save lyrics") }
            },
            dismissButton = { TextButton(onClick = { showLyrics = false }) { Text("Close") } }
        )
    }
    if (showQueue) {
        AlertDialog(
            onDismissRequest = { showQueue = false },
            title = { Text("Playing Queue") },
            text = {
                Column(Modifier.heightIn(max = 420.dp)) {
                    val count = player?.mediaItemCount ?: 0
                    if (count == 0) Text("Queue is empty")
                    else LazyColumn {
                        items(count) { index ->
                            val item = player?.getMediaItemAt(index)
                            val itemTitle = item?.mediaMetadata?.title?.toString() ?: "Track ${index + 1}"
                            ListItem(
                                headlineContent = { Text(itemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(if (index == (player?.currentMediaItemIndex ?: -1)) "Now playing" else "Queued") },
                                trailingContent = {
                                    Row {
                                        IconButton(enabled = index > 0, onClick = { player?.moveMediaItem(index, index - 1) }) {
                                            Icon(Icons.Default.KeyboardArrowUp, "Move up")
                                        }
                                        IconButton(enabled = index < count - 1, onClick = { player?.moveMediaItem(index, index + 1) }) {
                                            Icon(Icons.Default.KeyboardArrowDown, "Move down")
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showQueue = false }) { Text("Done") } }
        )
    }
    if (showSoundSettings) {
        AlertDialog(
            onDismissRequest = { showSoundSettings = false },
            title = { Text("Sound Settings") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("5-Band Equalizer", Modifier.weight(1f))
                        Switch(checked = eqEnabled, onCheckedChange = { enabled ->
                            if (!enabled) {
                                equalizer?.release()
                                equalizer = null
                                eqEnabled = false
                                soundEffectMessage = ""
                            } else {
                                try {
                                    val session = PlaybackService.audioSessionId
                                    check(player?.isPlaying == true && session > 0) {
                                        "Play a song first, wait a moment, then turn Equalizer on."
                                    }
                                    equalizer?.release()
                                    equalizer = Equalizer(0, session).apply { this.enabled = true }
                                    eqEnabled = true
                                    soundEffectMessage = ""
                                } catch (e: Exception) {
                                    eqEnabled = false
                                    soundEffectMessage = e.message ?: "Equalizer is not supported by this device."
                                }
                            }
                        })
                    }
                    Text("Preset")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Normal", "Pop", "Rock", "Jazz", "Classical").forEach { preset ->
                            FilterChip(selected = eqPreset == preset, onClick = {
                                eqPreset = preset
                                val values = when (preset) {
                                    "Pop" -> listOf(0.35f, 0.15f, 0f, 0.2f, 0.35f)
                                    "Rock" -> listOf(0.4f, 0.2f, -0.1f, 0.25f, 0.4f)
                                    "Jazz" -> listOf(0.25f, 0.1f, 0.05f, 0.2f, 0.25f)
                                    "Classical" -> listOf(0.15f, 0.1f, 0f, 0.15f, 0.25f)
                                    else -> listOf(0f, 0f, 0f, 0f, 0f)
                                }
                                eqBands = values
                                applyEqBands(equalizer, values)
                            }, label = { Text(preset) })
                        }
                    }
                    val labels = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")
                    labels.forEachIndexed { index, label ->
                        Text(label, style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = eqBands[index], onValueChange = { value ->
                                eqBands = eqBands.toMutableList().also { it[index] = value }
                                applyEqBands(equalizer, eqBands)
                                eqPreset = "Custom"
                            }, valueRange = -1f..1f, enabled = eqEnabled
                        )
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Bass Boost")
                            Text("Device-supported audio enhancement", style = MaterialTheme.typography.labelSmall)
                        }
                        Switch(checked = bassEnabled, onCheckedChange = { enabled ->
                            if (!enabled) {
                                bassBoost?.release()
                                bassBoost = null
                                bassEnabled = false
                                soundEffectMessage = ""
                            } else {
                                try {
                                    val session = PlaybackService.audioSessionId
                                    check(player?.isPlaying == true && session > 0) {
                                        "Play a song first, wait a moment, then turn Bass Boost on."
                                    }
                                    bassBoost?.release()
                                    bassBoost = BassBoost(0, session).apply {
                                        setStrength(700.toShort())
                                        this.enabled = true
                                    }
                                    bassEnabled = true
                                    soundEffectMessage = ""
                                } catch (e: Exception) {
                                    bassEnabled = false
                                    soundEffectMessage = e.message ?: "Bass Boost is not supported by this device."
                                }
                            }
                        })
                    }
                    if (soundEffectMessage.isNotBlank()) {
                        Text(soundEffectMessage, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Play a song and wait until it starts before enabling audio effects.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSoundSettings = false }) { Text("Done") } }
        )
    }
    if (showSleepTimer) {
        AlertDialog(
            onDismissRequest = { showSleepTimer = false },
            title = { Text("Sleep Timer") },
            text = {
                Column {
                    Text("Stop playback after:")
                    listOf(15, 30, 45, 60).forEach { minutes ->
                        FilledTonalButton(onClick = {
                            sleepJob?.cancel()
                            sleepRemaining = "$minutes min"
                            sleepJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                delay(minutes * 60_000L)
                                player?.pause()
                                sleepRemaining = ""
                                sleepJob = null
                            }
                            showSleepTimer = false
                        }, modifier = Modifier.fillMaxWidth()) { Text("Stop after $minutes minutes") }
                    }
                    if (sleepRemaining.isNotBlank()) {
                        Text("Active timer: $sleepRemaining", color = MaterialTheme.colorScheme.primary)
                    }
                    OutlinedButton(onClick = {
                        sleepJob?.cancel(); sleepJob = null; sleepRemaining = ""; showSleepTimer = false
                    }, modifier = Modifier.fillMaxWidth()) { Text("Cancel timer") }
                }
            },
            confirmButton = { TextButton(onClick = { showSleepTimer = false }) { Text("Close") } }
        )
    }
    if (showBackgroundDialog) {
        val choices = if (dark) listOf("Moonlit Sky", "Night Forest", "Blue Ocean")
            else listOf("Sunrise Mountains", "Green Forest", "Sunset Beach")
        AlertDialog(
            onDismissRequest = { showBackgroundDialog = false },
            title = { Text(if (dark) "Dark theme background" else "Light theme background") },
            text = {
                Column {
                    choices.forEachIndexed { index, label ->
                        TextButton(
                            onClick = {
                                if (dark) {
                                    darkBg = index
                                    prefs.edit().putInt("dark_bg", index).apply()
                                } else {
                                    lightBg = index
                                    prefs.edit().putInt("light_bg", index).apply()
                                }
                                showBackgroundDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                (if ((if (dark) darkBg else lightBg) == index) "✓  " else "") + label,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBackgroundDialog = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun FullPlayer(
    title: String, artist: String, artworkUri: Uri?, playing: Boolean, position: Long, duration: Long,
    shuffle: Boolean, repeatMode: Int, onBack: () -> Unit, onPlayPause: () -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit,
    onSoundSettings: () -> Unit, onSleepTimer: () -> Unit,
    onLyrics: () -> Unit, onQueue: () -> Unit,
    sliderValue: Float, onSliderChange: (Float) -> Unit, onSliderFinish: () -> Unit
) {
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, "Back") }
            Text("NOW PLAYING", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Icon(Icons.Default.MoreVert, "More")
        }
        AlbumArtwork(artworkUri = artworkUri, modifier = Modifier.fillMaxWidth().aspectRatio(0.88f))
        Column(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist.ifBlank { "Unknown artist" }, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.fillMaxWidth()) {
            Slider(
                value = sliderValue.coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
                onValueChange = onSliderChange,
                onValueChangeFinished = onSliderFinish,
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position), style = MaterialTheme.typography.labelSmall)
                Text(formatTime(duration), style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onShuffle) {
                Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Previous", modifier = Modifier.size(36.dp)) }
            FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(68.dp)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", modifier = Modifier.size(38.dp))
            }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Next", modifier = Modifier.size(36.dp)) }
            IconButton(onClick = onRepeat) {
                Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    "Repeat", tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            OutlinedButton(onClick = onSoundSettings) {
                Icon(Icons.Default.Equalizer, null); Spacer(Modifier.width(6.dp)); Text("Sound Settings")
            }
            OutlinedButton(onClick = onSleepTimer) {
                Icon(Icons.Default.Timer, null); Spacer(Modifier.width(6.dp)); Text("Sleep Timer")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onLyrics, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Article, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Lyrics")
            }
            OutlinedButton(onClick = onQueue, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.QueueMusic, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Queue")
            }
        }
    }
}

/**
 * Audius public discovery search. The API supplies the stream endpoint; no scraping,
 * user account, or embedded API secret is used by this client.
 */
private fun normalizeMusicSearchText(value: String): String {
    val normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(java.util.Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
    return normalized.replace(Regex("\\s+"), " ")
}

private fun musicSearchRelevance(query: String, title: String, artist: String, genre: String): Int {
    val q = normalizeMusicSearchText(query)
    val t = normalizeMusicSearchText(title)
    val a = normalizeMusicSearchText(artist)
    val g = normalizeMusicSearchText(genre)
    if (q.isBlank()) return 0

    var score = 0
    if (t == q) score += 120
    else if (t.startsWith(q)) score += 85
    else if (t.contains(q)) score += 70
    if (a == q) score += 95
    else if (a.startsWith(q)) score += 65
    else if (a.contains(q)) score += 50
    if (g == q || g.contains(q)) score += 20

    val queryWords = q.split(" ").filter { it.length > 1 }.toSet()
    if (queryWords.isNotEmpty()) {
        val titleWords = t.split(" ").toSet()
        val artistWords = a.split(" ").toSet()
        val genreWords = g.split(" ").toSet()
        val titleHits = queryWords.count { it in titleWords }
        val artistHits = queryWords.count { it in artistWords }
        val genreHits = queryWords.count { it in genreWords }
        score += titleHits * 18 + artistHits * 12 + genreHits * 3
        if (titleHits == queryWords.size) score += 35
        if (artistHits == queryWords.size) score += 25
        val allWords = titleWords + artistWords + genreWords
        if (queryWords.all { word -> allWords.any { it.startsWith(word) || word.startsWith(it) && it.length >= 3 } }) {
            score += 12
        }
    }
    return score
}

/**
 * Audius public discovery search. Results are locally ranked by title and artist
 * relevance so exact song-title matches appear before broad keyword matches.
 */
private fun searchAudiusTracks(query: String, genre: String): Pair<List<OnlineTrack>, String> {
    var connection: HttpURLConnection? = null
    return try {
        val cleanQuery = query.trim().replace(Regex("\\s+"), " ")
        val encodedQuery = java.net.URLEncoder.encode(cleanQuery, "UTF-8")
        val encodedGenre = java.net.URLEncoder.encode(genre, "UTF-8")
        val genrePart = if (genre == "All genres") "" else "&genre=$encodedGenre"
        val endpoint = "https://api.audius.co/v1/tracks/search?query=$encodedQuery$genrePart&limit=50&app_name=ArnitMusicPlayer"
        connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 12000
        connection.readTimeout = 18000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "ArnitMusicPlayer/2.0.2")
        val code = connection.responseCode
        if (code !in 200..299) {
            return emptyList<OnlineTrack>() to "Audius search failed (HTTP $code). Please try again later."
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
        val ranked = mutableListOf<Pair<Int, OnlineTrack>>()
        val seenIds = mutableSetOf<String>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: continue
            if (!seenIds.add(id)) continue
            val title = item.optString("title", "Untitled").trim().ifBlank { "Untitled" }
            val user = item.optJSONObject("user")
            val artist = user?.optString("name", "Unknown artist")?.takeIf { it.isNotBlank() } ?: "Unknown artist"
            val artwork = item.optJSONObject("artwork")?.optString("150x150")
                ?.takeIf { it.startsWith("https://") }
            val duration = item.optLong("duration", 0L)
            val stream = "https://api.audius.co/v1/tracks/${java.net.URLEncoder.encode(id, "UTF-8")}/stream?app_name=ArnitMusicPlayer"
            val track = OnlineTrack(
                id = id,
                title = title,
                artist = artist,
                genre = item.optString("genre", ""),
                artworkUrl = artwork,
                durationSeconds = duration,
                streamUrl = stream,
                permalink = item.optString("permalink").takeIf { it.startsWith("https://") }
            )
            ranked.add(musicSearchRelevance(cleanQuery, title, artist, track.genre) to track)
        }
        val sorted = ranked.sortedWith(
            compareByDescending<Pair<Int, OnlineTrack>> { it.first }
                .thenBy { normalizeMusicSearchText(it.second.title) }
        ).map { it.second }
        val message = if (sorted.isEmpty()) {
            "No tracks found. Try the song title, artist name, or a shorter keyword."
        } else {
            "${sorted.size} tracks found. Best title and artist matches are shown first."
        }
        sorted to message
    } catch (e: Exception) {
        emptyList<OnlineTrack>() to "Could not reach Audius: ${e.message ?: "network error"}"
    } finally {
        connection?.disconnect()
    }
}

private fun cleanLyricsQueryTitle(raw: String): String {
    var value = raw.substringAfterLast('/').substringAfterLast('\\')
    value = value.replace(Regex("(?i)\\.(mp3|m4a|aac|flac|ogg|opus|wav|wma)$"), "")
    value = value.replace(Regex("(?i)^\\s*\\d{1,3}\\s*[-._)]+\\s*"), "")
    value = value.replace(Regex("(?i)\\b(full\\s*mp3|official\\s*(audio|video)|lyrics?|audio|video|320kbps|128kbps)\\b"), " ")
    value = value.replace(Regex("[_]+"), " ").replace(Regex("\\s+"), " ").trim(' ', '-', '_', '.')
    return value
}

private fun fetchLrclibLyrics(title: String, artist: String, album: String?, durationSeconds: Long?): Pair<String, Boolean> {
    val cleanTitle = cleanLyricsQueryTitle(title)
    if (cleanTitle.isBlank()) return "" to false

    fun request(path: String): String? {
        val connection = (URL("https://lrclib.net$path").openConnection() as HttpURLConnection)
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.setRequestProperty("User-Agent", "ArnitMusicPlayer/2.0.0 (https://arnitcreation.online)")
        return try {
            val code = connection.responseCode
            if (code == 200) connection.inputStream.bufferedReader().use { it.readText() } else null
        } finally { connection.disconnect() }
    }

    fun usableArtist(value: String): Boolean {
        val a = value.trim()
        return a.isNotBlank() && !a.equals("Unknown artist", true) &&
            !a.contains(".in", true) && !a.contains("www.", true) &&
            !a.contains("http", true) && !a.contains("z fama", true)
    }

    return try {
        val cleanArtist = artist.trim()
        val artistOk = usableArtist(cleanArtist)
        val queries = mutableListOf<Pair<String, String?>>()
        if (artistOk) queries.add(cleanTitle to cleanArtist)
        queries.add(cleanTitle to null)

        val candidates = mutableListOf<JSONObject>()
        for ((track, by) in queries) {
            val params = buildString {
                append("track_name=${Uri.encode(track)}")
                if (!by.isNullOrBlank()) append("&artist_name=${Uri.encode(by)}")
            }
            val search = request("/api/search?$params")
            if (!search.isNullOrBlank()) {
                val array = JSONArray(search)
                for (i in 0 until array.length()) candidates.add(array.getJSONObject(i))
            }
            if (candidates.any {
                it.optString("trackName").equals(cleanTitle, true) &&
                    (it.optString("syncedLyrics").isNotBlank() || it.optString("plainLyrics").isNotBlank())
            }) break
        }

        val normalizedTarget = cleanTitle.lowercase().replace(Regex("[^a-z0-9]+"), "")
        val best = candidates.distinctBy { it.optString("id") }.maxByOrNull { item ->
            val candidateTitle = item.optString("trackName").lowercase().replace(Regex("[^a-z0-9]+"), "")
            var score = 0
            if (candidateTitle == normalizedTarget) score += 100
            else if (candidateTitle.contains(normalizedTarget) || normalizedTarget.contains(candidateTitle)) score += 35
            if (item.optString("syncedLyrics").isNotBlank()) score += 20
            if (item.optString("plainLyrics").isNotBlank()) score += 10
            if (artistOk && item.optString("artistName").equals(cleanArtist, true)) score += 15
            score
        }
        val record = best ?: run {
            // Last fallback: LRCLIB exact lookup without unreliable artist/album/duration metadata.
            val params = "track_name=${Uri.encode(cleanTitle)}"
            val exact = request("/api/get?$params") ?: return "" to false
            JSONObject(exact)
        }
        val synced = record.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        val plain = record.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        (synced ?: plain ?: "") to (synced != null)
    } catch (_: Exception) { "" to false }
}

private fun applyEqBands(eq: Equalizer?, values: List<Float>) {
    if (eq == null) return
    try {
        val range = eq.bandLevelRange
        val count = minOf(eq.numberOfBands.toInt(), values.size)
        for (i in 0 until count) {
            val level = (range[0] + ((values[i] + 1f) / 2f) * (range[1] - range[0])).toInt()
            eq.setBandLevel(i.toShort(), level.toShort())
        }
    } catch (_: Exception) { }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}


@Composable
private fun AlbumArtwork(artworkUri: Uri?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, artworkUri) {
        value = null
        if (artworkUri != null) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(artworkUri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                } catch (_: Exception) { null }
            }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Blurred album art background",
                modifier = Modifier.fillMaxSize().then(
                    Modifier.blur(28.dp)
                ),
                contentScale = ContentScale.Crop,
                alpha = 0.55f
            )
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f),
                shape = RoundedCornerShape(28.dp)
            ) {}
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Album cover",
                modifier = Modifier.fillMaxSize(0.82f),
                contentScale = ContentScale.Crop
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Album, null, modifier = Modifier.size(150.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}
