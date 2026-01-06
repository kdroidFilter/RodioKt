package io.github.kdroidfilter.rodio

import io.github.kdroidfilter.rodio.native.createPlayer
import io.github.kdroidfilter.rodio.native.destroyPlayer
import io.github.kdroidfilter.rodio.native.playerClear
import io.github.kdroidfilter.rodio.native.playerClearCallback
import io.github.kdroidfilter.rodio.native.playerGetDurationMs
import io.github.kdroidfilter.rodio.native.playerGetPositionMs
import io.github.kdroidfilter.rodio.native.playerIsEmpty
import io.github.kdroidfilter.rodio.native.playerIsPaused
import io.github.kdroidfilter.rodio.native.playerPause
import io.github.kdroidfilter.rodio.native.playerPlay
import io.github.kdroidfilter.rodio.native.playerPlayFile
import io.github.kdroidfilter.rodio.native.playerPlayRadio
import io.github.kdroidfilter.rodio.native.playerPlaySine
import io.github.kdroidfilter.rodio.native.playerPlayUrl
import io.github.kdroidfilter.rodio.native.playerSetCallback
import io.github.kdroidfilter.rodio.native.playerSetVolume
import io.github.kdroidfilter.rodio.native.playerStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

typealias PlaybackCallback = io.github.kdroidfilter.rodio.native.PlaybackCallback
typealias PlaybackEvent = io.github.kdroidfilter.rodio.native.PlaybackEvent

class RodioPlayer {
    private var handle: ULong = createPlayer()
    private var closed = false

    private fun requireHandle(): ULong {
        check(!closed) { "RodioPlayer is closed" }
        return handle
    }

    fun playFile(path: String, loop: Boolean) {
        playerPlayFile(requireHandle(), path, loop)
    }

    fun playUrl(url: String, loop: Boolean = false, callback: PlaybackCallback? = null) {
        if (callback != null) setCallback(callback)
        playerPlayUrl(requireHandle(), url, loop)
    }

    suspend fun playUrlAsync(url: String, loop: Boolean = false, callback: PlaybackCallback? = null) {
        withContext(Dispatchers.Default) {
            playUrl(url, loop, callback)
        }
    }

    fun playRadio(url: String, callback: PlaybackCallback? = null) {
        if (callback != null) setCallback(callback)
        playerPlayRadio(requireHandle(), url)
    }

    suspend fun playRadioAsync(url: String, callback: PlaybackCallback? = null) {
        withContext(Dispatchers.Default) {
            playRadio(url, callback)
        }
    }

    fun playSine(frequencyHz: Float, durationMs: Long) {
        require(durationMs > 0) { "durationMs must be > 0" }
        playerPlaySine(requireHandle(), frequencyHz, durationMs.toULong())
    }

    fun play() {
        playerPlay(requireHandle())
    }

    fun pause() {
        playerPause(requireHandle())
    }

    fun stop() {
        playerStop(requireHandle())
    }

    fun clear() {
        playerClear(requireHandle())
    }

    fun getPositionMs(): Long = playerGetPositionMs(requireHandle()).toLong()

    fun getDurationMs(): Long? = playerGetDurationMs(requireHandle())?.toLong()

    fun setVolume(volume: Float) {
        playerSetVolume(requireHandle(), volume)
    }

    fun setCallback(callback: PlaybackCallback?) {
        if (callback == null) {
            playerClearCallback(requireHandle())
        } else {
            playerSetCallback(requireHandle(), callback)
        }
    }

    fun clearCallback() {
        playerClearCallback(requireHandle())
    }

    fun isPaused(): Boolean = playerIsPaused(requireHandle())

    fun isEmpty(): Boolean = playerIsEmpty(requireHandle())

    fun close() {
        if (closed) return
        destroyPlayer(handle)
        closed = true
    }
}

