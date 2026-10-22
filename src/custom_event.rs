//! Custom event type for Ruffle on Android

use ruffle_core::events::KeyDescriptor;
use ruffle_render::quality::StageQuality;

use crate::PlayerRunnable;

/// User-defined events.
pub enum RuffleEvent {
    /// Indicates that a task is ready to be polled.
    TaskPoll(PlayerRunnable),
    VirtualKeyEvent {
        down: bool,
        key_descriptor: KeyDescriptor,
    },
    TextInput(String),
    DeleteText {
        before: u32,
        after: u32,
        codepoints: bool,
    },
    InputKey {
        code: u32,
        down: bool,
    },
    RunContextMenuCallback(usize),
    ClearContextMenu,
    RequestContextMenu,
    SetQuality(StageQuality),
    /// Lock playback to a frame rate, or follow the SWF when `None`.
    SetFrameRate(Option<f64>),
    /// Glow/drop-shadow blur branch resolution: 50, 75 or 100 percent.
    SetSoftFilterScale(u32),
}
