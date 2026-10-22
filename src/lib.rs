mod audio;
mod custom_event;
mod fonts;
mod java;
mod keycodes;
mod navigator;
mod trace;
mod ui;

use custom_event::RuffleEvent;

use jni::{
    errors::ThrowRuntimeExAndDefault,
    jni_sig, jni_str,
    objects::{JObject, JString},
    sys::{self, jint, jobject},
    Env, EnvUnowned, JavaVM,
};
use keycodes::{android_key_event_to_ruffle_key_descriptor, key_tag_to_key_descriptor};
use std::any::Any;
use std::rc::Rc;
use std::sync::mpsc::Sender;
use std::sync::{mpsc, MutexGuard};
use std::time::Duration;
use std::{
    fs::OpenOptions,
    io::Write,
    panic,
    path::PathBuf,
    sync::{Arc, Mutex, OnceLock},
    thread,
    time::Instant,
};
use wgpu::rwh::{AndroidDisplayHandle, HasWindowHandle, RawDisplayHandle};

use android_activity::input::{InputEvent, KeyAction, MotionAction};
use android_activity::{AndroidApp, AndroidAppWaker, InputStatus, MainEvent, PollEvent};
use backtrace::Backtrace;
use jni::objects::JClass;

use audio::AAudioAudioBackend;
use url::Url;

use ruffle_common::duration::FloatDuration;
use ruffle_core::{
    backend::navigator::OwnedFuture,
    events::{LogicalKey, MouseButton, NamedKey, PlayerEvent, TextControlCode},
    tag_utils::SwfMovie,
    Player, PlayerBuilder, ViewportDimensions,
};
use ruffle_frontend_utils::backends::storage::DiskStorageBackend;
use ruffle_frontend_utils::content::PlayingContent;
use ruffle_frontend_utils::{
    backends::navigator::{ExternalNavigatorBackend, FutureSpawner},
    content::ContentDescriptor,
};
use ruffle_render::quality::StageQuality;

use crate::navigator::AndroidNavigatorInterface;
use crate::trace::FileLogBackend;
use java::JavaInterface;
use ruffle_render_wgpu::{backend::WgpuRenderBackend, target::SwapChainTarget};

/// A unique identifier for a given `Player` instance.
/// Used to track which player any currently executing future is bound to.
#[derive(Copy, Clone, Eq, PartialEq)]
struct PlayerId(i64);

impl PlayerId {
    fn new() -> Self {
        use std::sync::atomic::{AtomicI64, Ordering};

        static NEXT: AtomicI64 = AtomicI64::new(0);
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        assert!(id >= 0, "PlayerId overflowed!");
        Self(id)
    }
}

/// Maps the value stored by the Android settings UI to a Ruffle stage quality.
/// Anything unexpected falls back to `Medium`, which disables 4x MSAA.
/// Loader completions decode images and create/upload textures synchronously.
/// Handling too many in one frame causes large memory spikes in the Adreno
/// driver, so spread a burst over a few frames instead of exhausting memory.
const MAX_EVENTS_PER_ITERATION: usize = 8;
const DETAILED_DIAGNOSTICS: bool = cfg!(feature = "diagnostics");
const PERF_LOG_INTERVAL: Duration = if DETAILED_DIAGNOSTICS {
    Duration::from_secs(1)
} else {
    Duration::from_secs(5)
};

/// Set once the player starts, so the panic hook can persist its message even
/// when Android logcat output is encrypted by the vendor.
static PANIC_LOG_PATH: OnceLock<PathBuf> = OnceLock::new();

fn process_memory_summary() -> String {
    let status = std::fs::read_to_string("/proc/self/status").unwrap_or_default();
    let fields: Vec<_> = status
        .lines()
        .filter(|line| {
            [
                "VmRSS:", "VmHWM:", "RssAnon:", "RssFile:", "VmSwap:", "Threads:",
            ]
            .iter()
            .any(|key| line.starts_with(key))
        })
        .collect();
    format!("process-memory: {}", fields.join("; "))
}

fn stage_quality_from_int(value: i32) -> StageQuality {
    match value {
        0 => StageQuality::Low,
        2 => StageQuality::High,
        _ => StageQuality::Medium,
    }
}

/// A `Player`-bound future that is currently running.
pub struct PlayerRunnable(async_task::Runnable<PlayerId>);

/// Represents a current Player and any associated state with that player,
/// which may be lost when this Player is closed (dropped)
struct ActivePlayer {
    id: PlayerId,
    player: Arc<Mutex<Player>>,
}

#[derive(Clone)]
pub struct EventSender {
    sender: Sender<RuffleEvent>,
    waker: AndroidAppWaker,
}

impl EventSender {
    pub fn send(&self, event: RuffleEvent) {
        if send_event_or_keep_local_task(&self.sender, event) {
            self.waker.wake();
        }
    }
}

/// A local task must be dropped on the thread that spawned it. During Android
/// shutdown a Tokio worker may wake a task after the event receiver is gone.
/// There is no owner thread left to dispose of that task, so leave it for
/// process teardown rather than aborting in async-task's thread check.
fn send_event_or_keep_local_task(sender: &Sender<RuffleEvent>, event: RuffleEvent) -> bool {
    match sender.send(event) {
        Ok(()) => true,
        Err(std::sync::mpsc::SendError(RuffleEvent::TaskPoll(task))) => {
            std::mem::forget(task);
            false
        }
        Err(_) => false,
    }
}

/// A bare-bones executor that schedules tasks on the winit event loop.
struct AndroidExecutor {
    event_loop: EventSender,
    player_id: PlayerId,
}

impl<E: std::error::Error + 'static> FutureSpawner<E> for AndroidExecutor {
    fn spawn(&self, future: OwnedFuture<(), E>) {
        // Discard any errors.
        let future = async {
            if let Err(e) = future.await {
                tracing::error!("Async error: {}", e);
            }
        };

        let event_loop = self.event_loop.clone();
        let scheduler = move |task| {
            let event = RuffleEvent::TaskPoll(PlayerRunnable(task));
            event_loop.send(event)
        };

        let (runnable, task) = async_task::Builder::new()
            .metadata(self.player_id)
            .spawn_local(|_| future, scheduler);

        // The future should run in the background.
        task.detach();
        // Immediately schedule the future to be polled for the first time.
        runnable.schedule();
    }
}

