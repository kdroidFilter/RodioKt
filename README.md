# RodioKt

RodioKt is a Kotlin JVM wrapper around the Rust `rodio` engine. It offers a compact API to play local files, HTTP(S)/HLS streams, internet radio (ICY), and to quickly generate a sine tone for testing.

## Highlights ✨
- Play local files, direct URLs, HLS streams, and internet radio (with ICY metadata).
- Callbacks to track state (`Connecting`, `Playing`, `Paused`, `Stopped`), receive metadata, and surface errors.
- Volume control, position/duration retrieval, and seeking when the source is seekable.
- `suspend` helpers so playback can start off the main thread.
- Tone generator (`playSine`) to verify audio output quickly.

## Requirements ⚙️
- JDK 17 (the project targets Java 17 toolchains).
- Gradle via the provided wrapper.
- Rust toolchain (rustup + cargo) to build the library or publish locally.

## Installation 📦
### From Maven Central
```kotlin
dependencies {
    implementation("io.github.kdroidfilter:rodio:<version>")
}
```

### From source
- Build everything: `./gradlew build`
- Publish to your local Maven for integration tests: `./gradlew :rodio:publishToMavenLocal`
- Run the Compose Desktop sample: `./gradlew :sample:composeApp:run`

## Quick start 🚀
```kotlin
import io.github.kdroidfilter.rodio.RodioPlayer
import io.github.kdroidfilter.rodio.PlaybackCallback
import io.github.kdroidfilter.rodio.PlaybackEvent

// Increase bufferSizeFrames to reduce underruns at the cost of a bit more latency.
val player = RodioPlayer(bufferSizeFrames = 2048)

val callback = object : PlaybackCallback {
    override fun onEvent(event: PlaybackEvent) {
        println("State: $event")
    }

    override fun onMetadata(key: String, value: String) {
        println("Metadata: $key = $value")
    }

    override fun onError(message: String) {
        println("Playback error: $message")
    }
}

player.setCallback(callback)
player.playUrl("https://example.com/stream.mp3", loop = false)

// ...
player.close()
```

## Core API 🧭
- Playback
  - `playFile(path: String, loop: Boolean)`
  - `playUrl(url: String, loop: Boolean = false, callback: PlaybackCallback? = null)` (auto-detects HLS)
  - `playRadio(url: String, callback: PlaybackCallback? = null)` (radio streams + ICY metadata)
  - `playSine(frequencyHz: Float, durationMs: Long)`
  - `suspend` variants: `playFileAsync`, `playUrlAsync`, `playRadioAsync`
- Control
  - `play()`, `pause()`, `stop()`, `clear()` (clears queue and resets the sink)
  - `setVolume(volume: Float)` (0.0 to 1.0 recommended)
  - `getPositionMs()`, `getDurationMs()` (may return `null` if the duration is unknown)
  - `seekToMs(positionMs: Long)` + `isSeekable()` to check if seeking is supported
- Callbacks
  - `setCallback(callback: PlaybackCallback?)` / `clearCallback()`
  - `PlaybackCallback.onMetadata` is invoked for ICY metadata (radio) and some HTTP responses.

Always close the player when you are done: `player.close()`.

## Focused examples 🎯
### Play a local file
```kotlin
val player = RodioPlayer()
player.playFile("/path/to/my_file.ogg", loop = false)
```

### Play radio with metadata
```kotlin
player.setCallback(object : PlaybackCallback {
    override fun onEvent(event: PlaybackEvent) { println("State $event") }
    override fun onMetadata(key: String, value: String) { println("$key -> $value") }
    override fun onError(message: String) { println("Error: $message") }
})

player.playRadio("https://my.radio.example/stream")
```

### Non-blocking with coroutines
```kotlin
scope.launch {
    player.playUrlAsync("https://example.com/playlist.m3u8")
}
```

## HTTP/TLS customization 🛡️
Allow self-signed certificates or add extra roots if needed:
```kotlin
import io.github.kdroidfilter.rodio.RodioHttp

RodioHttp.setAllowInvalidCerts(true)              // Debug only
RodioHttp.addRootCertPem(customPemString)         // Add a trusted root
RodioHttp.clearRootCerts()                        // Restore defaults
```
These options apply to every HTTP(S) request used by the player.

## Develop and test 🧪
- Build the library only: `./gradlew :rodio:build`
- Check network/playback integration (JVM tests): `./gradlew :rodio:jvmTest`
- Manual verification with the demo app: `./gradlew :sample:composeApp:run`

