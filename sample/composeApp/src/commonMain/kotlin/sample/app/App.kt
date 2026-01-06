package sample.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.rodio.RodioPlayer

@Composable
fun App() {
    val player =  RodioPlayer()
    DisposableEffect(player) {
        onDispose { player.close() }
    }

    val message = "Rodio ready.\nTap to play a 440Hz tone."


    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            message,
            modifier = Modifier
                .padding(16.dp)
                .clickable {
                    player.playSine(440f, 400)
                },
        )
    }
}
