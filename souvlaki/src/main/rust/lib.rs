//! Souvlaki Kotlin bindings via UniFFI.
//!
//! Cross-platform media controls for Kotlin/JVM applications.

mod error;

use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};

pub use error::SouvlakiError;
use souvlaki::{MediaControlEvent as SouvlakiEvent, MediaControls, MediaMetadata, MediaPlayback, MediaPosition, PlatformConfig, SeekDirection};

/// Registry of active media controls instances.
static REGISTRY: OnceLock<Mutex<HashMap<u64, ControlsState>>> = OnceLock::new();
static NEXT_ID: OnceLock<Mutex<u64>> = OnceLock::new();

fn registry() -> &'static Mutex<HashMap<u64, ControlsState>> {
    REGISTRY.get_or_init(|| Mutex::new(HashMap::new()))
}

fn next_id() -> u64 {
    let counter = NEXT_ID.get_or_init(|| Mutex::new(1));
    let mut guard = counter.lock().unwrap();
    let id = *guard;
    *guard = guard.wrapping_add(1);
    id
}

struct ControlsState {
    controls: MediaControls,
    callback: Option<Arc<dyn MediaControlCallback>>,
}

/// Event types from media controls.
#[derive(Clone, Copy, Debug, uniffi::Enum)]
pub enum MediaControlEventType {
    Play,
    Pause,
    Toggle,
    Next,
    Previous,
    Stop,
    Seek,
    SeekBy,
    SetPosition,
    SetVolume,
    OpenUri,
    Raise,
    Quit,
}

/// Media control event with associated data.
#[derive(Clone, Debug, uniffi::Record)]
pub struct MediaControlEventData {
    pub event_type: MediaControlEventType,
    /// For Seek events: forward (true) or backward (false)
    pub seek_forward: Option<bool>,
    /// For SeekBy events: offset in seconds (can be negative)
    pub seek_offset_secs: Option<f64>,
    /// For SetPosition events: position in seconds
    pub position_secs: Option<f64>,
    /// For SetVolume events: volume (0.0 to 1.0)
    pub volume: Option<f64>,
    /// For OpenUri events: the URI
    pub uri: Option<String>,
}

impl MediaControlEventData {
    fn from_souvlaki(event: SouvlakiEvent) -> Self {
        match event {
            SouvlakiEvent::Play => Self {
                event_type: MediaControlEventType::Play,
                seek_forward: None,
                seek_offset_secs: None,
                position_secs: None,
                volume: None,
                uri: None,
            },
            SouvlakiEvent::Pause => Self {
                event_type: MediaControlEventType::Pause,
                seek_forward: None,
                seek_offset_secs: None,
                position_secs: None,
                volume: None,
                uri: None,
            },
            SouvlakiEvent::Toggle => Self {
                event_type: MediaControlEventType::Toggle,
                seek_forward: None,
                seek_offset_secs: None,
                position_secs: None,
                volume: None,
                uri: None,
            },
            SouvlakiEvent::Next => Self {
                event_type: MediaControlEventType::Next,
                seek_forward: None,
                seek_offset_secs: None,
                position_secs: None,
                volume: None,
                uri: None,
            },
            SouvlakiEvent::Previous => Self {
                event_type: MediaControlEventType::Previous,
                seek_forward: None,
                seek_offset_secs: None,
                position_secs: None,
                volume: None,
                uri: None,
            },
            SouvlakiEvent::Stop => Self {
                event_type: MediaControlEventType::Stop,
                seek_forward: None,
                seek_offset_secs: None,
                position_secs: None,
                volume: None,
                uri: None,
            },
            SouvlakiEvent::Seek(direction) => Self {
                event_type: MediaControlEventType::Seek,
                seek_forward: Some(matches!(direction, SeekDirection::Forward)),
                seek_offset_secs: None,
                position_secs: None,
                volume: None,
                uri: None,
            },
            SouvlakiEvent::SeekBy(direction, duration) => {
                let secs = duration.as_secs_f64();
                let offset = match direction {
                    SeekDirection::Forward => secs,
                    SeekDirection::Backward => -secs,
                };
                Self {
                    event_type: MediaControlEventType::SeekBy,
                    seek_forward: Some(matches!(direction, SeekDirection::Forward)),
                    seek_offset_secs: Some(offset),
                    position_secs: None,
                    volume: None,
                    uri: None,
                }
            }
            SouvlakiEvent::SetPosition(pos) => Self {
                event_type: MediaControlEventType::SetPosition,
                seek_forward: None,
                seek_offset_secs: None,
                position_secs: Some(pos.0.as_secs_f64()),
                volume: None,
                uri: None,
            },
            SouvlakiEvent::SetVolume(vol) => Self {
                event_type: MediaControlEventType::SetVolume,
                seek_forward: None,
                seek_offset_secs: None,
                position_secs: None,
                volume: Some(vol),
                uri: None,
            },
            SouvlakiEvent::OpenUri(uri) => Self {
                event_type: MediaControlEventType::OpenUri,
                seek_forward: None,
                seek_offset_secs: None,
                position_secs: None,
                volume: None,
                uri: Some(uri),
            },
            SouvlakiEvent::Raise => Self {
                event_type: MediaControlEventType::Raise,
                seek_forward: None,
                seek_offset_secs: None,
                position_secs: None,
                volume: None,
                uri: None,
            },
            SouvlakiEvent::Quit => Self {
                event_type: MediaControlEventType::Quit,
                seek_forward: None,
                seek_offset_secs: None,
                position_secs: None,
                volume: None,
                uri: None,
            },
        }
    }
}

