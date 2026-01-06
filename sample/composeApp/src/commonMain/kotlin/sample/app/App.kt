package sample.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.rodio.PlaybackCallback
import io.github.kdroidfilter.rodio.PlaybackEvent
import io.github.kdroidfilter.rodio.RodioPlayer
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun App() {
    val player = remember { RodioPlayer() }
    val scope = rememberCoroutineScope()
    var playbackEvent by remember { mutableStateOf(PlaybackEvent.Stopped) }
    val playbackCallback = remember {
        object : PlaybackCallback {
            override fun onEvent(event: PlaybackEvent) {
                println("Playback event: $event")
                scope.launch { playbackEvent = event }
            }

            override fun onMetadata(key: String, value: String) {
                println("Metadata: $key=$value")
            }

            override fun onError(message: String) {
                println("Playback error: $message")
                scope.launch { playbackEvent = PlaybackEvent.Stopped }
            }
        }
    }
    DisposableEffect(player, playbackCallback) {
        player.setCallback(playbackCallback)
        onDispose {
            player.clearCallback()
            player.close()
        }
    }

    val defaultStreamUrl = "https://broadcast.adpronet.com/radio/6060/radio.mp3"
    var streamUrl by remember { mutableStateOf(defaultStreamUrl) }
    var volume by remember { mutableStateOf(1f) }
    val isPlaying = playbackEvent == PlaybackEvent.Playing || playbackEvent == PlaybackEvent.Connecting
    val statusLabel = when (playbackEvent) {
        PlaybackEvent.Connecting -> "Connecting"
        PlaybackEvent.Playing -> "Playing"
        PlaybackEvent.Paused -> "Paused"
        PlaybackEvent.Stopped -> "Stopped"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BasicText("Rodio ready.")
            BasicText("Tap to play a 440Hz tone.")
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))

            BasicText("Playback: $statusLabel")
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
            if (isPlaying) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = 0f, modifier = Modifier.fillMaxWidth())
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(24.dp))

            BasicText("Stream URL")
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
            BasicTextField(
                value = streamUrl,
                onValueChange = { streamUrl = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.Black),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF2F2F2))
                    .padding(12.dp),
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
            BasicText("Volume ${(volume * 100).roundToInt()}%")
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = volume,
                onValueChange = { newVolume ->
                    volume = newVolume
                    player.setVolume(newVolume)
                },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth(),
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
            BasicText(
                "Play stream",
                modifier = Modifier.clickable {
                    scope.launch {
                        runCatching { player.playRadioAsync(streamUrl, playbackCallback) }
                            .onFailure { println("Stream error: ${it.message}") }
                    }
                },
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
            BasicText(
                "Play 440Hz tone",
                modifier = Modifier.clickable {
                    player.playSine(440f, 400)
                },
            )
        }
    }
}