## Limitations and notes ⚠️
- Duration may be unknown for some live streams; `getDurationMs()` can return `null`.
- HLS is supported, but encrypted, byte-range, or init-segment-based streams are not.
- Looping (`loop = true`) is not available for HLS.
- One `RodioPlayer` per output device is recommended; reuse it and close it cleanly with `close()`.

---

# SouvlakiKt

SouvlakiKt is a Kotlin JVM wrapper around the Rust `souvlaki` library. It provides cross-platform media controls integration, allowing your application to display metadata and respond to media keys/system controls.

## Highlights ✨
- **Cross-platform**: Works on Linux (D-Bus MPRIS), macOS (Now Playing), and Windows (SMTC).
- Display track metadata (title, artist, album, artwork URL, duration) in OS media controls.
- Receive events from media keys and system controls (play, pause, next, previous, seek, etc.).
- Simple initialization pattern suitable for KMP (Kotlin Multiplatform) projects.

## Installation 📦
### From Maven Central
```kotlin
dependencies {
    implementation("io.github.kdroidfilter:souvlaki:<version>")
}
```

### From source
- Build everything: `./gradlew :souvlaki:build`
- Publish to your local Maven: `./gradlew :souvlaki:publishToMavenLocal`

## Quick start 🚀

### 1. Initialize (required on Windows)
```kotlin
import io.github.kdroidfilter.souvlaki.MediaControlsConfig

// At application startup, pass your main window
MediaControlsConfig.init(mainWindow)
```

### 2. Create and use MediaControls
```kotlin
import io.github.kdroidfilter.souvlaki.*

val controls = MediaControls.create(
    dbusName = "my_player",      // Linux only
    displayName = "My Player"    // Linux only
)

// Set metadata
controls.setMetadata(MediaMetadata(
    title = "Song Title",
    artist = "Artist Name",
    album = "Album Name",
    durationSecs = 180.0
))

// Set playback status
controls.setPlayback(PlaybackStatus.PLAYING)

// Listen for events
controls.attach { event ->
    when (event.eventType) {
        MediaControlEventType.PLAY -> player.play()
        MediaControlEventType.PAUSE -> player.pause()
        MediaControlEventType.NEXT -> player.next()
        MediaControlEventType.PREVIOUS -> player.previous()
        MediaControlEventType.STOP -> player.stop()
        MediaControlEventType.TOGGLE -> player.togglePlayPause()
        MediaControlEventType.SEEK -> {
            if (event.seekForward == true) player.seekForward()
            else player.seekBackward()
        }
        MediaControlEventType.SET_POSITION -> {
            event.positionSecs?.let { player.seekTo(it) }
        }
        else -> {}
    }
}

// When done
controls.close()
```

## Core API 🧭
- **Initialization**
  - `MediaControlsConfig.init(component: Component)` – Initialize with window (required on Windows)
  - `MediaControlsConfig.init(windowHandle: Long)` – Initialize with raw HWND
  - `MediaControlsConfig.reset()` – Reset configuration

- **MediaControls**
  - `MediaControls.create(dbusName, displayName)` – Create a new instance
  - `setMetadata(MediaMetadata)` – Update displayed metadata
  - `setPlayback(PlaybackStatus)` – Set playback status (Playing, Paused, Stopped)
  - `setPlayback(status, progressSecs)` – Set status with progress
  - `attach(callback)` / `attach { event -> }` – Listen for events
  - `detach()` – Stop listening for events
  - `close()` – Release resources

- **MediaMetadata**
  - `title`, `artist`, `album`, `coverUrl`, `durationSecs`

- **Events** (`MediaControlEventType`)
  - `PLAY`, `PAUSE`, `TOGGLE`, `STOP`
  - `NEXT`, `PREVIOUS`
  - `SEEK`, `SEEK_BY`, `SET_POSITION`
  - `SET_VOLUME`, `OPEN_URI`, `RAISE`, `QUIT`

## Platform notes 📋

| Platform | Init Required | Backend | Notes |
|----------|---------------|---------|-------|
| **Linux** | No | D-Bus MPRIS | Test with `playerctl` or media keys |
| **macOS** | No | MediaPlayer framework | Shows in Now Playing widget |
| **Windows** | **Yes** | SMTC | Requires window handle (HWND) |

### Testing on Linux
```bash
# List available players
playerctl -l

# Send commands
playerctl -p my_player play
playerctl -p my_player pause
playerctl -p my_player next

# View metadata
playerctl -p my_player metadata
```

## Develop and test 🧪
- Build the library: `./gradlew :souvlaki:build`
- Run the sample app with Media Controls tab: `./gradlew :sample:composeApp:run`