#[tokio::main]
async fn run(app: AndroidApp) {
    let mut last_frame_time = Instant::now();
    let mut next_frame_time = Some(Instant::now());
    let mut quit = false;
    let mut is_resumed = false;
    let (sender, receiver) = mpsc::channel::<RuffleEvent>();
    let mut native_window: Option<ndk::native_window::NativeWindow> = None;
    let mut playerbox: Option<ActivePlayer> = None;
    let sender = EventSender {
        sender,
        waker: app.create_waker(),
    };

    // Aggregated once per second so logcat stays readable.
    let mut perf_window_start = Instant::now();
    let mut perf_ticks = 0u32;
    let mut perf_renders = 0u32;
    let mut perf_tick_time = Duration::ZERO;
    let mut perf_render_time = Duration::ZERO;
    let mut perf_tick_max = Duration::ZERO;
    let mut perf_render_max = Duration::ZERO;
    let mut last_texture_pool_bytes = 0u64;
    let mut last_bitmap_uploaded_bytes = 0u64;
    let mut last_bitmap_target_bytes = 0u64;
    let mut last_bitmap_cache_bytes = 0u64;
    let mut last_pressure_flushes = 0u64;

    log::info!("Starting event loop...");
    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr() as *mut sys::JavaVM) };
    // This is a global reference, so it can only be borrowed, not wrapped as a local one.
    let activity_raw = app.activity_as_ptr() as jobject;

    let (trace_output, android_storage_dir, external_files_dir) = vm
        .attach_current_thread(|env| -> jni::errors::Result<_> {
            let activity = unsafe { env.as_cast_raw::<JObject>(&activity_raw) }?;
            let trace_output = JavaInterface::get_trace_output(env, &activity);
            let android_storage_dir = JavaInterface::get_android_data_storage_dir(env, &activity);
            let external_files_dir = JavaInterface::get_external_files_dir_path(env, &activity);
            let _ = unsafe {
                env.set_rust_field(&*activity, jni_str!("eventLoopHandle"), sender.clone())
            };
            // Lets reqwest verify server certificates using Android's trust store.
            let context = env.new_local_ref(&*activity)?;
            rustls_platform_verifier::android::init_with_env(env, context)?;
            Ok((trace_output, android_storage_dir, external_files_dir))
        })
        .expect("JNI calls on the main thread must succeed");

    // Some Android OEMs (e.g. Honor) encrypt logcat output for non-debuggable
    // apps, so mirror the same diagnostics into a file adb can pull.
    let perf_log_path = external_files_dir.map(|dir| dir.join("perf.log"));
    if let Some(path) = &perf_log_path {
        // Keep the previous session so a crash that killed the process can
        // still be inspected from the settings' "share diagnostics" action.
        let previous = path.with_file_name("perf-previous.log");
        let _ = std::fs::remove_file(&previous);
        let _ = std::fs::rename(path, &previous);
        let _ = PANIC_LOG_PATH.set(path.clone());
    }
    let mut perf_file = perf_log_path
        .as_ref()
        .and_then(|path| OpenOptions::new().create(true).append(true).open(path).ok());

    while !quit {
        let mut needs_redraw = false;
        // The frame timer starts when the frame is due, not when the previous
        // poll started: rendering time must not be added to the next wait.
        app.poll_events(
            Some(
                next_frame_time
                    .map(|next| next.saturating_duration_since(Instant::now()))
                    .unwrap_or_else(|| Duration::from_millis(100)),
            ),
            |event| {
                match event {
                    PollEvent::Main(event) => match event {
                        MainEvent::Destroy => {
                            if let Some(player) = playerbox.as_ref() {
                                let mut player_lock = player.player.lock().unwrap();
                                player_lock.flush_shared_objects();
                            }
                            quit = true;
                        }
                        MainEvent::WindowResized { .. } => {
                            if let Some(player) = playerbox.as_ref() {
                                let mut player_lock = player.player.lock().unwrap();
                                let Some(window) = native_window.as_ref() else {
                                    return;
                                };
                                if window.width() <= 0 || window.height() <= 0 {
                                    return;
                                }
                                log::info!(
                                    "WindowResized: {} x {}",
                                    window.width(),
                                    window.height()
                                );
                                let viewport_scale_factor = app
                                    .config()
                                    .density()
                                    .map(|dpi| dpi as f64 / 160.0)
                                    .unwrap_or(1.0);
                                let dimensions = ViewportDimensions {
                                    width: window.width() as u32,
                                    height: window.height() as u32,
                                    scale_factor: viewport_scale_factor,
                                };
                                player_lock.set_viewport_dimensions(dimensions);
                                needs_redraw = true;
                            }
                        }
                        MainEvent::Resume { .. } => {
                            is_resumed = true;
                            if let Some(player) = playerbox.as_ref() {
                                let mut player = player.player.lock().unwrap();
                                <dyn Any>::downcast_mut::<AAudioAudioBackend>(player.audio_mut())
                                    .unwrap().set_backgrounded(false);
                            }
                        }
                        MainEvent::Pause => {
                            is_resumed = false;
                            if let Some(player) = playerbox.as_ref() {
                                let mut player = player.player.lock().unwrap();
                                <dyn Any>::downcast_mut::<AAudioAudioBackend>(player.audio_mut())
                                    .unwrap().set_backgrounded(true);
                            }
                        }
                        MainEvent::InitWindow { .. } => {
                            native_window = app.native_window();
                            let window = native_window
                                .as_ref()
                                .expect("native_window should be Some after InitWindow");
                            let viewport_scale_factor = app
                                .config()
                                .density()
                                .map(|dpi| dpi as f64 / 160.0)
                                .unwrap_or(1.0);
                            let dimensions = ViewportDimensions {
                                width: window.width() as u32,
                                height: window.height() as u32,
                                scale_factor: viewport_scale_factor,
                            };
                            log::info!(
                                "Init window: {} x {} (is existing: {})",
                                window.width(),
                                window.height(),
                                playerbox.is_some()
                            );
                            if let Ok((view_width, view_height)) = get_view_size(&app) {
                                log::info!(
                                    target: "ruffle",
                                    "view size: {view_width} x {view_height} (buffer {} x {})",
                                    window.width(),
                                    window.height()
                                );
                                if let Some(path) = &perf_log_path {
                                    if let Ok(mut file) =
                                        OpenOptions::new().create(true).append(true).open(path)
                                    {
                                        let _ = writeln!(
                                            file,
                                            "view size: {view_width} x {view_height} (buffer {} x {})",
                                            window.width(),
                                            window.height()
                                        );
                                    }
                                }
                            }

                            if let Some(activeplayer) = &playerbox {
                                let mut player_lock = activeplayer.player.lock().unwrap();
                                unsafe {
                                    let renderer = <dyn Any>::downcast_mut::<WgpuRenderBackend<SwapChainTarget>>(
                                        player_lock.renderer_mut(),
                                    )
                                    .unwrap();

                                    renderer.recreate_surface_unsafe(
                                        wgpu::SurfaceTargetUnsafe::RawHandle {
                                            raw_display_handle: Some(RawDisplayHandle::Android(
                                                AndroidDisplayHandle::new(),
                                            )),
                                            raw_window_handle: window
                                                .window_handle()
                                                .unwrap()
                                                .into(),
                                        },
                                        (window.width() as u32, window.height() as u32),
                                    )
                                    .unwrap();
                                }
                            } else {
                                let (url, bytes, base_url, stage_quality, graphics_backend, frame_rate_override, soft_filter_scale) = with_activity(&app, |env, activity| {
                                    (
                                        JavaInterface::get_swf_uri(env, activity),
                                        JavaInterface::get_swf_bytes(env, activity),
                                        JavaInterface::get_base_url(env, activity),
                                        JavaInterface::get_stage_quality(env, activity),
                                        JavaInterface::get_graphics_backend(env, activity),
                                        JavaInterface::get_frame_rate_override(env, activity),
                                        JavaInterface::get_soft_filter_scale(env, activity),
                                    )
                                })
                                .unwrap();
                                // 0 means "follow the SWF"; anything else locks playback to
                                // that rate (the Android UI caps it at 60 fps).
                                let frame_rate_override =
                                    (frame_rate_override > 0).then_some(f64::from(frame_rate_override));
                                // TODO: make this take an Arc<Window> instead?
                                let create_renderer = |backends: wgpu::Backends| unsafe {
                                    WgpuRenderBackend::for_window_unsafe(
                                        wgpu::SurfaceTargetUnsafe::RawHandle {
                                            raw_display_handle: Some(RawDisplayHandle::Android(
                                                AndroidDisplayHandle::new(),
                                            )),
                                            raw_window_handle: window
                                                .window_handle()
                                                .unwrap()
                                                .into(),
                                        },
                                        (dimensions.width, dimensions.height),
                                        backends,
                                        wgpu::PowerPreference::HighPerformance,
                                        None,
                                    )
                                };
                                // GLES is the stable default. Vulkan is faster on
                                // some devices but currently crashes in the
                                // Adreno 750 driver while recording command
                                // buffers, so it is opt-in from the settings.
                                let mut renderer = if graphics_backend == 1 {
                                    match create_renderer(wgpu::Backends::VULKAN) {
                                        Ok(renderer) => {
                                            log::info!("Using wgpu Vulkan backend (experimental)");
                                            renderer
                                        }
                                        Err(error) => {
                                            log::warn!(
                                                "Vulkan unavailable ({error}); falling back to GLES"
                                            );
                                            create_renderer(wgpu::Backends::GL).unwrap()
                                        }
                                    }
                                } else {
                                    log::info!("Using wgpu GLES backend");
                                    create_renderer(wgpu::Backends::GL).unwrap()
                                };
                                renderer.set_quality_limit(Some(stage_quality_from_int(stage_quality)));
                                let soft_filter_scale = soft_filter_scale as u32;
                                if !renderer.set_soft_filter_scale(soft_filter_scale) {
                                    log::warn!(
                                        "Invalid soft filter scale {soft_filter_scale}; using 100%"
                                    );
                                    let _ = renderer.set_soft_filter_scale(100);
                                }
                                log::info!(
                                    target: "ruffle",
                                    "soft filter scale: {soft_filter_scale}%"
                                );
                                let movie_url = Url::parse(&url).unwrap();
                                let base_url = base_url
                                    .and_then(|base| Url::parse(&base).ok())
                                    .filter(|base| matches!(base.scheme(), "http" | "https") && base.has_host())
                                    .unwrap_or_else(|| movie_url.clone());
                                let player_id = PlayerId::new();

                                let future_spawner = AndroidExecutor {
                                    event_loop: sender.clone(),
                                    player_id,
                                };

                                let navigator = ExternalNavigatorBackend::new(
                                    base_url,
                                    None,
                                    None,
                                    future_spawner,
                                    None,
                                    false,
                                    Default::default(),
                                    ruffle_core::backend::navigator::SocketMode::Allow,
                                    Rc::new(PlayingContent::DirectFile(ContentDescriptor::new_remote(movie_url))),
                                    AndroidNavigatorInterface,
                                );

                                playerbox = Some(ActivePlayer {
                                    id: player_id,
                                    player: PlayerBuilder::new()
                                            .with_renderer(renderer)
                                            .with_quality(stage_quality_from_int(stage_quality))
                                            .with_frame_rate(frame_rate_override)
                                            .with_audio(AAudioAudioBackend::new().unwrap())
                                            .with_storage(Box::new(DiskStorageBackend::new(android_storage_dir.clone())))
                                            .with_navigator(navigator)
                                            .with_ui(ui::AndroidUiBackend::new(app.clone()))
                                            .with_log(FileLogBackend::new(trace_output.as_deref()))
                                            .with_video(
                                                ruffle_video_software::backend::SoftwareVideoBackend::new(),
                                            )
                                        .build(),
                                    }
                                );

                                let player = &playerbox.as_ref().unwrap().player;
                                let mut player_lock = player.lock().unwrap();
                                player_lock.set_render_cache_idle_limit(Some(std::time::Duration::from_secs(90)));
                                let renderer_info = player_lock.renderer_mut().debug_info();
                                log::info!(target: "ruffle", "renderer:\n{renderer_info}");
                                let render_scale =
                                    with_activity(&app, JavaInterface::get_render_scale).unwrap();
                                log::info!(target: "ruffle", "render scale: {render_scale:.2}");
                                log::info!(target: "ruffle", "frame rate override: {frame_rate_override:?}");
                                if let Some(path) = &perf_log_path {
                                    if let Ok(mut file) =
                                        OpenOptions::new().create(true).append(true).open(path)
                                    {
                                        let _ = writeln!(
                                            file,
                                            "=== renderer ===\n{renderer_info}\nrender_scale={render_scale:.2}\nframe_rate_override={frame_rate_override:?}\nsoft_filter_scale={soft_filter_scale}%"
                                        );
                                    }
                                }
                                for (font, families) in [
                                    (ruffle_core::font::DefaultFont::Sans, vec!["Roboto", "Noto Sans", "Noto Sans CJK SC"]),
                                    (ruffle_core::font::DefaultFont::Serif, vec!["Noto Serif", "Noto Serif CJK SC", "Noto Sans CJK SC"]),
                                    (ruffle_core::font::DefaultFont::Typewriter, vec!["Droid Sans Mono", "Noto Sans Mono", "Noto Sans CJK SC"]),
                                ] {
                                    player_lock.set_default_font(font, families.into_iter().map(str::to_owned).collect());
                                }
                                if let Some(bytes) = bytes {
                                    let movie = SwfMovie::from_data(&bytes, url, None, None).unwrap();
                                    player_lock.mutate_with_update_context(|context| {
                                        context.set_root_movie(movie);
                                    });
                                } else {
                                    player_lock.fetch_root_movie(url, Vec::new(), Box::new(|_| {}))
                                }
                                player_lock.set_is_playing(true); // Desktop player will auto-play.

                                player_lock.set_letterbox(ruffle_core::config::Letterbox::On);

                                player_lock.set_viewport_dimensions(dimensions);

                                last_frame_time = Instant::now();
                                next_frame_time = Some(Instant::now());

                                log::info!("MOVIE STARTED");
                            }
                        }
                        MainEvent::TerminateWindow { .. } => {
                            if let Some(player) = playerbox.as_ref() {
                                let mut player = player.player.lock().unwrap();
                                let renderer = <dyn Any>::downcast_mut::<WgpuRenderBackend<SwapChainTarget>>(
                                    player.renderer_mut(),
                                ).unwrap();
                                renderer.suspend_surface();
                            }
                            native_window = None;
                            // Keep ticking: SWF timers and socket responses drive game heartbeats.
                        }
                        MainEvent::InputAvailable => {
                            if let Ok(mut inputs) = app.input_events_iter() {
                                while inputs.next(|input| match input {
                                    InputEvent::MotionEvent(event) => {
                                        let Some(window) = native_window.as_ref() else {
                                            return InputStatus::Unhandled;
                                        };
                                        let pointer = event.pointer_index();
                                        let pointer = event.pointer_at_index(pointer);
                                        let coords: (i32, i32) = get_loc_in_window(&app);
                                        let mut x = pointer.x() as f64 - coords.0 as f64;
                                        let mut y = pointer.y() as f64 - coords.1 as f64;
                                        let view_size = get_view_size(&app).unwrap();
                                        if view_size.0 <= 0 || view_size.1 <= 0 {
                                            return InputStatus::Unhandled;
                                        }
                                        x = x * window.width() as f64 / view_size.0 as f64;
                                        y = y * window.height() as f64 / view_size.1 as f64;
                                        let ruffle_event = match event.action() {
                                            MotionAction::Down | MotionAction::PointerDown | MotionAction::ButtonPress => {
                                                PlayerEvent::MouseDown {
                                                    x,
                                                    y,
                                                    button: MouseButton::Left, // TODO
                                                    index: None, // TODO
                                                }
                                            }
                                            MotionAction::Up | MotionAction::PointerUp | MotionAction::ButtonRelease => {
                                                PlayerEvent::MouseUp {
                                                    x,
                                                    y,
                                                    button: MouseButton::Left, // TODO
                                                }
                                            }
                                            MotionAction::Move => PlayerEvent::MouseMove { x, y },
                                            _ => return InputStatus::Unhandled,
                                        };

                                        if let Some(player) = playerbox.as_ref() {
                                            player
                                                .player
                                                .lock()
                                                .unwrap()
                                                .handle_event(ruffle_event);
                                        }

                                        InputStatus::Handled
                                    }
                                    InputEvent::KeyEvent(event) => {
                                        if let Some(player) = playerbox.as_ref() {
                                            let Some(key_descriptor) =
                                                android_key_event_to_ruffle_key_descriptor(event)
                                            else {
                                                return InputStatus::Unhandled;
                                            };
                                            let down;
                                            let ruffle_event = match event.action() {
                                                KeyAction::Down => {
                                                    down = true;
                                                    PlayerEvent::KeyDown {
                                                        key: key_descriptor,
                                                    }
                                                }
                                                KeyAction::Up => {
                                                    down = false;
                                                    PlayerEvent::KeyUp { key: key_descriptor }
                                                }
                                                _ => return InputStatus::Unhandled,
                                            };
                                            player
                                                .player
                                                .lock()
                                                .unwrap()
                                                .handle_event(ruffle_event);

                                            // TODO: Use `KeyEvent.unicode_char` when it's available:
                                            // https://github.com/rust-mobile/android-activity/issues/183
                                            if down {
                                                if let LogicalKey::Character(c) = key_descriptor.logical_key {
                                                    let event = PlayerEvent::TextInput { codepoint: c };
                                                    player.player.lock().unwrap().handle_event(event);
                                                }
                                            };

                                            needs_redraw = true;
                                        }

                                        InputStatus::Handled
                                    }
                                    _ => InputStatus::Unhandled,
                                }) {}
                            }
                        }
                        _ => {} // Something else happened but it's probably not important for now.
                    },
                    PollEvent::Wake => {} // A task tried to wake us, we'll recv it below
                    PollEvent::Timeout => {} // No events happened, we'll tick as normal below
                    _ => {}               // Unknown future event
                }
            },
        );

        if quit {
            break;
        }

        // Drain everything that is already queued. Loader completions arrive as
        // individual TaskPoll events; handling only one per loop iteration made
        // item icons appear one frame at a time.
        let mut processed_events = 0;
        while processed_events < MAX_EVENTS_PER_ITERATION {
            let event = match receiver.try_recv() {
                Ok(event) => event,
                Err(_) => break,
            };
            processed_events += 1;

            match event {
                RuffleEvent::TaskPoll(task) => {
                    // Only run the task if it matches our current player;
                    // otherwise it is stale, and should be cancelled (which
                    // happens implicitly on drop).
                    if let Some(player) = playerbox.as_ref() {
                        if *task.0.metadata() == player.id {
                            task.0.run();
                        }
                    }
                }
                RuffleEvent::VirtualKeyEvent {
                    down,
                    key_descriptor,
                } => {
                    if let Some(player) = playerbox.as_ref() {
                        let event = if down {
                            PlayerEvent::KeyDown {
                                key: key_descriptor,
                            }
                        } else {
                            PlayerEvent::KeyUp {
                                key: key_descriptor,
                            }
                        };
                        player.player.lock().unwrap().handle_event(event);

                        if down {
                            // TODO: Add shift/capslock and pass in uppercase characters accordingly
                            if let LogicalKey::Character(c) = key_descriptor.logical_key {
                                let event = PlayerEvent::TextInput { codepoint: c };
                                player.player.lock().unwrap().handle_event(event);
                            }
                        }
                    }
                }
                RuffleEvent::TextInput(text) => {
                    if let Some(player) = playerbox.as_ref() {
                        let mut player = player.player.lock().unwrap();
                        for codepoint in text.chars() {
                            player.handle_event(PlayerEvent::TextInput { codepoint });
                        }
                        needs_redraw = true;
                    }
                }
                RuffleEvent::DeleteText {
                    before,
                    after,
                    codepoints,
                } => {
                    if let Some(player) = playerbox.as_ref() {
                        let mut player = player.player.lock().unwrap();
                        // Android's legacy delete API counts UTF-16 units; Flash deletes
                        // whole Unicode characters. Convert using the actual Flash caret.
                        let (before, after) = if codepoints {
                            (before, after)
                        } else {
                            player.mutate_with_update_context(|context| {
                                let Some(field) = context.focus_tracker.get_as_edit_text() else {
                                    return (0, 0);
                                };
                                let Some(selection) = field.selection() else {
                                    return (0, 0);
                                };
                                let text = field.text();
                                let left = text
                                    .slice(..selection.start())
                                    .unwrap_or_default()
                                    .to_utf8_lossy();
                                let right = text
                                    .slice(selection.end()..)
                                    .unwrap_or_default()
                                    .to_utf8_lossy();
                                (
                                    count_utf16_characters(left.chars().rev(), before),
                                    count_utf16_characters(right.chars(), after),
                                )
                            })
                        };
                        for (count, code) in [
                            (before, TextControlCode::Backspace),
                            (after, TextControlCode::Delete),
                        ] {
                            for _ in 0..count {
                                player.handle_event(PlayerEvent::TextControl { code });
                            }
                        }
                        needs_redraw = true;
                    }
                }
                RuffleEvent::InputKey { code, down } => {
                    if let (Some(player), Some(key)) = (
                        playerbox.as_ref(),
                        keycodes::android_keycode_to_ruffle_key_descriptor(code.into()),
                    ) {
                        let mut player = player.player.lock().unwrap();
                        player.handle_event(if down {
                            PlayerEvent::KeyDown { key }
                        } else {
                            PlayerEvent::KeyUp { key }
                        });
                        if down {
                            let control = match key.logical_key {
                                LogicalKey::Named(NamedKey::Backspace) => {
                                    Some(TextControlCode::Backspace)
                                }
                                LogicalKey::Named(NamedKey::Delete) => {
                                    Some(TextControlCode::Delete)
                                }
                                LogicalKey::Named(NamedKey::Enter) => Some(TextControlCode::Enter),
                                LogicalKey::Named(NamedKey::ArrowLeft) => {
                                    Some(TextControlCode::MoveLeft)
                                }
                                LogicalKey::Named(NamedKey::ArrowRight) => {
                                    Some(TextControlCode::MoveRight)
                                }
                                _ => None,
                            };
                            if let Some(code) = control {
                                player.handle_event(PlayerEvent::TextControl { code });
                            }
                        }
                        needs_redraw = true;
                    }
                }
                RuffleEvent::RunContextMenuCallback(index) => {
                    if let Some(player) = playerbox.as_ref() {
                        player
                            .player
                            .lock()
                            .unwrap()
                            .run_context_menu_callback(index);
                    }
                }
                RuffleEvent::ClearContextMenu => {
                    if let Some(player) = playerbox.as_ref() {
                        player.player.lock().unwrap().clear_custom_menu_items();
                    }
                }
                RuffleEvent::RequestContextMenu => {
                    if let Some(player) = playerbox.as_ref() {
                        log::warn!("preparing context menu!");
                        let items = player.player.lock().unwrap().prepare_context_menu();
                        with_activity(&app, |env, activity| {
                            JavaInterface::show_context_menu(env, activity, &items)
                        })
                        .unwrap();
                    }
                }
                RuffleEvent::SetQuality(quality) => {
                    if let Some(player) = playerbox.as_ref() {
                        let mut player = player.player.lock().unwrap();
                        if let Some(renderer) = <dyn Any>::downcast_mut::<
                            WgpuRenderBackend<SwapChainTarget>,
                        >(player.renderer_mut())
                        {
                            renderer.set_quality_limit(Some(quality));
                        }
                        player.set_quality(quality);
                    }
                }
                RuffleEvent::SetFrameRate(frame_rate) => {
                    if let Some(player) = playerbox.as_ref() {
                        player
                            .player
                            .lock()
                            .unwrap()
                            .set_frame_rate_override(frame_rate);
                    }
                }
                RuffleEvent::SetSoftFilterScale(percent) => {
                    if let Some(player) = playerbox.as_ref() {
                        let mut player = player.player.lock().unwrap();
                        if let Some(renderer) = <dyn Any>::downcast_mut::<
                            WgpuRenderBackend<SwapChainTarget>,
                        >(player.renderer_mut())
                        {
                            if !renderer.set_soft_filter_scale(percent) {
                                log::warn!("Invalid soft filter scale {percent}%");
                            }
                        }
                    }
                }
            }
        }

        let new_time = Instant::now();
        let dt = new_time.duration_since(last_frame_time).as_micros();
        if dt > 0 {
            last_frame_time = new_time;
            if let Some(player) = playerbox.as_ref() {
                if let Ok(mut player) = player.player.lock() {
                    let tick_start = Instant::now();
                    player.tick(FloatDuration::from_millis(dt as f64 / 1000.0));
                    let tick_elapsed = tick_start.elapsed();
                    perf_ticks += 1;
                    perf_tick_time += tick_elapsed;
                    perf_tick_max = perf_tick_max.max(tick_elapsed);

                    next_frame_time = Some(new_time + player.time_til_next_frame());
                    needs_redraw = player.needs_render();
                    let audio =
                        <dyn Any>::downcast_mut::<AAudioAudioBackend>(player.audio_mut()).unwrap();
                    audio.recreate_stream_if_needed();
                }
            } else {
                next_frame_time = None;
            }
        }

        if needs_redraw && is_resumed && native_window.is_some() {
            if let Some(player) = playerbox.as_ref() {
                if let Ok(mut player) = player.player.lock() {
                    let render_start = Instant::now();
                    player.render();
                    let render_elapsed = render_start.elapsed();
                    perf_renders += 1;
                    perf_render_time += render_elapsed;
                    perf_render_max = perf_render_max.max(render_elapsed);
                    if let Some(renderer) = <dyn Any>::downcast_mut::<
                        WgpuRenderBackend<SwapChainTarget>,
                    >(player.renderer_mut())
                    {
                        last_texture_pool_bytes = renderer.texture_pool_bytes();
                        let pressure_flushes = renderer.scratch_pressure_flushes();
                        let pressure_flushes_this_frame =
                            pressure_flushes.saturating_sub(last_pressure_flushes);
                        last_pressure_flushes = pressure_flushes;
                        if DETAILED_DIAGNOSTICS
                            && (perf_renders == 1 || perf_renders.is_multiple_of(24))
                        {
                            let stats = renderer.bitmap_texture_stats();
                            last_bitmap_uploaded_bytes = stats.uploaded_bytes;
                            last_bitmap_target_bytes = stats.target_bytes;
                            last_bitmap_cache_bytes = stats.cache_bytes;
                        }
                        if DETAILED_DIAGNOSTICS && render_elapsed >= Duration::from_millis(120) {
                            let (main, _) = renderer.scratch_texture_stats();
                            let bitmaps = renderer.bitmap_texture_stats();
                            let slow_line = format!(
                                "slow-render: {:.1}ms dt={}us tex_pool={:.1}MiB scratch_created={} scratch_created_bytes={:.1}MiB scratch_flushes={} deferred={} fallback={} cab={:.1}MiB uploaded={:.1}MiB targets={:.1}MiB scratch_top={}",
                                render_elapsed.as_secs_f64() * 1000.0,
                                dt,
                                last_texture_pool_bytes as f64 / (1024.0 * 1024.0),
                                main.created_textures,
                                main.created_bytes as f64 / (1024.0 * 1024.0),
                                pressure_flushes_this_frame,
                                main.deferred_layers,
                                main.fallback_layers,
                                bitmaps.cache_bytes as f64 / (1024.0 * 1024.0),
                                bitmaps.uploaded_bytes as f64 / (1024.0 * 1024.0),
                                bitmaps.target_bytes as f64 / (1024.0 * 1024.0),
                                renderer.scratch_creation_summary(),
                            );
                            log::info!(target: "ruffle", "{slow_line}");
                            if let Some(file) = &mut perf_file {
                                let _ = writeln!(file, "{slow_line}");
                                let _ = file.flush();
                            }
                        }
                    }
                }
            }
        }

        if perf_window_start.elapsed() >= PERF_LOG_INTERVAL {
            if DETAILED_DIAGNOSTICS {
                // Persist renderer details here: some vendors encrypt native logcat.
                if let Some(player) = playerbox.as_ref() {
                    if let Ok(mut player) = player.player.lock() {
                        let core_memory = player.memory_diagnostic_summary();
                        if let Some(file) = &mut perf_file {
                            let _ = writeln!(file, "{core_memory}");
                        }
                        if let Some(renderer) = <dyn Any>::downcast_mut::<
                            WgpuRenderBackend<SwapChainTarget>,
                        >(player.renderer_mut())
                        {
                            let summary = renderer.memory_diagnostic_summary();
                            log::info!(target: "ruffle", "{summary}");
                            if let Some(file) = &mut perf_file {
                                let _ = writeln!(file, "{summary}");
                            }
                        }
                    }
                }
                let memory = process_memory_summary();
                if let Some(file) = &mut perf_file {
                    let _ = writeln!(file, "{memory}");
                }
            }
            let elapsed = perf_window_start.elapsed().as_secs_f64();
            let tick_avg = perf_tick_time.as_secs_f64() * 1000.0 / f64::from(perf_ticks.max(1));
            let render_avg =
                perf_render_time.as_secs_f64() * 1000.0 / f64::from(perf_renders.max(1));
            let perf_line = if DETAILED_DIAGNOSTICS {
                format!(
                "perf: ticks={} renders={} ({:.1} fps) tick_avg={:.1}ms tick_max={:.1}ms render_avg={:.1}ms render_max={:.1}ms tex_pool={:.1}MiB cab={:.1}MiB uploaded={:.1}MiB targets={:.1}MiB",
                perf_ticks,
                perf_renders,
                f64::from(perf_renders) / elapsed,
                tick_avg,
                perf_tick_max.as_secs_f64() * 1000.0,
                render_avg,
                perf_render_max.as_secs_f64() * 1000.0,
                last_texture_pool_bytes as f64 / (1024.0 * 1024.0),
                last_bitmap_cache_bytes as f64 / (1024.0 * 1024.0),
                last_bitmap_uploaded_bytes as f64 / (1024.0 * 1024.0),
                last_bitmap_target_bytes as f64 / (1024.0 * 1024.0),
            )
            } else {
                format!(
                    "perf: ticks={} renders={} ({:.1} fps) tick_avg={:.1}ms tick_max={:.1}ms render_avg={:.1}ms render_max={:.1}ms tex_pool={:.1}MiB",
                    perf_ticks, perf_renders, f64::from(perf_renders) / elapsed,
                    tick_avg, perf_tick_max.as_secs_f64() * 1000.0,
                    render_avg, perf_render_max.as_secs_f64() * 1000.0,
                    last_texture_pool_bytes as f64 / 1048576.0,
                )
            };
            log::info!(target: "ruffle", "{perf_line}");
            if let Some(file) = &mut perf_file {
                let _ = writeln!(file, "{perf_line}");
                let _ = file.flush();
            }
            perf_window_start = Instant::now();
            perf_ticks = 0;
            perf_renders = 0;
            perf_tick_time = Duration::ZERO;
            perf_render_time = Duration::ZERO;
            perf_tick_max = Duration::ZERO;
            perf_render_max = Duration::ZERO;
        }
    }

    let _ = vm.attach_current_thread(|env| -> jni::errors::Result<()> {
        let activity = unsafe { env.as_cast_raw::<JObject>(&activity_raw) }?;
        // Ensure that we take the EventSender back, or we'll leak it
        let _: jni::errors::Result<EventSender> =
            unsafe { env.take_rust_field(&*activity, jni_str!("eventLoopHandle")) };
        Ok(())
    });
}

