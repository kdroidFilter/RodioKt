//! Rodio Kotlin bindings via UniFFI.

mod error;
mod state;

use std::fs::File;
use std::io::{self, BufReader, Cursor, Read, Seek, SeekFrom};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use reqwest::blocking::{Client, ClientBuilder, Response};
use reqwest::header::{HeaderMap, CONTENT_TYPE, USER_AGENT};
use reqwest::Certificate;

use rodio::decoder::Decoder;
use rodio::source::SineWave;
use rodio::Source;

pub use error::RodioError;
use state::{register, unregister, with_player, with_player_mut, PlayerState};

#[derive(Clone, Copy, Debug, uniffi::Enum)]
pub enum PlaybackEvent {
    Connecting,
    Playing,
    Paused,
    Stopped,
}

#[uniffi::export(callback_interface)]
pub trait PlaybackCallback: Send + Sync {
    fn on_event(&self, event: PlaybackEvent);
    fn on_metadata(&self, key: String, value: String);
    fn on_error(&self, message: String);
}

fn notify_event(callback: &Option<Arc<dyn PlaybackCallback>>, event: PlaybackEvent) {
    if let Some(callback) = callback {
        callback.on_event(event);
    }
}

fn notify_error(callback: &Option<Arc<dyn PlaybackCallback>>, error: &RodioError) {
    if let Some(callback) = callback {
        callback.on_error(error.to_string());
    }
}

#[derive(Clone, Default)]
struct HttpOptions {
    allow_invalid_certs: bool,
    extra_roots: Vec<Certificate>,
}

static HTTP_OPTIONS: OnceLock<Mutex<HttpOptions>> = OnceLock::new();

fn http_options() -> &'static Mutex<HttpOptions> {
    HTTP_OPTIONS.get_or_init(|| Mutex::new(HttpOptions::default()))
}

fn http_options_snapshot() -> Result<(bool, Vec<Certificate>), RodioError> {
    let guard = http_options()
        .lock()
        .map_err(|_| RodioError::Internal("http options lock failed".to_string()))?;
    Ok((guard.allow_invalid_certs, guard.extra_roots.clone()))
}

fn apply_http_options(
    mut builder: ClientBuilder,
    allow_invalid: bool,
    extra_roots: &[Certificate],
) -> ClientBuilder {
    if allow_invalid {
        builder = builder.danger_accept_invalid_certs(true);
    }
    for cert in extra_roots {
        builder = builder.add_root_certificate(cert.clone());
    }
    builder
}

fn player_callback(id: u64) -> Result<Option<Arc<dyn PlaybackCallback>>, RodioError> {
    with_player(id, |state| Ok(state.callback.clone()))
}

struct IcyMetadataReader<R: Read> {
    inner: R,
    meta_interval: Option<usize>,
    remaining_until_meta: usize,
    callback: Option<Arc<dyn PlaybackCallback>>,
}

impl<R: Read> IcyMetadataReader<R> {
    fn new(inner: R, meta_interval: Option<usize>, callback: Option<Arc<dyn PlaybackCallback>>) -> Self {
        let interval = meta_interval.filter(|value| *value > 0);
        Self {
            inner,
            meta_interval: interval,
            remaining_until_meta: interval.unwrap_or(0),
            callback,
        }
    }
}

impl<R: Read> Read for IcyMetadataReader<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if let Some(interval) = self.meta_interval {
            if self.remaining_until_meta == 0 {
                let mut len_buf = [0u8; 1];
                let read = self.inner.read(&mut len_buf)?;
                if read == 0 {
                    return Ok(0);
                }
                let meta_len = len_buf[0] as usize * 16;
                if meta_len > 0 {
                    let mut metadata = vec![0u8; meta_len];
                    self.inner.read_exact(&mut metadata)?;
                    for (key, value) in parse_icy_metadata_block(&metadata) {
                        if let Some(callback) = &self.callback {
                            callback.on_metadata(key, value);
                        }
                    }
                }
                self.remaining_until_meta = interval;
            }

            let to_read = buf.len().min(self.remaining_until_meta);
            let read = self.inner.read(&mut buf[..to_read])?;
            self.remaining_until_meta -= read;
            Ok(read)
        } else {
            self.inner.read(buf)
        }
    }
}

struct StreamReader {
    inner: Mutex<IcyMetadataReader<Response>>,
    pos: u64,
}

impl StreamReader {
    fn new(
        response: Response,
        meta_interval: Option<usize>,
        callback: Option<Arc<dyn PlaybackCallback>>,
    ) -> Self {
        Self {
            inner: Mutex::new(IcyMetadataReader::new(response, meta_interval, callback)),
            pos: 0,
        }
    }
}

