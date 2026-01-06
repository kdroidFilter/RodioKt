//! Error types for the Rodio bindings.

/// Errors that can occur when working with Rodio.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum RodioError {
    #[error("player {0} not found")]
    PlayerNotFound(u64),

    #[error("invalid volume: {0}")]
    InvalidVolume(f32),

    #[error("invalid frequency: {0}")]
    InvalidFrequency(f32),

    #[error("invalid duration: {0}")]
    InvalidDuration(u64),

    #[error("io error: {0}")]
    Io(String),

    #[error("decoder error: {0}")]
    Decoder(String),

    #[error("stream error: {0}")]
    Stream(String),

    #[error("internal error: {0}")]
    Internal(String),
}

impl From<std::io::Error> for RodioError {
    fn from(error: std::io::Error) -> Self {
        RodioError::Io(error.to_string())
    }
}

impl From<rodio::decoder::DecoderError> for RodioError {
    fn from(error: rodio::decoder::DecoderError) -> Self {
        RodioError::Decoder(error.to_string())
    }
}

impl From<rodio::StreamError> for RodioError {
    fn from(error: rodio::StreamError) -> Self {
        RodioError::Stream(error.to_string())
    }
}
