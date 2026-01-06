//! Rodio Kotlin bindings via UniFFI.

mod error;
mod state;

/// Initialize the Android NDK context when the library is loaded.
/// This is required for cpal/rodio to access Android audio APIs.
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "C" fn JNI_OnLoad(
    vm: jni::JavaVM,
    _res: *mut std::os::raw::c_void,
) -> jni::sys::jint {
    use std::ffi::c_void;
    let vm_ptr = vm.get_java_vm_pointer() as *mut c_void;
    unsafe {
        ndk_context::initialize_android_context(vm_ptr, std::ptr::null_mut());
    }
    jni::JNIVersion::V6.into()
}

use std::fs::File;
use std::io::BufReader;
use std::time::Duration;

use rodio::decoder::Decoder;
use rodio::source::SineWave;
use rodio::Source;

pub use error::RodioError;
use state::{register, unregister, with_player, PlayerState};

#[uniffi::export]
pub fn create_player() -> Result<u64, RodioError> {
    let player = PlayerState::new()?;
    Ok(register(player))
}

#[uniffi::export]
pub fn destroy_player(id: u64) -> Result<(), RodioError> {
    unregister(id)
}

#[uniffi::export]
pub fn player_play_file(id: u64, path: String, looped: bool) -> Result<(), RodioError> {
    with_player(id, |state| {
        let file = File::open(path)?;
        let decoder = Decoder::try_from(BufReader::new(file))?;
        if looped {
            state.sink.append(decoder.repeat_infinite());
        } else {
            state.sink.append(decoder);
        }
        Ok(())
    })
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
    with_player(id, |state| {
        let source = SineWave::new(frequency_hz)
            .take_duration(Duration::from_millis(duration_ms));
        state.sink.append(source);
        Ok(())
    })
}

#[uniffi::export]
pub fn player_play(id: u64) -> Result<(), RodioError> {
    with_player(id, |state| {
        state.sink.play();
        Ok(())
    })
}

#[uniffi::export]
pub fn player_pause(id: u64) -> Result<(), RodioError> {
    with_player(id, |state| {
        state.sink.pause();
        Ok(())
    })
}

#[uniffi::export]
pub fn player_stop(id: u64) -> Result<(), RodioError> {
    with_player(id, |state| {
        state.sink.stop();
        Ok(())
    })
}

#[uniffi::export]
pub fn player_clear(id: u64) -> Result<(), RodioError> {
    with_player(id, |state| {
        state.sink.clear();
        Ok(())
    })
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
