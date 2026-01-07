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
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.LinearProgressIndicator
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

@Composable
fun App() {
    val player = remember { RodioPlayer() }
    val scope = rememberCoroutineScope()
    var filePath by remember { mutableStateOf("") }
    var playbackEvent by remember { mutableStateOf(PlaybackEvent.STOPPED) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf<Long?>(null) }

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
            BasicText("RodioKt - Lecteur local", style = TextStyle(color = Color.Black))
            Spacer(modifier = Modifier.height(16.dp))

            BasicText("Fichier audio", style = TextStyle(color = Color.Black))
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
                    BasicText("Parcourir")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            BasicText("Statut: $statusLabel", style = TextStyle(color = Color.Black))
            Spacer(modifier = Modifier.height(8.dp))
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
            }
            Spacer(modifier = Modifier.height(6.dp))
            val durationLabel = durationMs?.let { formatTime(it) } ?: "--:--"
            BasicText(
                "${formatTime(positionMs)} / $durationLabel",
                style = TextStyle(color = Color.DarkGray),
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.Center) {
                Button(
                    onClick = {
                        if (filePath.isNotBlank()) {
                            player.playFile(filePath, loop = false)
                        }
                    },
                    enabled = filePath.isNotBlank(),
                ) {
                    BasicText("Lire")
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
    val dialog = FileDialog(null as Frame?, "Choisir un fichier audio", FileDialog.LOAD)
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
