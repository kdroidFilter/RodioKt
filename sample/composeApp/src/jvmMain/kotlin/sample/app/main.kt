import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.kdroidfilter.souvlaki.MediaControlsConfig
import sample.app.App
import java.awt.Dimension

fun main() = application {
    Window(
        title = "sample",
        state = rememberWindowState(width = 800.dp, height = 600.dp),
        onCloseRequest = ::exitApplication,
    ) {
        window.minimumSize = Dimension(350, 600)
        // Initialize MediaControls with the window (required on Windows)
        MediaControlsConfig.init(window)
        App()
    }
}