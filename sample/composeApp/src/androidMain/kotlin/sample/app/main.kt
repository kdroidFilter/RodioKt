package sample.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.kdroidfilter.rodio.RodioInitializer

class AppActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize Rodio native library before any audio usage
        RodioInitializer.initialize()
        enableEdgeToEdge()
        setContent { App() }
    }
}
