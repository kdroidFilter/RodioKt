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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private enum class SourceTab { File, Stream }

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
                                text = if (tab == SourceTab.File) "File" else "Stream",
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
            }
            Spacer(modifier = Modifier.height(16.dp))

            BasicText("Status: $statusLabel", style = TextStyle(color = Color.Black))
            Spacer(modifier = Modifier.height(8.dp))
            val totalDuration = durationMs
            val durationLabel = totalDuration?.let { formatTime(it) } ?: "--:--"
            if (hasSource && playbackEvent != PlaybackEvent.STOPPED) {
                if (activeTab == SourceTab.File && seekable && totalDuration != null && totalDuration > 0) {
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
