package sample.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Slider
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.TabRowDefaults
import androidx.compose.material.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.rodio.RodioPlayer
import io.github.kdroidfilter.rodio.native.PlaybackCallback
import io.github.kdroidfilter.rodio.native.PlaybackEvent
import io.github.kdroidfilter.souvlaki.MediaControls
import io.github.kdroidfilter.souvlaki.MediaControlCallback
import io.github.kdroidfilter.souvlaki.MediaControlEventData
import io.github.kdroidfilter.souvlaki.MediaControlEventType
import io.github.kdroidfilter.souvlaki.MediaMetadata
import io.github.kdroidfilter.souvlaki.PlaybackStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private enum class SourceTab { File, Stream, MediaControls }

@Composable
fun App() {
    val player = remember { RodioPlayer() }
    val scope = rememberCoroutineScope()
    var activeTab by remember { mutableStateOf(SourceTab.File) }
    var filePath by remember { mutableStateOf("") }
    var streamUrl by remember { mutableStateOf("https://broadcast.adpronet.com/radio/6060/radio.mp3") }
    var playbackEvent by remember { mutableStateOf(PlaybackEvent.STOPPED) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf<Long?>(null) }
    var userSeekMs by remember { mutableStateOf<Long?>(null) }
    var seekable by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(1f) }

    // Souvlaki Media Controls state
    var mediaControlsInstance by remember { mutableStateOf<MediaControls?>(null) }
    var mediaControlsEnabled by remember { mutableStateOf(false) }
    var lastMediaEvent by remember { mutableStateOf<String?>(null) }
    var trackTitle by remember { mutableStateOf("Sample Track") }
    var trackArtist by remember { mutableStateOf("Sample Artist") }
    var trackAlbum by remember { mutableStateOf("Sample Album") }
    var currentPlaybackStatus by remember { mutableStateOf(PlaybackStatus.STOPPED) }

    val accentColor = Color(0xFF2563EB)
    val tabBackground = Color(0xFFEFF3FB)

    val callback = remember {
        object : PlaybackCallback {
            override fun onEvent(event: PlaybackEvent) {
                scope.launch { playbackEvent = event }
            }

            override fun onMetadata(key: String, value: String) {
                // No-op for minimal player.
            }

            override fun onError(message: String) {
                println("Playback error: $message")
                scope.launch { playbackEvent = PlaybackEvent.STOPPED }
            }
        }
    }

    DisposableEffect(player, callback) {
        player.setCallback(callback)
        onDispose {
            player.clearCallback()
            player.close()
        }
    }

    // Cleanup media controls on dispose
    DisposableEffect(Unit) {
        onDispose {
            mediaControlsInstance?.close()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            positionMs = player.getPositionMs()
            durationMs = player.getDurationMs()
            seekable = runCatching { player.isSeekable() }.getOrDefault(false)
            delay(200)
        }
    }

    val statusLabel = when (playbackEvent) {
        PlaybackEvent.CONNECTING -> "Connecting"
        PlaybackEvent.PLAYING -> "Playing"
        PlaybackEvent.PAUSED -> "Paused"
        PlaybackEvent.STOPPED -> "Stopped"
    }
    val progress = durationMs
        ?.takeIf { it > 0L }
        ?.let { positionMs.coerceAtMost(it).toFloat() / it.toFloat() }
    val displayPositionMs = userSeekMs ?: positionMs
    val hasSource = when (activeTab) {
        SourceTab.File -> filePath.isNotBlank()
        SourceTab.Stream -> streamUrl.isNotBlank()
        SourceTab.MediaControls -> true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BasicText("RodioKt - Minimal player", style = TextStyle(color = Color.Black))
            Spacer(modifier = Modifier.height(12.dp))

            TabRow(
                selectedTabIndex = activeTab.ordinal,
                backgroundColor = Color.Transparent,
                contentColor = accentColor,
                divider = {},
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        modifier = Modifier
                            .tabIndicatorOffset(tabPositions[activeTab.ordinal])
                            .padding(horizontal = 16.dp)
                            .height(3.dp),
                        color = accentColor,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tabBackground, RoundedCornerShape(999.dp)),
            ) {
                SourceTab.values().forEachIndexed { index, tab ->
                    val selected = activeTab.ordinal == index
                    Tab(
                        selected = selected,
                        onClick = { activeTab = tab },
                        selectedContentColor = accentColor,
                        unselectedContentColor = Color(0xFF6B7280),
                        text = {
                            Text(
                                text = when (tab) {
                                    SourceTab.File -> "File"
                                    SourceTab.Stream -> "Stream"
                                    SourceTab.MediaControls -> "Media Controls"
                                },
                                style = TextStyle(color = if (selected) accentColor else Color(0xFF6B7280)),
                            )
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            when (activeTab) {
                SourceTab.File -> {
                    BasicText("Local file", style = TextStyle(color = Color.Black))
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = filePath,
                            onValueChange = { filePath = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.Black),
                            modifier = Modifier
                                .weight(1f)
                                .background(Color(0xFFF2F2F2))
                                .padding(10.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { filePath = chooseLocalFile() ?: filePath }) {
                            BasicText("Browse")
                        }
                    }
                }
                SourceTab.Stream -> {
                    BasicText("Stream URL (http/https)", style = TextStyle(color = Color.Black))
                    Spacer(modifier = Modifier.height(8.dp))
                    BasicTextField(
                        value = streamUrl,
                        onValueChange = { streamUrl = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.Black),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF2F2F2))
                            .padding(10.dp),
                    )
                }
                SourceTab.MediaControls -> {
                    BasicText("Souvlaki Media Controls Test", style = TextStyle(color = Color.Black))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Enable/Disable controls
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                if (mediaControlsEnabled) {
                                    mediaControlsInstance?.close()
                                    mediaControlsInstance = null
                                    mediaControlsEnabled = false
                                    lastMediaEvent = null
                                } else {
                                    runCatching {
                                        val controls = MediaControls.create(
                                            dbusName = "rodiokt_sample",
                                            displayName = "RodioKt Sample"
                                        )
                                        controls.attach { event ->
                                            scope.launch {
                                                lastMediaEvent = when (event.eventType) {
                                                    MediaControlEventType.PLAY -> "Play"
                                                    MediaControlEventType.PAUSE -> "Pause"
                                                    MediaControlEventType.TOGGLE -> "Toggle"
                                                    MediaControlEventType.NEXT -> "Next"
                                                    MediaControlEventType.PREVIOUS -> "Previous"
                                                    MediaControlEventType.STOP -> "Stop"
                                                    MediaControlEventType.SEEK -> "Seek ${if (event.seekForward == true) "Forward" else "Backward"}"
                                                    MediaControlEventType.SEEK_BY -> "SeekBy ${event.seekOffsetSecs}s"
                                                    MediaControlEventType.SET_POSITION -> "SetPosition ${event.positionSecs}s"
                                                    MediaControlEventType.SET_VOLUME -> "SetVolume ${event.volume}"
                                                    MediaControlEventType.OPEN_URI -> "OpenUri ${event.uri}"
                                                    MediaControlEventType.RAISE -> "Raise"
                                                    MediaControlEventType.QUIT -> "Quit"
                                                }
                                            }
                                        }
                                        mediaControlsInstance = controls
                                        mediaControlsEnabled = true
                                    }.onFailure {
                                        println("Failed to create media controls: ${it.message}")
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = if (mediaControlsEnabled) Color(0xFF10B981) else accentColor
                            ),
                        ) {
                            BasicText(if (mediaControlsEnabled) "Disable Controls" else "Enable Controls")
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    if (mediaControlsEnabled) {
                        // Track metadata inputs
                        BasicText("Track Title", style = TextStyle(color = Color.Gray))
                        BasicTextField(
                            value = trackTitle,
                            onValueChange = { trackTitle = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.Black),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF2F2F2))
                                .padding(10.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        BasicText("Artist", style = TextStyle(color = Color.Gray))
                        BasicTextField(
                            value = trackArtist,
                            onValueChange = { trackArtist = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.Black),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF2F2F2))
                                .padding(10.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        BasicText("Album", style = TextStyle(color = Color.Gray))
                        BasicTextField(
                            value = trackAlbum,
                            onValueChange = { trackAlbum = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.Black),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF2F2F2))
                                .padding(10.dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // Update metadata button
                        Button(
                            onClick = {
                                mediaControlsInstance?.setMetadata(
                                    MediaMetadata(
                                        title = trackTitle.takeIf { it.isNotBlank() },
                                        artist = trackArtist.takeIf { it.isNotBlank() },
                                        album = trackAlbum.takeIf { it.isNotBlank() },
                                        durationSecs = 180.0
                                    )
                                )
                            },
                        ) {
                            BasicText("Update Metadata")
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        // Playback status buttons
                        BasicText("Set Playback Status:", style = TextStyle(color = Color.Gray))
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    currentPlaybackStatus = PlaybackStatus.PLAYING
                                    mediaControlsInstance?.setPlayback(PlaybackStatus.PLAYING)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = if (currentPlaybackStatus == PlaybackStatus.PLAYING) Color(0xFF10B981) else Color(0xFFEDEDED)
                                ),
                            ) {
                                BasicText("Playing")
                            }
                            Button(
                                onClick = {
                                    currentPlaybackStatus = PlaybackStatus.PAUSED
                                    mediaControlsInstance?.setPlayback(PlaybackStatus.PAUSED)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = if (currentPlaybackStatus == PlaybackStatus.PAUSED) Color(0xFFF59E0B) else Color(0xFFEDEDED)
                                ),
                            ) {
                                BasicText("Paused")
                            }
                            Button(
                                onClick = {
                                    currentPlaybackStatus = PlaybackStatus.STOPPED
                                    mediaControlsInstance?.setPlayback(PlaybackStatus.STOPPED)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = if (currentPlaybackStatus == PlaybackStatus.STOPPED) Color(0xFFEF4444) else Color(0xFFEDEDED)
                                ),
                            ) {
                                BasicText("Stopped")
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))

                        // Last received event
                        BasicText(
                            "Last Event: ${lastMediaEvent ?: "None"}",
                            style = TextStyle(color = Color.DarkGray)
                        )
                    } else {
                        BasicText(
                            "Click 'Enable Controls' to start media controls.\nOn Linux, you can use playerctl or your DE's media keys to test.",
                            style = TextStyle(color = Color.Gray)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            BasicText("Volume", style = TextStyle(color = Color.Black))
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = volume,
                onValueChange = { newVolume ->
                    volume = newVolume
                    runCatching { player.setVolume(newVolume.coerceIn(0f, 1f)) }
                        .onFailure { println("Volume error: ${it.message}") }
                },
                valueRange = 0f..1f,
                steps = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))

            BasicText("Status: $statusLabel", style = TextStyle(color = Color.Black))
            Spacer(modifier = Modifier.height(8.dp))
            val totalDuration = durationMs
            val durationLabel = totalDuration?.let { formatTime(it) } ?: "--:--"
            val canSeek = seekable && totalDuration != null && totalDuration > 0
            if (hasSource && playbackEvent != PlaybackEvent.STOPPED) {
                if (canSeek) {
                    val safeDuration = totalDuration
                    val sliderValue = displayPositionMs
                        .coerceIn(0, safeDuration)
                        .toFloat()
                    Slider(
                        value = sliderValue,
                        onValueChange = { newValue -> userSeekMs = newValue.toLong() },
                        valueRange = 0f..safeDuration.toFloat(),
                        modifier = Modifier.fillMaxWidth(),
                        onValueChangeFinished = {
                            userSeekMs?.let { target ->
                                scope.launch {
                                    runCatching { player.seekToMs(target) }
                                        .onFailure { println("Seek error: ${it.message}") }
                                }
                            }
                            userSeekMs = null
                        },
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    BasicText(
                        "${formatTime(displayPositionMs)} / $durationLabel",
                        style = TextStyle(color = Color.DarkGray),
                    )
                } else {
                    if (progress == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    BasicText(
                        "${formatTime(displayPositionMs)} / $durationLabel",
                        style = TextStyle(color = Color.DarkGray),
                    )
                }
            } else {
                BasicText("Load a file or URL to show progress", style = TextStyle(color = Color.DarkGray))
            }
            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.Center) {
                Button(
                    onClick = {
                        if (playbackEvent == PlaybackEvent.PAUSED) {
                            player.play()
                            return@Button
                        }
                        when (activeTab) {
                            SourceTab.File -> if (filePath.isNotBlank()) {
                                scope.launch {
                                    runCatching { player.playFileAsync(filePath, loop = false) }
                                        .onFailure { println("File error: ${it.message}") }
                                }
                            }
                            SourceTab.Stream -> if (streamUrl.isNotBlank()) {
                                scope.launch {
                                    runCatching { player.playUrlAsync(streamUrl, loop = false) }
                                        .onFailure { println("Stream error: ${it.message}") }
                                }
                            }
                            SourceTab.MediaControls -> {
                                // MediaControls tab doesn't use rodio player
                            }
                        }
                    },
                    enabled = hasSource,
                ) {
                    BasicText("Play")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { player.pause() },
                    enabled = playbackEvent == PlaybackEvent.PLAYING,
                ) {
                    BasicText("Pause")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { player.stop() },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFEDEDED)),
                ) {
                    BasicText("Stop")
                }
            }
        }
    }
}

private fun chooseLocalFile(): String? {
    val dialog = FileDialog(null as Frame?, "Choose an audio file", FileDialog.LOAD)
    dialog.isVisible = true
    val fileName = dialog.file ?: return null
    val directory = dialog.directory ?: return fileName
    return File(directory, fileName).absolutePath
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
