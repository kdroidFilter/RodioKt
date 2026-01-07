package io.github.kdroidfilter.souvlaki

import java.awt.Component

/**
 * Platform configuration for MediaControls initialization.
 *
 * On Windows, the System Media Transport Controls (SMTC) require a window handle (HWND)
 * to function properly. Call [init] once at application startup before using [MediaControls].
 *
 * On Linux and macOS, initialization is optional but can be called for consistency.
 *
 * ## Usage
 *
 * ```kotlin
 * // At application startup (e.g., in main() or window initialization)
 * MediaControlsConfig.init(mainWindow)
 *
 * // Then anywhere in your app
 * val controls = MediaControls.create()
 * ```
 */
object MediaControlsConfig {
    @Volatile
    internal var hwnd: Long? = null
        private set

    @Volatile
    internal var initialized = false
        private set

    /**
     * Initialize MediaControls for the current platform.
     *
     * On Windows, this extracts the HWND from the provided component.
     * On Linux/macOS, this marks the config as initialized but doesn't require the component.
     *
     * @param component The main window/frame component (required on Windows, optional on other platforms)
     * @throws IllegalStateException If on Windows and the HWND cannot be extracted
     */
    fun init(component: Component) {
        if (isWindows) {
            hwnd = getWindowHandle(component)
                ?: throw IllegalStateException(
                    "Could not extract native window handle (HWND) from component. " +
                    "Make sure the window is displayable."
                )
        }
        initialized = true
    }

    /**
     * Initialize MediaControls with a raw window handle.
     *
     * This is useful when you already have the native window handle.
     *
     * @param windowHandle The native window handle (HWND on Windows)
     */
    fun init(windowHandle: Long) {
        hwnd = windowHandle
        initialized = true
    }

    /**
     * Reset the configuration.
     *
     * Useful for testing or when the window changes.
     */
    fun reset() {
        hwnd = null
        initialized = false
    }

    /**
     * Check if the configuration has been initialized.
     */
    val isInitialized: Boolean
        get() = initialized

    private fun getWindowHandle(component: Component): Long? {
        return try {
            // Use JNA's Native.getComponentID for native handle extraction
            val nativeClass = Class.forName("com.sun.jna.Native")
            val method = nativeClass.getMethod("getComponentID", Component::class.java)
            when (val result = method.invoke(null, component)) {
                is Long -> result
                is Int -> result.toLong()
                else -> null
            }
        } catch (_: Exception) {
            // Fallback: try using sun.awt.windows.WComponentPeer (Windows-specific)
            tryGetWindowsHandle(component)
        }
    }

    private fun tryGetWindowsHandle(component: Component): Long? {
        return try {
            val peer = component.javaClass.getMethod("getPeer").invoke(component)
                ?: return null
            val hwndField = peer.javaClass.getDeclaredField("hwnd")
            hwndField.isAccessible = true
            hwndField.getLong(peer)
        } catch (_: Exception) {
            null
        }
    }
}
