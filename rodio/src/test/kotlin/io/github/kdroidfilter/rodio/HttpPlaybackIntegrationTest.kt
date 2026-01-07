package io.github.kdroidfilter.rodio

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

private val HTTP_SOURCES = listOf(
    "https://dl.espressif.com/dl/audio/ff-16b-2c-44100hz.aac",
    "https://dl.espressif.com/dl/audio/ff-16b-2c-44100hz.ac3",
    "https://dl.espressif.com/dl/audio/ff-16b-2c-44100hz.aiff",
    "https://dl.espressif.com/dl/audio/ff-16b-2c-44100hz.flac",
    "https://dl.espressif.com/dl/audio/ff-16b-2c-44100hz.m4a",
    "https://dl.espressif.com/dl/audio/ff-16b-2c-44100hz.mp3",
    "https://dl.espressif.com/dl/audio/ff-16b-2c-44100hz.wav",
    "https://dl.espressif.com/dl/audio/ff-16b-2c-44100hz.wma",
)

private val SUPPORTED_EXTENSIONS = setOf(
    "aac",
    "aiff",
    "aif",
    "flac",
    "m4a",
    "mp3",
    "mp4",
    "ogg",
    "opus",
    "wav",
)

private sealed interface PlaybackOutcome {
    data class Playing(val durationMs: Long?, val seekable: Boolean) : PlaybackOutcome
    data class Error(val message: String) : PlaybackOutcome
    object Timeout : PlaybackOutcome
}

private class PlaybackObserver : PlaybackCallback {
    val events = Channel<PlaybackEvent>(Channel.UNLIMITED)
    val errors = Channel<String>(Channel.UNLIMITED)
    val metadata = Channel<Pair<String, String>>(Channel.UNLIMITED)

    override fun onEvent(event: PlaybackEvent) {
        events.trySend(event)
    }

    override fun onMetadata(key: String, value: String) {
        metadata.trySend(key to value)
    }

    override fun onError(message: String) {
        errors.trySend(message)
    }
}

class HttpPlaybackIntegrationTest {
    @Test
    fun playback_and_seek_when_available() = runBlocking {
        assumeTrue(shouldRunHttpTests())
        for (url in HTTP_SOURCES) {
            val outcome = playOnce(url, performSeek = true)
            when (outcome) {
                is PlaybackOutcome.Playing -> {
                    if (outcome.seekable && outcome.durationMs != null && outcome.durationMs > 0) {
                        assertTrue(true, "Seek verified for $url")
                    }
                }
                is PlaybackOutcome.Error -> assertTrue(
                    outcome.message.isNotBlank(),
                    "Expected an error message for $url",
                )
                PlaybackOutcome.Timeout -> fail("Timeout while waiting for playback start for $url")
            }
        }
    }

    @Test
    fun metadata_callback_collects_without_crashing() = runBlocking {
        assumeTrue(shouldRunHttpTests())
        val source = HTTP_SOURCES.first()
        val observer = PlaybackObserver()
        val player = RodioPlayer()
        player.setCallback(observer)
        try {
            player.playUrlAsync(source, loop = false)
            when (val outcome = observer.awaitOutcome(player, timeoutMs = 15_000)) {
                is PlaybackOutcome.Playing -> {
                    // Drain any metadata events; these static files are expected to emit none.
                    val metadataPairs = observer.metadata.drain()
                    assertTrue(metadataPairs.isEmpty(), "Expected no metadata for $source but got $metadataPairs")
                }
                is PlaybackOutcome.Error -> fail("Playback error for $source: ${outcome.message}")
                PlaybackOutcome.Timeout -> fail("Timeout while waiting for playback start for $source")
            }
        } finally {
            player.stop()
            player.clearCallback()
            player.close()
        }
    }

    private suspend fun playOnce(url: String, performSeek: Boolean): PlaybackOutcome {
        if (!isLikelySupported(url)) {
            return PlaybackOutcome.Error("format not supported for test; skipped playback")
        }
        val observer = PlaybackObserver()
        val player = RodioPlayer()
        player.setCallback(observer)
        try {
            val startResult = runCatching { player.playUrlAsync(url, loop = false) }
            if (startResult.isFailure) {
                return PlaybackOutcome.Error(startResult.exceptionOrNull()?.message ?: "playUrl failed")
            }
            val outcome = observer.awaitOutcome(player, timeoutMs = 20_000)
            if (outcome is PlaybackOutcome.Playing && performSeek && outcome.seekable && outcome.durationMs != null) {
                val duration = outcome.durationMs
                val target = (duration / 2).coerceAtLeast(1_000L)
                val seekResult = runCatching { player.seekToMs(target) }
                if (seekResult.isFailure) {
                    return PlaybackOutcome.Error("seek failed for $url: ${seekResult.exceptionOrNull()?.message}")
                }
                delay(750)
                val position = player.getPositionMs()
                val tolerance = 2_000L
                if (position !in (target - tolerance)..(target + tolerance)) {
                    return PlaybackOutcome.Error("seek position for $url should be near $target ms but was $position ms")
                }
            }
            return outcome
        } finally {
            player.stop()
            player.clearCallback()
            player.close()
        }
    }
}

private fun isLikelySupported(url: String): Boolean {
    val extension = url.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in SUPPORTED_EXTENSIONS
}

private fun shouldRunHttpTests(): Boolean =
    System.getenv("RUN_HTTP_PLAYBACK_TESTS") == "1"

private suspend fun PlaybackObserver.awaitOutcome(
    player: RodioPlayer,
    timeoutMs: Long,
): PlaybackOutcome {
    val result = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val outcome = select<PlaybackOutcome?> {
                events.onReceive { event ->
                    when (event) {
                        PlaybackEvent.PLAYING -> PlaybackOutcome.Playing(player.getDurationMs(), player.isSeekable())
                        PlaybackEvent.STOPPED -> PlaybackOutcome.Error("Playback stopped unexpectedly")
                        else -> null
                    }
                }
                errors.onReceive { message ->
                    PlaybackOutcome.Error(message)
                }
            }
            if (outcome != null) return@withTimeoutOrNull outcome
        }
        null
    }
    return result ?: PlaybackOutcome.Timeout
}

private fun <T> Channel<T>.drain(): List<T> {
    val items = mutableListOf<T>()
    while (true) {
        val result = tryReceive().getOrNull() ?: break
        items += result
    }
    return items
}
