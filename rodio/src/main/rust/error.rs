//! Error types for the Rodio bindings.

use std::error::Error as _;

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

    #[error("http error: {0}")]
    Http(String),

    #[error("http status: {0}")]
    HttpStatus(u16),

    #[error("invalid url: {0}")]
    InvalidUrl(String),

    #[error("playlist error: {0}")]
    Playlist(String),

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

impl From<reqwest::Error> for RodioError {
    fn from(error: reqwest::Error) -> Self {
        RodioError::Http(format_reqwest_error(&error))
    }
}

fn format_reqwest_error(error: &reqwest::Error) -> String {
    let mut message = error.to_string();
    let mut source = error.source();
    while let Some(err) = source {
        message.push_str(": ");
        message.push_str(&err.to_string());
        source = err.source();
    }
    if error.is_timeout() {
        message.push_str(" [timeout]");
    }
    if error.is_connect() {
        message.push_str(" [connect]");
    }
    if error.is_request() {
        message.push_str(" [request]");
    }
    if error.is_body() {
        message.push_str(" [body]");
    }
    if error.is_decode() {
        message.push_str(" [decode]");
    }
    if error.is_redirect() {
        message.push_str(" [redirect]");
    }
    message
}
