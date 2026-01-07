//! Rodio Kotlin bindings via UniFFI.

mod error;
mod state;

use std::fs::File;
use std::io::{self, BufReader, Cursor, Read, Seek, SeekFrom};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use hls_m3u8::tags::VariantStream;
use hls_m3u8::{MasterPlaylist, MediaPlaylist};
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

struct HlsStreamReader {
    playlist_url: reqwest::Url,
    cached_playlist: Option<MediaPlaylist<'static>>,
    next_sequence: Option<usize>,
    current_response: Option<Response>,
    ended: bool,
    pos: u64,
}

impl HlsStreamReader {
    fn new(url: &str) -> Result<(Self, Option<String>, Option<Duration>), RodioError> {
        let playlist_url =
            reqwest::Url::parse(url).map_err(|_| RodioError::InvalidUrl(url.to_string()))?;
        let (playlist, resolved_url) = fetch_hls_media_playlist(&playlist_url)?;
        let hint_url = first_hls_segment_url(&resolved_url, &playlist);
        let total_duration = hls_total_duration(&playlist);
        Ok((
            Self {
                playlist_url: resolved_url,
                cached_playlist: Some(playlist),
                next_sequence: None,
                current_response: None,
                ended: false,
                pos: 0,
            },
            hint_url,
            total_duration,
        ))
    }

    fn load_playlist(&mut self) -> Result<(), RodioError> {
        let (playlist, playlist_url) = fetch_hls_media_playlist(&self.playlist_url)?;
        self.playlist_url = playlist_url;
        self.cached_playlist = Some(playlist);
        Ok(())
    }

    fn next_segment_url(&mut self) -> Result<Option<reqwest::Url>, RodioError> {
        loop {
            if self.ended {
                return Ok(None);
            }
            if self.cached_playlist.is_none() {
                self.load_playlist()?;
            }
            let (segment_count, target_duration, has_end_list, media_sequence) = {
                let playlist = self.cached_playlist.as_ref().ok_or_else(|| {
                    RodioError::Internal("hls playlist missing after load".to_string())
                })?;
                (
                    playlist.segments.values().count(),
                    playlist.target_duration,
                    playlist.has_end_list,
                    playlist.media_sequence,
                )
            };
            if segment_count == 0 {
                if has_end_list {
                    self.ended = true;
                    return Ok(None);
                }
                self.cached_playlist = None;
                std::thread::sleep(hls_refresh_delay(target_duration));
                continue;
            }

            let mut next_sequence = self.next_sequence.unwrap_or(media_sequence);
            if next_sequence < media_sequence {
                next_sequence = media_sequence;
                self.next_sequence = Some(next_sequence);
            }

            let index = next_sequence - media_sequence;
            if index >= segment_count {
                if has_end_list {
                    self.ended = true;
                    return Ok(None);
                }
                self.cached_playlist = None;
                std::thread::sleep(hls_refresh_delay(target_duration));
                continue;
            }

            let segment = self
                .cached_playlist
                .as_ref()
                .ok_or_else(|| RodioError::Internal("hls playlist missing after load".to_string()))?
                .segments
                .values()
                .nth(index)
                .ok_or_else(|| RodioError::Internal("hls segment lookup failed".to_string()))?;
            validate_hls_segment(segment)?;
            self.next_sequence = Some(next_sequence + 1);
            let url = resolve_hls_url(&self.playlist_url, segment.uri().as_ref())?;
            return Ok(Some(url));
        }
    }
}

impl Read for HlsStreamReader {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        loop {
            if let Some(response) = &mut self.current_response {
                let read = response.read(buf)?;
                if read > 0 {
                    self.pos = self.pos.saturating_add(read as u64);
                    return Ok(read);
                }
                self.current_response = None;
            }

            if self.ended {
                return Ok(0);
            }