impl Read for StreamReader {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let mut guard = self
            .inner
            .lock()
            .map_err(|_| io::Error::new(io::ErrorKind::Other, "stream lock poisoned"))?;
        let read = guard.read(buf)?;
        self.pos = self.pos.saturating_add(read as u64);
        Ok(read)
    }
}

impl Seek for StreamReader {
    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
        match pos {
            SeekFrom::Current(0) => Ok(self.pos),
            _ => Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "stream is not seekable",
            )),
        }
    }
}

fn parse_icy_metadata_block(bytes: &[u8]) -> Vec<(String, String)> {
    let text = String::from_utf8_lossy(bytes);
    let trimmed = text.trim_matches('\0').trim();
    if trimmed.is_empty() {
        return Vec::new();
    }

    trimmed
        .split(';')
        .filter_map(|part| {
            let part = part.trim();
            if part.is_empty() {
                return None;
            }
            let (key, value) = part.split_once('=')?;
            let value = value.trim().trim_matches('\'').trim_matches('"');
            if value.is_empty() {
                return None;
            }
            Some((key.trim().to_string(), value.to_string()))
        })
        .collect()
}

fn header_value(headers: &HeaderMap, name: &str) -> Option<String> {
    headers
        .get(name)
        .and_then(|value| value.to_str().ok())
        .map(|value| value.to_string())
}

fn response_content_type(response: &Response) -> Option<String> {
    response
        .headers()
        .get(CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .map(|value| value.split(';').next().unwrap_or(value).trim().to_lowercase())
}

fn icy_metaint(headers: &HeaderMap) -> Option<usize> {
    headers
        .get("icy-metaint")
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse::<usize>().ok())
        .filter(|value| *value > 0)
}

fn hint_from_mime(mime: &str) -> Option<&'static str> {
    match mime {
        "audio/mpeg" | "audio/mp3" | "audio/mpeg3" | "audio/x-mpeg" => Some("mp3"),
        "audio/aac" | "audio/aacp" | "audio/x-aac" => Some("aac"),
        "audio/ogg" | "application/ogg" => Some("ogg"),
        "audio/flac" | "audio/x-flac" => Some("flac"),
        "audio/wav" | "audio/x-wav" | "audio/wave" => Some("wav"),
        "audio/mp4" | "video/mp4" => Some("mp4"),
        _ => None,
    }
}

fn hint_from_url(url: &str) -> Option<&'static str> {
    let path = reqwest::Url::parse(url)
        .map(|parsed| parsed.path().to_string())
        .unwrap_or_else(|_| url.to_string());
    let ext = path.rsplit('.').next()?;
    match ext.to_lowercase().as_str() {
        "mp3" => Some("mp3"),
        "aac" | "aacp" => Some("aac"),
        "m4a" | "mp4" => Some("mp4"),
        "ogg" | "oga" => Some("ogg"),
        "flac" => Some("flac"),
        "wav" => Some("wav"),
        _ => None,
    }
}

fn http_client() -> Result<Client, RodioError> {
    let (allow_invalid, extra_roots) = http_options_snapshot()?;
    let builder = apply_http_options(Client::builder(), allow_invalid, &extra_roots);
    match builder.build() {
        Ok(client) => Ok(client),
        Err(_) => {
            let builder = Client::builder()
                .tls_built_in_native_certs(false)
                .tls_built_in_webpki_certs(true);
            let builder = apply_http_options(builder, allow_invalid, &extra_roots);
            Ok(builder.build()?)
        }
    }
}

fn request_stream(url: &str, want_metadata: bool) -> Result<Response, RodioError> {
    let client = http_client()?;
    let mut request = client.get(url).header(USER_AGENT, "RodioKt/1.0");
    if want_metadata {
        request = request.header("Icy-MetaData", "1");
    }
    let response = request.send()?;
    if !response.status().is_success() {
        return Err(RodioError::HttpStatus(response.status().as_u16()));
    }
    Ok(response)
}

fn download_bytes(url: &str) -> Result<Vec<u8>, RodioError> {
    let response = request_stream(url, false)?;
    let bytes = response.bytes()?;
    Ok(bytes.to_vec())
}

fn is_playlist(url: &str, content_type: Option<&str>) -> bool {
    let url = url.to_lowercase();
    if url.ends_with(".m3u") || url.ends_with(".m3u8") || url.ends_with(".pls") {
        return true;
    }
    if let Some(content_type) = content_type {
        return content_type.contains("mpegurl")
            || content_type.contains("x-mpegurl")
            || content_type.contains("scpls")
            || content_type.contains("playlist");
    }
    false
}