/// Playback status for media controls.
#[derive(Clone, Copy, Debug, uniffi::Enum)]
pub enum PlaybackStatus {
    Playing,
    Paused,
    Stopped,
}

/// Callback interface for media control events.
#[uniffi::export(callback_interface)]
pub trait MediaControlCallback: Send + Sync {
    fn on_event(&self, event: MediaControlEventData);
}

fn with_controls_mut<T, F>(id: u64, f: F) -> Result<T, SouvlakiError>
where
    F: FnOnce(&mut ControlsState) -> Result<T, SouvlakiError>,
{
    let mut guard = registry()
        .lock()
        .map_err(|_| SouvlakiError::Internal("registry lock failed".to_string()))?;
    let state = guard
        .get_mut(&id)
        .ok_or(SouvlakiError::ControlsNotFound(id))?;
    f(state)
}

/// Create media controls for Linux (D-Bus MPRIS).
///
/// - `dbus_name`: The D-Bus name for the media player (e.g., "my_player")
/// - `display_name`: The display name shown in the media controls UI
#[cfg(target_os = "linux")]
#[uniffi::export]
pub fn create_media_controls(dbus_name: String, display_name: String) -> Result<u64, SouvlakiError> {
    let config = PlatformConfig {
        dbus_name: &dbus_name,
        display_name: &display_name,
        hwnd: None,
    };

    let controls = MediaControls::new(config)
        .map_err(|_| SouvlakiError::Creation("failed to create media controls".to_string()))?;

    let id = next_id();
    let state = ControlsState {
        controls,
        callback: None,
    };

    let mut guard = registry()
        .lock()
        .map_err(|_| SouvlakiError::Internal("registry lock failed".to_string()))?;
    guard.insert(id, state);

    Ok(id)
}

/// Create media controls for macOS.
///
/// Note: On macOS, you need to run an event loop for events to be received.
#[cfg(target_os = "macos")]
#[uniffi::export]
pub fn create_media_controls(_dbus_name: String, _display_name: String) -> Result<u64, SouvlakiError> {
    let config = PlatformConfig {
        dbus_name: "",
        display_name: "",
        hwnd: None,
    };

    let controls = MediaControls::new(config)
        .map_err(|_| SouvlakiError::Creation("failed to create media controls".to_string()))?;

    let id = next_id();
    let state = ControlsState {
        controls,
        callback: None,
    };

    let mut guard = registry()
        .lock()
        .map_err(|_| SouvlakiError::Internal("registry lock failed".to_string()))?;
    guard.insert(id, state);

    Ok(id)
}

/// Create media controls for Windows.
///
/// - `dbus_name`: Ignored on Windows
/// - `display_name`: Ignored on Windows
///
/// Note: On Windows, HWND handling requires special setup. This creates
/// dummy window-less controls which may have limited functionality.
#[cfg(target_os = "windows")]
#[uniffi::export]
pub fn create_media_controls(_dbus_name: String, _display_name: String) -> Result<u64, SouvlakiError> {
    // On Windows, we need an HWND. For now, we'll try with None which
    // may work for some scenarios or fail gracefully.
    let config = PlatformConfig {
        dbus_name: "",
        display_name: "",
        hwnd: None,
    };

    let controls = MediaControls::new(config)
        .map_err(|_| SouvlakiError::Creation("failed to create media controls".to_string()))?;

    let id = next_id();
    let state = ControlsState {
        controls,
        callback: None,
    };

    let mut guard = registry()
        .lock()
        .map_err(|_| SouvlakiError::Internal("registry lock failed".to_string()))?;
    guard.insert(id, state);

    Ok(id)
}