            let next_url = self
                .next_segment_url()
                .map_err(|err| io::Error::new(io::ErrorKind::Other, err.to_string()))?;
            match next_url {
                Some(url) => {
                    let response = request_stream(url.as_str(), false)
                        .map_err(|err| io::Error::new(io::ErrorKind::Other, err.to_string()))?;
                    self.current_response = Some(response);
                }
                None => {
                    self.ended = true;
                    return Ok(0);
                }
            }
        }
    }
}

impl Seek for HlsStreamReader {
    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
        match pos {
            SeekFrom::Current(0) => Ok(self.pos),
            _ => Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "hls stream is not seekable",
            )),
        }
    }
}

fn validate_hls_segment(segment: &hls_m3u8::MediaSegment<'_>) -> Result<(), RodioError> {
    if segment.map.is_some() {
        return Err(RodioError::Playlist(
            "hls init segments are not supported".to_string(),
        ));
    }
    if segment.byte_range.is_some() {
        return Err(RodioError::Playlist(
            "hls byte-range segments are not supported".to_string(),
        ));
    }
    if segment.keys.iter().any(|key| key.is_some()) {
        return Err(RodioError::Playlist(
            "hls encrypted segments are not supported".to_string(),
        ));
    }
    Ok(())
}

fn hls_refresh_delay(target_duration: Duration) -> Duration {
    let mut millis = target_duration.as_millis() as u64 / 2;
    if millis < 500 {
        millis = 500;
    }
    if millis > 2000 {
        millis = 2000;
    }
    Duration::from_millis(millis)
}

fn hls_total_duration(playlist: &MediaPlaylist<'_>) -> Option<Duration> {
    if !playlist.has_end_list {
        return None;
    }
    let mut total = Duration::ZERO;
    for segment in playlist.segments.values() {
        total = total.saturating_add(segment.duration.duration());
    }
    Some(total)
}

fn first_hls_segment_url(
    playlist_url: &reqwest::Url,
    playlist: &MediaPlaylist<'_>,
) -> Option<String> {
    playlist
        .segments
        .values()
        .next()
        .and_then(|segment| resolve_hls_url(playlist_url, segment.uri().as_ref()).ok())
        .map(|url| url.to_string())
}

fn parse_hls_media_playlist(body: &str) -> Result<MediaPlaylist<'static>, RodioError> {
    MediaPlaylist::try_from(body)
        .map(|playlist| playlist.into_owned())
        .map_err(|err| RodioError::Playlist(format!("hls media playlist parse failed: {err}")))
}

fn resolve_hls_url(base_url: &reqwest::Url, candidate: &str) -> Result<reqwest::Url, RodioError> {
    if let Ok(url) = reqwest::Url::parse(candidate) {
        return Ok(url);
    }
    base_url
        .join(candidate)
        .map_err(|_| RodioError::InvalidUrl(candidate.to_string()))
}