fn resolve_playlist(base_url: &str, body: &str) -> Option<String> {
    let base = reqwest::Url::parse(base_url).ok();
    for line in body.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let candidate = if let Some((key, value)) = line.split_once('=') {
            if key.trim().to_lowercase().starts_with("file") {
                value.trim()
            } else {
                continue;
            }
        } else {
            line
        };
        if candidate.starts_with("http://") || candidate.starts_with("https://") {
            return Some(candidate.to_string());
        }
        if let Some(base) = &base {
            if let Ok(joined) = base.join(candidate) {
                return Some(joined.to_string());
            }
        }
    }
    None
}

fn build_stream_decoder(
    reader: StreamReader,
    content_type: Option<&str>,
    url: &str,
) -> Result<Decoder<StreamReader>, RodioError> {
    let mut builder = Decoder::builder().with_data(reader).with_seekable(false);
    if let Some(content_type) = content_type {
        builder = builder.with_mime_type(content_type);
    }
    if let Some(hint) = content_type
        .and_then(hint_from_mime)
        .or_else(|| hint_from_url(url))
    {
        builder = builder.with_hint(hint);
    }
    Ok(builder.build()?)
}

#[uniffi::export]
pub fn create_player() -> Result<u64, RodioError> {
    let (player, stream) = PlayerState::new()?;
    Ok(register(player, stream))
}

#[uniffi::export]
pub fn destroy_player(id: u64) -> Result<(), RodioError> {
    unregister(id)
}

#[uniffi::export]
pub fn player_set_callback(
    id: u64,
    callback: Box<dyn PlaybackCallback>,
) -> Result<(), RodioError> {
    with_player_mut(id, |state| {
        state.callback = Some(Arc::from(callback));
        Ok(())
    })
}

#[uniffi::export]
pub fn player_clear_callback(id: u64) -> Result<(), RodioError> {
    with_player_mut(id, |state| {
        state.callback = None;
        Ok(())
    })
}

#[uniffi::export]
pub fn player_play_file(id: u64, path: String, looped: bool) -> Result<(), RodioError> {
    let callback = player_callback(id)?;
    let result = (|| {
        let file = File::open(path)?;
        let decoder = Decoder::try_from(BufReader::new(file))?;
        with_player(id, |state| {
            if looped {
                state.sink.append(decoder.repeat_infinite());
            } else {
                state.sink.append(decoder);
            }
            Ok(())
        })
    })();
    if let Err(error) = &result {
        notify_error(&callback, error);
    } else {
        notify_event(&callback, PlaybackEvent::Playing);
    }
    result
}

#[uniffi::export]
pub fn player_play_sine(
    id: u64,
    frequency_hz: f32,
    duration_ms: u64,
) -> Result<(), RodioError> {
    if frequency_hz <= 0.0 {
        return Err(RodioError::InvalidFrequency(frequency_hz));
    }
    if duration_ms == 0 {
        return Err(RodioError::InvalidDuration(duration_ms));
    }
    let callback = player_callback(id)?;
    let result = with_player(id, |state| {
        let source = SineWave::new(frequency_hz)
            .take_duration(Duration::from_millis(duration_ms));
        state.sink.append(source);
        Ok(())
    });
    if let Err(error) = &result {
        notify_error(&callback, error);
    } else {
        notify_event(&callback, PlaybackEvent::Playing);
    }
    result
}

#[uniffi::export]
pub fn player_play_url(id: u64, url: String, looped: bool) -> Result<(), RodioError> {
    let callback = player_callback(id)?;
    notify_event(&callback, PlaybackEvent::Connecting);
    let result = if looped {
        (|| {
            let bytes = download_bytes(&url)?;
            let cursor = Cursor::new(bytes);
            let decoder = Decoder::new_looped(cursor)?;
            with_player(id, |state| {
                state.sink.append(decoder);
                Ok(())
            })
        })()
    } else {
        (|| {
            let response = request_stream(&url, false)?;
            let content_type = response_content_type(&response);
            let meta_interval = icy_metaint(response.headers());
            let reader = StreamReader::new(response, meta_interval, callback.clone());
            let decoder = build_stream_decoder(reader, content_type.as_deref(), &url)?;
            with_player(id, |state| {
                state.sink.append(decoder);
                Ok(())
            })
        })()
    };
    if let Err(error) = &result {
        notify_error(&callback, error);
    } else {
        notify_event(&callback, PlaybackEvent::Playing);
    }
    result
}

