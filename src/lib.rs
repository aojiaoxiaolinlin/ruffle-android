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
    events::{LogicalKey, MouseButton, PlayerEvent},
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

/// Set once the player starts, so the panic hook can persist its message even
/// when Android logcat output is encrypted by the vendor.
static PANIC_LOG_PATH: OnceLock<PathBuf> = OnceLock::new();

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
        if self.sender.send(event).is_ok() {
            self.waker.wake();
        }
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
        let _ = PANIC_LOG_PATH.set(path.clone());
    }
    let mut perf_file = perf_log_path.as_ref().and_then(|path| {
        let _ = std::fs::write(path, b"");
        OpenOptions::new().create(true).append(true).open(path).ok()
    });

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
                                let (url, bytes, base_url, auth_string, stage_quality, graphics_backend) = with_activity(&app, |env, activity| {
                                    (
                                        JavaInterface::get_swf_uri(env, activity),
                                        JavaInterface::get_swf_bytes(env, activity),
                                        JavaInterface::get_base_url(env, activity),
                                        JavaInterface::get_auth_string(env, activity),
                                        JavaInterface::get_stage_quality(env, activity),
                                        JavaInterface::get_graphics_backend(env, activity),
                                    )
                                })
                                .unwrap();
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
                                let renderer = if graphics_backend == 1 {
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
                                let parameters = auth_string
                                    .filter(|auth| !auth.is_empty())
                                    .map(|auth| vec![("auth_string".to_owned(), auth)])
                                    .unwrap_or_default();
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
                                            .with_audio(AAudioAudioBackend::new().unwrap())
                                            .with_storage(Box::new(DiskStorageBackend::new(android_storage_dir.clone())))
                                            .with_navigator(navigator)
                                            .with_ui(ui::AndroidUiBackend::new())
                                            .with_log(FileLogBackend::new(trace_output.as_deref()))
                                            .with_video(
                                                ruffle_video_software::backend::SoftwareVideoBackend::new(),
                                            )
                                        .build(),
                                    }
                                );

                                let player = &playerbox.as_ref().unwrap().player;
                                let mut player_lock = player.lock().unwrap();
                                let renderer_info = player_lock.renderer_mut().debug_info();
                                log::info!(target: "ruffle", "renderer:\n{renderer_info}");
                                let render_scale =
                                    with_activity(&app, JavaInterface::get_render_scale).unwrap();
                                log::info!(target: "ruffle", "render scale: {render_scale:.2}");
                                if let Some(path) = &perf_log_path {
                                    if let Ok(mut file) =
                                        OpenOptions::new().create(true).append(true).open(path)
                                    {
                                        let _ = writeln!(
                                            file,
                                            "=== renderer ===\n{renderer_info}\nrender_scale={render_scale:.2}"
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
                                    let mut movie = SwfMovie::from_data(&bytes, url, None, None).unwrap();
                                    movie.append_parameters(parameters);
                                    player_lock.mutate_with_update_context(|context| {
                                        context.set_root_movie(movie);
                                    });
                                } else {
                                    player_lock.fetch_root_movie(url, parameters, Box::new(|_| {}))
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
                        player.player.lock().unwrap().set_quality(quality);
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
                    if let Some(renderer) =
                        <dyn Any>::downcast_mut::<WgpuRenderBackend<SwapChainTarget>>(
                            player.renderer_mut(),
                        )
                    {
                        last_texture_pool_bytes = renderer.texture_pool_bytes();
                    }
                }
            }
        }

        if perf_window_start.elapsed() >= Duration::from_secs(1) {
            let elapsed = perf_window_start.elapsed().as_secs_f64();
            let tick_avg = perf_tick_time.as_secs_f64() * 1000.0 / f64::from(perf_ticks.max(1));
            let render_avg =
                perf_render_time.as_secs_f64() * 1000.0 / f64::from(perf_renders.max(1));
            let perf_line = format!(
                "perf: ticks={} renders={} ({:.1} fps) tick_avg={:.1}ms tick_max={:.1}ms render_avg={:.1}ms render_max={:.1}ms tex_pool={:.1}MiB",
                perf_ticks,
                perf_renders,
                f64::from(perf_renders) / elapsed,
                tick_avg,
                perf_tick_max.as_secs_f64() * 1000.0,
                render_avg,
                perf_render_max.as_secs_f64() * 1000.0,
                last_texture_pool_bytes as f64 / (1024.0 * 1024.0),
            );
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
        log::error!(target: "panic","{}", full);

        // Persist the panic for devices whose logcat is encrypted.
        if let Some(path) = PANIC_LOG_PATH.get() {
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
