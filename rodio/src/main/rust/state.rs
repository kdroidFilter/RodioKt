//! Rodio player registry and state.

use std::cell::RefCell;
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};

use rodio::{OutputStream, OutputStreamBuilder, Sink};

use crate::error::RodioError;

pub struct PlayerState {
    pub _stream: OutputStream,
    pub sink: Sink,
}

impl PlayerState {
    pub fn new() -> Result<Self, RodioError> {
        let stream = OutputStreamBuilder::open_default_stream()?;
        let sink = Sink::connect_new(stream.mixer());
        Ok(Self { _stream: stream, sink })
    }
}

static NEXT_ID: AtomicU64 = AtomicU64::new(1);
thread_local! {
    static PLAYERS: RefCell<HashMap<u64, PlayerState>> = RefCell::new(HashMap::new());
}

fn next_id() -> u64 {
    NEXT_ID.fetch_add(1, Ordering::Relaxed)
}

pub fn register(player: PlayerState) -> u64 {
    let id = next_id();
    PLAYERS.with(|players| {
        players.borrow_mut().insert(id, player);
    });
    id
}

pub fn with_player<F, R>(id: u64, f: F) -> Result<R, RodioError>
where
    F: FnOnce(&PlayerState) -> Result<R, RodioError>,
{
    PLAYERS.with(|players| {
        let map = players
            .try_borrow()
            .map_err(|_| RodioError::Internal("player registry borrow failed".to_string()))?;
        let entry = map.get(&id).ok_or(RodioError::PlayerNotFound(id))?;
        f(entry)
    })
}

pub fn unregister(id: u64) -> Result<(), RodioError> {
    PLAYERS.with(|players| {
        let mut map = players
            .try_borrow_mut()
            .map_err(|_| RodioError::Internal("player registry borrow failed".to_string()))?;
        if map.remove(&id).is_some() {
            Ok(())
        } else {
            Err(RodioError::PlayerNotFound(id))
        }
    })
}
