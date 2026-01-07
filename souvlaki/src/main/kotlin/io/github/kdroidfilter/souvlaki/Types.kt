package io.github.kdroidfilter.souvlaki

/**
 * Re-export types from native bindings for convenient access.
 */

/** Callback interface for receiving media control events. */
typealias MediaControlCallback = io.github.kdroidfilter.souvlaki.native.MediaControlCallback

/** Data class containing event information from media controls. */
typealias MediaControlEventData = io.github.kdroidfilter.souvlaki.native.MediaControlEventData

/** Enum representing the type of media control event. */
typealias MediaControlEventType = io.github.kdroidfilter.souvlaki.native.MediaControlEventType

/** Enum representing playback status (Playing, Paused, Stopped). */
typealias PlaybackStatus = io.github.kdroidfilter.souvlaki.native.PlaybackStatus

/** Exception thrown by souvlaki operations. */
typealias SouvlakiException = io.github.kdroidfilter.souvlaki.native.SouvlakiException
