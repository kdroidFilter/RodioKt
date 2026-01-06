//! Rodio player registry and state.

use std::cell::RefCell;
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use rodio::{OutputStream, OutputStreamBuilder, Sink};

use crate::error::RodioError;
use crate::PlaybackCallback;

pub struct PlayerState {
    pub sink: Sink,
    pub callback: Option<Arc<dyn PlaybackCallback>>,
}

impl PlayerState {
    pub fn new() -> Result<(Self, OutputStream), RodioError> {
        let stream = OutputStreamBuilder::open_default_stream()?;
        let sink = Sink::connect_new(stream.mixer());
        Ok((
            Self {
                sink,
                callback: None,
            },
            stream,
        ))
    }
}

static NEXT_ID: AtomicU64 = AtomicU64::new(1);
static PLAYERS: OnceLock<Mutex<HashMap<u64, PlayerState>>> = OnceLock::new();
thread_local! {
    static STREAMS: RefCell<HashMap<u64, OutputStream>> = RefCell::new(HashMap::new());
}

fn players() -> &'static Mutex<HashMap<u64, PlayerState>> {
    PLAYERS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn next_id() -> u64 {
    NEXT_ID.fetch_add(1, Ordering::Relaxed)
}

pub fn register(player: PlayerState, stream: OutputStream) -> u64 {
    let id = next_id();
    let mut map = players()
        .lock()
        .unwrap_or_else(|err| err.into_inner());
    map.insert(id, player);
    STREAMS.with(|streams| {
        streams.borrow_mut().insert(id, stream);
    });
    id
}

pub fn with_player<F, R>(id: u64, f: F) -> Result<R, RodioError>
where
    F: FnOnce(&PlayerState) -> Result<R, RodioError>,
{
    let map = players()
        .lock()
        .map_err(|_| RodioError::Internal("player registry lock failed".to_string()))?;
    let entry = map.get(&id).ok_or(RodioError::PlayerNotFound(id))?;
    f(entry)
}

pub fn with_player_mut<F, R>(id: u64, f: F) -> Result<R, RodioError>
where
    F: FnOnce(&mut PlayerState) -> Result<R, RodioError>,
{
    let mut map = players()
        .lock()
        .map_err(|_| RodioError::Internal("player registry lock failed".to_string()))?;
    let entry = map.get_mut(&id).ok_or(RodioError::PlayerNotFound(id))?;
    f(entry)
}

pub fn unregister(id: u64) -> Result<(), RodioError> {
    let mut map = players()
        .lock()
        .map_err(|_| RodioError::Internal("player registry lock failed".to_string()))?;
    let existed = map.remove(&id).is_some();
    STREAMS.with(|streams| {
        streams.borrow_mut().remove(&id);
    });
    if existed {
        Ok(())
    } else {
        Err(RodioError::PlayerNotFound(id))
    }
}