#[uniffi::export]
pub fn http_set_allow_invalid_certs(allow: bool) -> Result<(), RodioError> {
    let mut guard = http_options()
        .lock()
        .map_err(|_| RodioError::Internal("http options lock failed".to_string()))?;
    guard.allow_invalid_certs = allow;
    Ok(())
}

#[uniffi::export]
pub fn http_add_root_cert_pem(pem: String) -> Result<(), RodioError> {
    let certs = Certificate::from_pem_bundle(pem.as_bytes())?;
    let mut guard = http_options()
        .lock()
        .map_err(|_| RodioError::Internal("http options lock failed".to_string()))?;
    guard.extra_roots.extend(certs);
    Ok(())
}

#[uniffi::export]
pub fn http_clear_root_certs() -> Result<(), RodioError> {
    let mut guard = http_options()
        .lock()
        .map_err(|_| RodioError::Internal("http options lock failed".to_string()))?;
    guard.extra_roots.clear();
    Ok(())
}

#[uniffi::export]
pub fn player_play_radio(id: u64, url: String) -> Result<(), RodioError> {
    let callback = player_callback(id)?;
    notify_event(&callback, PlaybackEvent::Connecting);
    let result = (|| {
        let mut response = request_stream(&url, true)?;
        let mut content_type = response_content_type(&response);
        let mut final_url = url.clone();

        if is_playlist(&url, content_type.as_deref()) {
            let body = response.text()?;
            let stream_url = resolve_playlist(&url, &body)
                .ok_or_else(|| RodioError::Playlist("playlist did not contain a stream url".to_string()))?;
            response = request_stream(&stream_url, true)?;
            content_type = response_content_type(&response);
            final_url = stream_url;
        }

        if let Some(callback) = &callback {
            let headers = response.headers();
            for (key, header) in [
                ("icy-name", "icy-name"),
                ("icy-description", "icy-description"),
                ("icy-genre", "icy-genre"),
            ] {
                if let Some(value) = header_value(headers, header) {
                    callback.on_metadata(key.to_string(), value);
                }
            }
        }

        let meta_interval = icy_metaint(response.headers());
        let reader = StreamReader::new(response, meta_interval, callback.clone());
        let decoder = build_stream_decoder(reader, content_type.as_deref(), &final_url)?;
        with_player(id, |state| {
            state.sink.append(decoder);
            Ok(())
        })
    })();
    if let Err(error) = &result {
        notify_error(&callback, error);
    } else {
        notify_event(&callback, PlaybackEvent::Playing);
    }
    result
}

#[uniffi::export]
pub fn player_play(id: u64) -> Result<(), RodioError> {
    let callback = with_player(id, |state| {
        state.sink.play();
        Ok(state.callback.clone())
    })?;
    notify_event(&callback, PlaybackEvent::Playing);
    Ok(())
}

#[uniffi::export]
pub fn player_pause(id: u64) -> Result<(), RodioError> {
    let callback = with_player(id, |state| {
        state.sink.pause();
        Ok(state.callback.clone())
    })?;
    notify_event(&callback, PlaybackEvent::Paused);
    Ok(())
}

#[uniffi::export]
pub fn player_stop(id: u64) -> Result<(), RodioError> {
    let callback = with_player(id, |state| {
        state.sink.stop();
        Ok(state.callback.clone())
    })?;
    notify_event(&callback, PlaybackEvent::Stopped);
    Ok(())
}

#[uniffi::export]
pub fn player_clear(id: u64) -> Result<(), RodioError> {
    let callback = with_player(id, |state| {
        state.sink.clear();
        Ok(state.callback.clone())
    })?;
    notify_event(&callback, PlaybackEvent::Stopped);
    Ok(())
}

#[uniffi::export]
pub fn player_is_paused(id: u64) -> Result<bool, RodioError> {
    with_player(id, |state| Ok(state.sink.is_paused()))
}

#[uniffi::export]
pub fn player_is_empty(id: u64) -> Result<bool, RodioError> {
    with_player(id, |state| Ok(state.sink.empty()))
}

#[uniffi::export]
pub fn player_set_volume(id: u64, volume: f32) -> Result<(), RodioError> {
    if volume < 0.0 {
        return Err(RodioError::InvalidVolume(volume));
    }
    with_player(id, |state| {
        state.sink.set_volume(volume);
        Ok(())
    })
}

uniffi::setup_scaffolding!();
