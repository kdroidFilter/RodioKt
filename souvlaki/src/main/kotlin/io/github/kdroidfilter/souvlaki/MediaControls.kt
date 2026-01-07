package io.github.kdroidfilter.souvlaki

import io.github.kdroidfilter.souvlaki.native.createMediaControls
import io.github.kdroidfilter.souvlaki.native.createMediaControlsWithHwnd
import io.github.kdroidfilter.souvlaki.native.destroyMediaControls
import io.github.kdroidfilter.souvlaki.native.mediaControlsAttach
import io.github.kdroidfilter.souvlaki.native.mediaControlsDetach
import io.github.kdroidfilter.souvlaki.native.mediaControlsSetMetadata
import io.github.kdroidfilter.souvlaki.native.mediaControlsSetPlayback
import io.github.kdroidfilter.souvlaki.native.mediaControlsSetPlaybackWithProgress

/**
 * Cross-platform media controls for Kotlin/JVM applications.
 *
 * Provides integration with the operating system's media control interfaces:
 * - **Linux**: D-Bus MPRIS
 * - **macOS**: MediaPlayer framework (Now Playing)
 * - **Windows**: System Media Transport Controls (SMTC)
 *
 * ## Setup
 *
 * On Windows, you must initialize the configuration before creating controls:
 *
 * ```kotlin
 * // At app startup (required on Windows, optional on Linux/macOS)
 * MediaControlsConfig.init(mainWindow)
 * ```
 *
 * ## Example Usage
 *
 * ```kotlin
 * val controls = MediaControls.create(
 *     dbusName = "my_player",
 *     displayName = "My Player"
 * )
 *
 * controls.setMetadata(MediaMetadata(
 *     title = "Song Title",
 *     artist = "Artist Name",
 *     album = "Album Name"
 * ))
 *
 * controls.setPlayback(PlaybackStatus.PLAYING)
 *
 * controls.attach { event ->
 *     when (event.eventType) {
 *         MediaControlEventType.PLAY -> player.play()
 *         MediaControlEventType.PAUSE -> player.pause()
 *         MediaControlEventType.NEXT -> player.next()
 *         MediaControlEventType.PREVIOUS -> player.previous()
 *         else -> {}
 *     }
 * }
 *
 * // When done
 * controls.close()
 * ```
 *
 * @see MediaControlsConfig
 * @see MediaMetadata
 * @see PlaybackStatus
 */
class MediaControls private constructor(
    private var handle: ULong
) : AutoCloseable {

    private var closed = false

    private fun requireHandle(): ULong {
        check(!closed) { "MediaControls is closed" }
        return handle
    }

    /**
     * Attach a callback to receive media control events.
     *
     * The callback will be invoked when the user interacts with the OS
     * media controls (e.g., play/pause buttons, seek slider, media keys).
     *
     * @param callback The callback to receive events
     */
    fun attach(callback: MediaControlCallback) {
        mediaControlsAttach(requireHandle(), callback)
    }

    /**
     * Attach a callback to receive media control events using a lambda.
     *
     * @param onEvent Lambda invoked when an event is received
     */
    fun attach(onEvent: (MediaControlEventData) -> Unit) {
        attach(object : MediaControlCallback {
            override fun onEvent(event: MediaControlEventData) {
                onEvent(event)
            }
        })
    }

    /**
     * Detach the event callback.
     *
     * After calling this, no more events will be received until [attach] is called again.
     */
    fun detach() {
        mediaControlsDetach(requireHandle())
    }

    /**
     * Set the media metadata displayed in OS media controls.
     *
     * @param metadata The metadata to display
     */
    fun setMetadata(metadata: MediaMetadata) {
        mediaControlsSetMetadata(
            requireHandle(),
            metadata.title,
            metadata.album,
            metadata.artist,
            metadata.coverUrl,
            metadata.durationSecs
        )
    }

    /**
     * Set the media metadata displayed in OS media controls.
     *
     * @param title Track title
     * @param album Album name
     * @param artist Artist name
     * @param coverUrl URL to album artwork
     * @param durationSecs Track duration in seconds
     */
    fun setMetadata(
        title: String? = null,
        album: String? = null,
        artist: String? = null,
        coverUrl: String? = null,
        durationSecs: Double? = null,
    ) {
        mediaControlsSetMetadata(
            requireHandle(),
            title,
            album,
            artist,
            coverUrl,
            durationSecs
        )
    }

    /**
     * Set the playback status.
     *
     * @param status The current playback status
     */
    fun setPlayback(status: PlaybackStatus) {
        mediaControlsSetPlayback(requireHandle(), status)
    }

    /**
     * Set the playback status with progress information.
     *
     * @param status The current playback status
     * @param progressSecs Current playback position in seconds
     */
    fun setPlayback(status: PlaybackStatus, progressSecs: Double?) {
        mediaControlsSetPlaybackWithProgress(requireHandle(), status, progressSecs)
    }

    /**
     * Close the media controls and release resources.
     *
     * After calling this, the instance cannot be used anymore.
     */
    override fun close() {
        if (closed) return
        destroyMediaControls(handle)
        closed = true
    }

    companion object {
        /**
         * Create media controls.
         *
         * On Windows, [MediaControlsConfig.init] must be called first with the main window.
         * On Linux/macOS, initialization is optional.
         *
         * @param dbusName D-Bus name for Linux MPRIS (e.g., "my_player").
         *                 Ignored on macOS and Windows.
         * @param displayName Display name shown in media controls UI.
         *                    Ignored on macOS and Windows.
         * @return A new MediaControls instance
         * @throws IllegalStateException On Windows if [MediaControlsConfig.init] was not called.
         */
        fun create(
            dbusName: String = "kotlin_media_player",
            displayName: String = "Kotlin Media Player"
        ): MediaControls {
            if (isWindows) {
                val hwnd = MediaControlsConfig.hwnd
                    ?: throw IllegalStateException(
                        "On Windows, MediaControlsConfig.init(window) must be called before creating MediaControls."
                    )
                val handle = createMediaControlsWithHwnd(hwnd.toULong())
                return MediaControls(handle)
            }
            val handle = createMediaControls(dbusName, displayName)
            return MediaControls(handle)
        }
    }
}
