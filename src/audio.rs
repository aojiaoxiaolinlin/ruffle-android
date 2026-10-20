use ndk::audio::{AudioDirection, AudioFormat, AudioStream, AudioStreamBuilder, AudioStreamState};
use ruffle_core::backend::audio::{
    swf, AudioBackend, AudioMixer, DecodeError, RegisterError, SoundHandle, SoundInstanceHandle,
    SoundStreamInfo, SoundTransform,
};

use ruffle_core::impl_audio_mixer_backend;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc,
};

pub struct AAudioAudioBackend {
    pub stream: Option<AudioStream>,
    pub mixer: AudioMixer,
    pub paused: bool,
    backgrounded: Arc<AtomicBool>,
}

type Error = Box<dyn std::error::Error>;

impl AAudioAudioBackend {
    pub fn new() -> Result<Self, Error> {
        let mixer = AudioMixer::new(2, 44100);

        let mut result = Self {
            stream: None,
            mixer,
            paused: true,
            backgrounded: Arc::new(AtomicBool::new(false)),
        };

        result.recreate_stream()?;

        Ok(result)
    }

    pub fn recreate_stream(&mut self) -> Result<(), Error> {
        let proxy = self.mixer.proxy();
        let backgrounded = self.backgrounded.clone();

        let stream = AudioStreamBuilder::new()?
            .direction(AudioDirection::Output)
            .format(AudioFormat::PCM_Float)
            .channel_count(2)
            .sample_rate(44100)
            .performance_mode(ndk::audio::AudioPerformanceMode::LowLatency)
            .data_callback(Box::new(move |_stream, data, len| {
                let sl = unsafe {
                    std::slice::from_raw_parts_mut::<f32>(data as *mut f32, len as usize * 2)
                };
                proxy.mix(sl);
                // Advance stream clocks normally so audio synchronization cannot
                // stall frame-driven heartbeats, but emit silence while hidden.
                if backgrounded.load(Ordering::Relaxed) {
                    sl.fill(0.0);
                }
                ndk::audio::AudioCallbackResult::Continue
            }))
            .open_stream()?;

        if !self.paused {
            stream.request_start()?;
        }

        self.stream = Some(stream);
        Ok(())
    }

    pub fn set_backgrounded(&mut self, backgrounded: bool) {
        self.backgrounded.store(backgrounded, Ordering::Relaxed);
    }

    pub fn recreate_stream_if_needed(&mut self) {
        let stream_state = self.stream.as_ref().unwrap().state();
        if stream_state == AudioStreamState::Disconnected {
            // I'm sure it's fine...
            let _ = self.recreate_stream();
        }
    }
}

impl AudioBackend for AAudioAudioBackend {
    impl_audio_mixer_backend!(mixer);

    fn play(&mut self) {
        self.paused = false;
        if let Some(stream) = self.stream.as_mut() {
            if let Err(error) = stream.request_start() {
                log::warn!("Could not resume audio stream: {error}");
            }
        }
    }

    fn pause(&mut self) {
        self.paused = true;
        if let Some(stream) = self.stream.as_mut() {
            if let Err(error) = stream.request_pause() {
                log::warn!("Could not pause audio stream: {error}");
            }
        }
    }
}
