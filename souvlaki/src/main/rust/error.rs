//! Error types for Souvlaki Kotlin bindings.

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum SouvlakiError {
    #[error("Media controls not found with id: {0}")]
    ControlsNotFound(u64),

    #[error("Platform not supported")]
    PlatformNotSupported,

    #[error("Failed to create media controls: {0}")]
    Creation(String),

    #[error("Failed to attach event handler: {0}")]
    Attach(String),

    #[error("Failed to detach event handler: {0}")]
    Detach(String),

    #[error("Failed to set metadata: {0}")]
    Metadata(String),

    #[error("Failed to set playback status: {0}")]
    PlaybackStatus(String),

    #[error("Internal error: {0}")]
    Internal(String),
}

impl From<souvlaki::Error> for SouvlakiError {
    fn from(_err: souvlaki::Error) -> Self {
        // The Error type varies by platform backend, use a generic message
        SouvlakiError::Internal("souvlaki operation failed".to_string())
    }
}
