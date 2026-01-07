package io.github.kdroidfilter.souvlaki

/** Check if the current platform is Windows. */
internal val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")

/** Check if the current platform is macOS. */
internal val isMacOS: Boolean = System.getProperty("os.name").lowercase().contains("mac")

/** Check if the current platform is Linux. */
internal val isLinux: Boolean = System.getProperty("os.name").lowercase().contains("linux")
