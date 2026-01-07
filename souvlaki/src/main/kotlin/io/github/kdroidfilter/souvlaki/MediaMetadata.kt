package io.github.kdroidfilter.souvlaki

/**
 * Media metadata for display in OS media controls.
 *
 * @property title Track title
 * @property album Album name
 * @property artist Artist name
 * @property coverUrl URL to album artwork (may not be supported on all platforms)
 * @property durationSecs Track duration in seconds
 */
data class MediaMetadata(
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val coverUrl: String? = null,
    val durationSecs: Double? = null,
)