/// Create media controls for Windows with a window handle.
///
/// - `hwnd`: The window handle (HWND) as a raw pointer value
#[cfg(target_os = "windows")]
#[uniffi::export]
pub fn create_media_controls_with_hwnd(hwnd: u64) -> Result<u64, SouvlakiError> {
    let hwnd_ptr = hwnd as *mut std::ffi::c_void;
    let hwnd_opt = if hwnd_ptr.is_null() {
        None
    } else {
        Some(hwnd_ptr)
    };

    let config = PlatformConfig {
        dbus_name: "",
        display_name: "",
        hwnd: hwnd_opt,
    };

    let controls = MediaControls::new(config)
        .map_err(|_| SouvlakiError::Creation("failed to create media controls".to_string()))?;

    let id = next_id();
    let state = ControlsState {
        controls,
        callback: None,
    };

    let mut guard = registry()
        .lock()
        .map_err(|_| SouvlakiError::Internal("registry lock failed".to_string()))?;
    guard.insert(id, state);

    Ok(id)
}

/// Stub for non-Windows platforms
#[cfg(not(target_os = "windows"))]
#[uniffi::export]
pub fn create_media_controls_with_hwnd(_hwnd: u64) -> Result<u64, SouvlakiError> {
    Err(SouvlakiError::PlatformNotSupported)
}

/// Destroy media controls and release resources.
#[uniffi::export]
pub fn destroy_media_controls(id: u64) -> Result<(), SouvlakiError> {
    let mut guard = registry()
        .lock()
        .map_err(|_| SouvlakiError::Internal("registry lock failed".to_string()))?;

    guard
        .remove(&id)
        .ok_or(SouvlakiError::ControlsNotFound(id))?;

    Ok(())
}

/// Attach a callback to receive media control events.
#[uniffi::export]
pub fn media_controls_attach(
    id: u64,
    callback: Box<dyn MediaControlCallback>,
) -> Result<(), SouvlakiError> {
    let callback_arc: Arc<dyn MediaControlCallback> = Arc::from(callback);
    let callback_clone = callback_arc.clone();

    with_controls_mut(id, |state| {
        state.callback = Some(callback_arc);
        state
            .controls
            .attach(move |event| {
                let data = MediaControlEventData::from_souvlaki(event);
                callback_clone.on_event(data);
            })
            .map_err(|_| SouvlakiError::Attach("failed to attach event handler".to_string()))
    })
}

/// Detach the event callback.
#[uniffi::export]
pub fn media_controls_detach(id: u64) -> Result<(), SouvlakiError> {
    with_controls_mut(id, |state| {
        state.callback = None;
        state
            .controls
            .detach()
            .map_err(|_| SouvlakiError::Detach("failed to detach event handler".to_string()))
    })
}

/// Set media metadata.
///
/// All parameters are optional and can be None.
#[uniffi::export]
pub fn media_controls_set_metadata(
    id: u64,
    title: Option<String>,
    album: Option<String>,
    artist: Option<String>,
    cover_url: Option<String>,
    duration_secs: Option<f64>,
) -> Result<(), SouvlakiError> {
    with_controls_mut(id, |state| {
        let duration = duration_secs.map(|s| std::time::Duration::from_secs_f64(s));

        let metadata = MediaMetadata {
            title: title.as_deref(),
            album: album.as_deref(),
            artist: artist.as_deref(),
            cover_url: cover_url.as_deref(),
            duration,
        };

        state
            .controls
            .set_metadata(metadata)
            .map_err(|_| SouvlakiError::Metadata("failed to set metadata".to_string()))
    })
}

/// Set playback status.
#[uniffi::export]
pub fn media_controls_set_playback(id: u64, status: PlaybackStatus) -> Result<(), SouvlakiError> {
    with_controls_mut(id, |state| {
        let playback = match status {
            PlaybackStatus::Playing => MediaPlayback::Playing { progress: None },
            PlaybackStatus::Paused => MediaPlayback::Paused { progress: None },
            PlaybackStatus::Stopped => MediaPlayback::Stopped,
        };

        state
            .controls
            .set_playback(playback)
            .map_err(|_| SouvlakiError::PlaybackStatus("failed to set playback status".to_string()))
    })
}

/// Set playback status with progress information.
///
/// - `status`: The playback status
/// - `progress_secs`: Current playback position in seconds (optional)
#[uniffi::export]
pub fn media_controls_set_playback_with_progress(
    id: u64,
    status: PlaybackStatus,
    progress_secs: Option<f64>,
) -> Result<(), SouvlakiError> {
    with_controls_mut(id, |state| {
        let progress = progress_secs.map(|s| {
            MediaPosition(std::time::Duration::from_secs_f64(s))
        });

        let playback = match status {
            PlaybackStatus::Playing => MediaPlayback::Playing { progress },
            PlaybackStatus::Paused => MediaPlayback::Paused { progress },
            PlaybackStatus::Stopped => MediaPlayback::Stopped,
        };

        state
            .controls
            .set_playback(playback)
            .map_err(|_| SouvlakiError::PlaybackStatus("failed to set playback status".to_string()))
    })
}

uniffi::setup_scaffolding!();