fn select_hls_variant_url(
    master: &MasterPlaylist<'_>,
    base_url: &reqwest::Url,
) -> Result<reqwest::Url, RodioError> {
    let mut best: Option<(&VariantStream<'_>, u64)> = None;
    for variant in &master.variant_streams {
        let VariantStream::ExtXStreamInf { .. } = variant else {
            continue;
        };
        let bandwidth = variant.bandwidth();
        if best
            .map(|(_, current)| bandwidth > current)
            .unwrap_or(true)
        {
            best = Some((variant, bandwidth));
        }
    }
    let variant = best
        .map(|(variant, _)| variant)
        .ok_or_else(|| {
            RodioError::Playlist("hls master playlist has no stream variants".to_string())
        })?;
    let uri = match variant {
        VariantStream::ExtXStreamInf { uri, .. } => uri.as_ref(),
        VariantStream::ExtXIFrame { .. } => {
            return Err(RodioError::Playlist(
                "hls master playlist has no playable stream variants".to_string(),
            ));
        }
    };
    resolve_hls_url(base_url, uri)
}

fn fetch_hls_media_playlist(
    url: &reqwest::Url,
) -> Result<(MediaPlaylist<'static>, reqwest::Url), RodioError> {
    let response = request_stream(url.as_str(), false)?;
    let body = response.text()?;

    if let Ok(media) = parse_hls_media_playlist(&body) {
        return Ok((media, url.clone()));
    }

    let master = MasterPlaylist::try_from(body.as_str())
        .map(|playlist| playlist.into_owned())
        .map_err(|err| RodioError::Playlist(format!("hls master playlist parse failed: {err}")))?;
    let variant_url = select_hls_variant_url(&master, url)?;
    let response = request_stream(variant_url.as_str(), false)?;
    let body = response.text()?;
    let media = parse_hls_media_playlist(&body)?;
    Ok((media, variant_url))
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

fn duration_to_millis(duration: Duration) -> u64 {
    u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
}

fn approximate_file_duration(path: &str) -> Option<Duration> {
    // Fallback for formats that do not expose a duration in metadata.
    let file = File::open(path).ok()?;
    let mut decoder = Decoder::try_from(BufReader::new(file)).ok()?;
    let sample_rate = u64::from(decoder.sample_rate());
    let channels = u64::from(decoder.channels());
    if sample_rate == 0 || channels == 0 {
        return None;
    }
    let total_samples = u64::try_from(decoder.count()).ok()?;
    let frames = total_samples.checked_div(channels)?;
    let seconds = frames as f64 / sample_rate as f64;
    if seconds.is_finite() && seconds > 0.0 {
        Some(Duration::from_secs_f64(seconds))
    } else {
        None
    }
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

fn is_hls_playlist(url: &str, content_type: Option<&str>) -> bool {
    if url.to_lowercase().ends_with(".m3u8") {
        return true;
    }
    if let Some(content_type) = content_type {
        let content_type = content_type.to_lowercase();
        return content_type.contains("vnd.apple.mpegurl")
            || content_type.contains("application/x-mpegurl")
            || content_type.contains("mpegurl");
    }
    false
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

fn build_hls_decoder(
    reader: HlsStreamReader,
    hint_url: Option<&str>,
) -> Result<Decoder<HlsStreamReader>, RodioError> {
    let mut builder = Decoder::builder().with_data(reader).with_seekable(false);
    if let Some(hint) = hint_url.and_then(hint_from_url) {
        builder = builder.with_hint(hint);
    }
    Ok(builder.build()?)
}

fn play_hls_stream(id: u64, url: &str) -> Result<(), RodioError> {
    let (reader, hint_url, total_duration) = HlsStreamReader::new(url)?;
    let decoder = build_hls_decoder(reader, hint_url.as_deref())?;
    with_player_mut(id, |state| {
        state.current_duration = total_duration;
        state.seekable = false;
        state.sink.append(decoder);
        Ok(())
    })
}

#[uniffi::export]
pub fn create_player() -> Result<u64, RodioError> {
    let (player, stream) = PlayerState::new()?;
    Ok(register(player, stream))
}

#[uniffi::export]
pub fn create_player_with_buffer_size_frames(buffer_size_frames: u32) -> Result<u64, RodioError> {
    let (player, stream) = PlayerState::new_with_buffer_size_frames(buffer_size_frames)?;
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
        let file = File::open(&path)?;
        let len = file.metadata()?.len();
        let mut builder = Decoder::builder()
            .with_data(file)
            .with_byte_len(len);
        if let Some(hint) = hint_from_url(&path) {
            builder = builder.with_hint(hint);
        }
        let mut decoder = builder.build()?;
        let seekable = decoder.try_seek(Duration::from_millis(0)).is_ok();
        let duration = if looped {
            None
        } else {
            decoder
                .total_duration()
                .or_else(|| approximate_file_duration(&path))
        };
        with_player_mut(id, |state| {
            state.current_duration = duration;
            state.seekable = seekable && !looped;
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
    let duration = Duration::from_millis(duration_ms);
    let result = with_player_mut(id, |state| {
        state.current_duration = Some(duration);
        state.seekable = false;
        let source = SineWave::new(frequency_hz)
            .take_duration(duration);
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
    let result = (|| {
        if looped {
            if is_hls_playlist(&url, None) {
                return Err(RodioError::Playlist(
                    "hls looped playback is not supported".to_string(),
                ));
            }
            let bytes = download_bytes(&url)?;
            let cursor = Cursor::new(bytes);
            let decoder = Decoder::new_looped(cursor)?;
            return with_player_mut(id, |state| {
                state.current_duration = None;
                state.sink.append(decoder);
                Ok(())
            });
        }

        if is_hls_playlist(&url, None) {
            return play_hls_stream(id, &url);
        }

        let response = request_stream(&url, false)?;
        let content_type = response_content_type(&response);
        if is_hls_playlist(&url, content_type.as_deref()) {
            return play_hls_stream(id, &url);
        }
        let meta_interval = icy_metaint(response.headers());
        let reader = StreamReader::new(response, meta_interval, callback.clone());
        let decoder = build_stream_decoder(reader, content_type.as_deref(), &url)?;
        let duration = decoder.total_duration();
        with_player_mut(id, |state| {
            state.current_duration = duration;
            state.seekable = false;
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
        if is_hls_playlist(&url, None) {
            return play_hls_stream(id, &url);
        }

        let mut response = request_stream(&url, true)?;
        let mut content_type = response_content_type(&response);
        let mut final_url = url.clone();

        if is_hls_playlist(&final_url, content_type.as_deref()) {
            return play_hls_stream(id, &final_url);
        }

        if is_playlist(&url, content_type.as_deref()) {
            let body = response.text()?;
            let stream_url = resolve_playlist(&url, &body)
                .ok_or_else(|| RodioError::Playlist("playlist did not contain a stream url".to_string()))?;
            if is_hls_playlist(&stream_url, None) {
                return play_hls_stream(id, &stream_url);
            }
            response = request_stream(&stream_url, true)?;
            content_type = response_content_type(&response);
            final_url = stream_url;
            if is_hls_playlist(&final_url, content_type.as_deref()) {
                return play_hls_stream(id, &final_url);
            }
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
        let duration = decoder.total_duration();
        with_player_mut(id, |state| {
            state.current_duration = duration;
            state.seekable = false;
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
    let callback = with_player_mut(id, |state| {
        state.current_duration = None;
        state.seekable = false;
        state.sink.stop();
        Ok(state.callback.clone())
    })?;
    notify_event(&callback, PlaybackEvent::Stopped);
    Ok(())
}

#[uniffi::export]
pub fn player_clear(id: u64) -> Result<(), RodioError> {
    let callback = with_player_mut(id, |state| {
        state.current_duration = None;
        state.seekable = false;
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
pub fn player_get_position_ms(id: u64) -> Result<u64, RodioError> {
    with_player(id, |state| Ok(duration_to_millis(state.sink.get_pos())))
}

#[uniffi::export]
pub fn player_seek_position_ms(id: u64, position_ms: u64) -> Result<(), RodioError> {
    let target = Duration::from_millis(position_ms);
    with_player_mut(id, |state| {
        let duration = state
            .current_duration
            .ok_or_else(|| RodioError::Seek("duration unknown or not seekable".to_string()))?;
        let clamped = if target > duration { duration } else { target };
        if !state.seekable {
            return Err(RodioError::Seek("source is not seekable".to_string()));
        }
        state.sink.try_seek(clamped)?;
        Ok(())
    })
}

#[uniffi::export]
pub fn player_get_duration_ms(id: u64) -> Result<Option<u64>, RodioError> {
    with_player(id, |state| Ok(state.current_duration.map(duration_to_millis)))
}

#[uniffi::export]
pub fn player_is_seekable(id: u64) -> Result<bool, RodioError> {
    with_player(id, |state| Ok(state.seekable))
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