#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_keydown<'local>(
    mut unowned_env: EnvUnowned<'local>,
    this: JObject<'local>,
    key_tag: JString<'local>,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let tag = key_tag.try_to_string(env)?;

            let event_loop: MutexGuard<Sender<RuffleEvent>> =
                unsafe { env.get_rust_field(&this, jni_str!("eventLoopHandle")) }?;
            if let Some(desc) = key_tag_to_key_descriptor(&tag) {
                let _ = event_loop.send(RuffleEvent::VirtualKeyEvent {
                    down: true,
                    key_descriptor: desc,
                });
            }
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_keyup<'local>(
    mut unowned_env: EnvUnowned<'local>,
    this: JObject<'local>,
    key_tag: JString<'local>,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let tag = key_tag.try_to_string(env)?;

            let event_loop: MutexGuard<Sender<RuffleEvent>> =
                unsafe { env.get_rust_field(&this, jni_str!("eventLoopHandle")) }?;
            if let Some(desc) = key_tag_to_key_descriptor(&tag) {
                let _ = event_loop.send(RuffleEvent::VirtualKeyEvent {
                    down: false,
                    key_descriptor: desc,
                });
            }
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Attaches the current thread to the JVM and runs `f` with the JNI environment and the activity.
///
/// Note: The activity can't be taken from `ndk_context`, because since android-activity 0.6.1,
/// that holds the `Application` instead.
pub fn with_activity<T>(
    app: &AndroidApp,
    f: impl FnOnce(&mut Env, &JObject) -> T,
) -> jni::errors::Result<T> {
    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) };

    // This is a global reference, so it can only be borrowed, not wrapped as a local one.
    let activity_raw = app.activity_as_ptr() as jobject;

    vm.attach_current_thread(|env| {
        let activity = unsafe { env.as_cast_raw::<JObject>(&activity_raw) }?;
        Ok(f(env, &activity))
    })
}

