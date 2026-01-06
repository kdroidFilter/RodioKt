package io.github.kdroidfilter.rodio

/**
 * Initializes the Rodio native library for Android.
 *
 * This MUST be called before creating any RodioPlayer instances.
 * Typically call this in your Application.onCreate() or before first use.
 *
 * This is required because UniFFI uses JNA which loads libraries via dlopen(),
 * which does not trigger JNI_OnLoad. By calling System.loadLibrary() first,
 * we ensure the Android NDK context is properly initialized for audio APIs.
 */
object RodioInitializer {
    @Volatile
    private var initialized = false

    /**
     * Initialize the Rodio native library.
     * Safe to call multiple times - subsequent calls are no-ops.
     */
    @JvmStatic
    fun initialize() {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    System.loadLibrary("rodio_kt")
                    initialized = true
                }
            }
        }
    }
}
