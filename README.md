# RodioKt

RodioKt is a Kotlin Multiplatform wrapper around the Rust `rodio` audio engine. It gives you a small, direct API for file playback, streaming URLs, internet radio, and simple tone generation.

## Features

- Play local files, URLs, and radio streams
- Callback-based playback events and metadata
- Volume control and playback position
- Simple sine tone generator for testing

## Installation

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation("io.github.kdroidfilter:rodio:<version>")
}
```

If you build from source, you will need a Rust toolchain installed (cargo + rustup).

## Quick start

```kotlin
import io.github.kdroidfilter.rodio.RodioPlayer
import io.github.kdroidfilter.rodio.native.PlaybackCallback
import io.github.kdroidfilter.rodio.native.PlaybackEvent

val player = RodioPlayer()

val callback = object : PlaybackCallback {
    override fun onEvent(event: PlaybackEvent) {
        println("Playback event: $event")
    }

    override fun onMetadata(key: String, value: String) {
        println("Metadata: $key=$value")
    }

    override fun onError(message: String) {
        println("Playback error: $message")
    }
}

player.setCallback(callback)
player.playUrl("https://example.com/stream.mp3", loop = false)
```

When you are done:

```kotlin
player.clearCallback()
player.close()
```

## Core API

`RodioPlayer`:

- `playFile(path: String, loop: Boolean)`
- `playUrl(url: String, loop: Boolean = false, callback: PlaybackCallback? = null)`
- `playRadio(url: String, callback: PlaybackCallback? = null)`
- `playSine(frequencyHz: Float, durationMs: Long)`
- `play()`, `pause()`, `stop()`, `clear()`
- `getPositionMs()`, `getDurationMs()`
- `setVolume(volume: Float)`
- `setCallback(callback: PlaybackCallback?)`, `clearCallback()`
- `close()`

There are also coroutine helpers:

- `playUrlAsync(...)`
- `playRadioAsync(...)`

## HTTP/TLS customization

If you need to relax TLS validation or add custom roots:

```kotlin
import io.github.kdroidfilter.rodio.RodioHttp

RodioHttp.setAllowInvalidCerts(true)
RodioHttp.addRootCertPem(yourPemString)
RodioHttp.clearRootCerts()
```

## Sample app

There is a small Compose Desktop sample in `sample/` that demonstrates stream playback, volume control, and progress reporting.