#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_requestContextMenu<'local>(
    mut unowned_env: EnvUnowned<'local>,
    this: JObject<'local>,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let event_loop: MutexGuard<Sender<RuffleEvent>> =
                unsafe { env.get_rust_field(&this, jni_str!("eventLoopHandle")) }?;
            let _ = event_loop.send(RuffleEvent::RequestContextMenu);
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_runContextMenuCallback<'local>(
    mut unowned_env: EnvUnowned<'local>,
    this: JObject<'local>,
    index: jint,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let event_loop: MutexGuard<Sender<RuffleEvent>> =
                unsafe { env.get_rust_field(&this, jni_str!("eventLoopHandle")) }?;
            let _ = event_loop.send(RuffleEvent::RunContextMenuCallback(index as usize));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_clearContextMenu<'local>(
    mut unowned_env: EnvUnowned<'local>,
    this: JObject<'local>,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let event_loop: MutexGuard<Sender<RuffleEvent>> =
                unsafe { env.get_rust_field(&this, jni_str!("eventLoopHandle")) }?;
            let _ = event_loop.send(RuffleEvent::ClearContextMenu);
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_setStageQuality<'local>(
    mut unowned_env: EnvUnowned<'local>,
    this: JObject<'local>,
    quality: jint,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let event_loop: MutexGuard<EventSender> =
                unsafe { env.get_rust_field(&this, jni_str!("eventLoopHandle")) }?;
            event_loop.send(RuffleEvent::SetQuality(stage_quality_from_int(quality)));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_setFrameRate<'local>(
    mut unowned_env: EnvUnowned<'local>,
    this: JObject<'local>,
    frame_rate: jint,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let event_loop: MutexGuard<EventSender> =
                unsafe { env.get_rust_field(&this, jni_str!("eventLoopHandle")) }?;
            let frame_rate = (frame_rate > 0).then_some(f64::from(frame_rate));
            event_loop.send(RuffleEvent::SetFrameRate(frame_rate));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_setSoftFilterScale<'local>(
    mut unowned_env: EnvUnowned<'local>,
    this: JObject<'local>,
    percent: jint,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let event_loop: MutexGuard<EventSender> =
                unsafe { env.get_rust_field(&this, jni_str!("eventLoopHandle")) }?;
            event_loop.send(RuffleEvent::SetSoftFilterScale(percent as u32));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_nativeInit<'local>(
    mut unowned_env: EnvUnowned<'local>,
    class: JClass<'local>,
    crash_callback: JObject<'local>,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> { native_init(env, &class, &crash_callback) })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn native_init(env: &mut Env, class: &JClass, crash_callback: &JObject) -> jni::errors::Result<()> {
    let crash_callback = env.new_global_ref(crash_callback)?;
    let jvm = env.get_java_vm()?;

    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("ruffle")
            .with_filter(
                android_logger::FilterBuilder::new()
                    .parse("warn,ruffle=info,ruffle_android=info")
                    .build(),
            ),
    );

    panic::set_hook(Box::new(move |info| {
        let backtrace = Backtrace::new();
        let thread = thread::current();
        let thread = thread.name().unwrap_or("<unnamed>");
        let message = match info.payload().downcast_ref::<&'static str>() {
            Some(s) => *s,
            None => match info.payload().downcast_ref::<String>() {
                Some(s) => &**s,
                None => "Box<Any>",
            },
        };

        let full = match info.location() {
            Some(location) => format!(
                "thread '{}' panicked at '{}': {}:{}\n{:?}",
                thread,
                message,
                location.file(),
                location.line(),
                backtrace
            ),
            None => format!(
                "thread '{}' panicked at '{}'\n{:?}",
                thread, message, backtrace
            ),
        };
        let full = format!("{full}\n{}", process_memory_summary());
        log::error!(target: "panic","{}", full);

        // Persist the panic for devices whose logcat is encrypted. A dedicated
        // crash file lets the next launch offer to share it.
        if let Some(path) = PANIC_LOG_PATH.get() {
            let crash_path = path.with_file_name("crash-rust.log");
            let _ = std::fs::write(&crash_path, format!("=== RUST PANIC ===\n{full}\n"));
            if let Ok(mut file) = OpenOptions::new().create(true).append(true).open(path) {
                let _ = writeln!(file, "\n=== PANIC ===\n{full}");
                let _ = file.flush();
            }
        }

        // Any exception already pending on this thread is stashed by the attachment while the
        // callback runs, and re-thrown afterwards, so java will still discover it on their own.
        let result = jvm.attach_current_thread(|env| -> jni::errors::Result<()> {
            let java_message = env.new_string(full)?;
            env.call_method(
                &*crash_callback,
                jni_str!("onCrash"),
                jni_sig!("(Ljava/lang/String;)V"),
                &[(&java_message).into()],
            )?;
            Ok(())
        });
        if let Err(e) = result {
            log::error!(target: "panic", "Failed to report crash to java: {}", e);
        }
    }));

    JavaInterface::init(env, class);
    Ok(())
}

