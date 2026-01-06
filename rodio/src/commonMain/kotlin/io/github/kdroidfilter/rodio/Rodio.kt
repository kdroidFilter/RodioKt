package io.github.kdroidfilter.rodio

import io.github.kdroidfilter.rodio.native.createPlayer
import io.github.kdroidfilter.rodio.native.destroyPlayer
import io.github.kdroidfilter.rodio.native.playerClear
import io.github.kdroidfilter.rodio.native.playerIsEmpty
import io.github.kdroidfilter.rodio.native.playerIsPaused
import io.github.kdroidfilter.rodio.native.playerPause
import io.github.kdroidfilter.rodio.native.playerPlay
import io.github.kdroidfilter.rodio.native.playerPlayFile
import io.github.kdroidfilter.rodio.native.playerPlaySine
import io.github.kdroidfilter.rodio.native.playerSetVolume
import io.github.kdroidfilter.rodio.native.playerStop

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

    fun setVolume(volume: Float) {
        playerSetVolume(requireHandle(), volume)
    }

    fun isPaused(): Boolean = playerIsPaused(requireHandle())

    fun isEmpty(): Boolean = playerIsEmpty(requireHandle())

    fun close() {
        if (closed) return
        destroyPlayer(handle)
        closed = true
    }
}