fn get_loc_in_window(app: &AndroidApp) -> (i32, i32) {
    // no worky :(
    //ndk_glue::native_activity().show_soft_input(true);

    with_activity(app, JavaInterface::get_loc_in_window).unwrap()
}

fn get_view_size(app: &AndroidApp) -> Result<(i32, i32), Box<dyn std::error::Error>> {
    let size = with_activity(app, |env, activity| {
        let width = JavaInterface::get_surface_width(env, activity);
        let height = JavaInterface::get_surface_height(env, activity);
        (width, height)
    })?;

    Ok(size)
}

#[no_mangle]
fn android_main(app: AndroidApp) {
    log::info!("Starting android_main...");
    // Must happen before any reqwest client is built (e.g. by the navigator backend).
    let _ = rustls::crypto::ring::default_provider().install_default();
    run(app);
}

#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_commitText<'local>(
    mut unowned_env: EnvUnowned<'local>,
    this: JObject<'local>,
    text: JString<'local>,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let text = text.try_to_string(env)?;
            let sender: MutexGuard<EventSender> =
                unsafe { env.get_rust_field(&this, jni_str!("eventLoopHandle")) }?;
            sender.send(RuffleEvent::TextInput(text));
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_deleteText<'local>(
    mut unowned_env: EnvUnowned<'local>,
    this: JObject<'local>,
    before: jint,
    after: jint,
    codepoints: sys::jboolean,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let sender: MutexGuard<EventSender> =
                unsafe { env.get_rust_field(&this, jni_str!("eventLoopHandle")) }?;
            sender.send(RuffleEvent::DeleteText {
                before: before.max(0) as u32,
                after: after.max(0) as u32,
                codepoints,
            });
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
#[allow(clippy::missing_safety_doc)]
pub unsafe extern "C" fn Java_rs_ruffle_PlayerActivity_inputKey<'local>(
    mut unowned_env: EnvUnowned<'local>,
    this: JObject<'local>,
    code: jint,
    down: sys::jboolean,
) {
    unowned_env
        .with_env(|env| -> jni::errors::Result<()> {
            let sender: MutexGuard<EventSender> =
                unsafe { env.get_rust_field(&this, jni_str!("eventLoopHandle")) }?;
            sender.send(RuffleEvent::InputKey {
                code: code as u32,
                down,
            });
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

// Round requests ending within a surrogate pair to the whole character.
fn count_utf16_characters(chars: impl Iterator<Item = char>, mut units: u32) -> u32 {
    let mut count = 0;
    for c in chars {
        if units == 0 {
            break;
        }
        units = units.saturating_sub(c.len_utf16() as u32);
        count += 1;
    }
    count
}
