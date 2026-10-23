use std::sync::Arc;

use ruffle_render_wgpu::{
    backend::{request_adapter_and_device, WgpuRenderBackend},
    descriptors::Descriptors,
    target::SwapChainTarget,
};

/// Create the Android renderer with GLES debug groups and HAL labels disabled.
///
/// # Safety
/// The native window must remain valid for the lifetime of its wgpu surface.
pub unsafe fn for_window(
    window: wgpu::SurfaceTargetUnsafe,
    size: (u32, u32),
    backends: wgpu::Backends,
) -> Result<WgpuRenderBackend<SwapChainTarget>, Box<dyn std::error::Error>> {
    // Adreno 750 and 830 drivers crash in strlcpy while copying a debug
    // group label. Disable the GL entry points, including internal markers,
    // rather than relying on the labels being NUL-terminated in memory.
    let options = wgpu::BackendOptions {
        gl: wgpu::GlBackendOptions {
            debug_fns: wgpu::GlDebugFns::Disabled,
            ..Default::default()
        },
        ..Default::default()
    };
    // Preserve Ruffle's instance defaults, including the indirect-validation
    // workaround, while keeping labels out of shader descriptors as well.
    let mut flags = wgpu::InstanceFlags::default()
        .difference(wgpu::InstanceFlags::VALIDATION_INDIRECT_CALL)
        .with_env();
    if backends.contains(wgpu::Backends::GL) {
        flags |= wgpu::InstanceFlags::DISCARD_HAL_LABELS;
    }
    let instance = wgpu::Instance::new(wgpu::InstanceDescriptor {
        backends,
        flags,
        backend_options: options,
        ..wgpu::InstanceDescriptor::new_without_display_handle()
    });
    let surface = unsafe { instance.create_surface_unsafe(window)? };
    let (adapter, device, queue) = futures::executor::block_on(request_adapter_and_device(
        backends,
        &instance,
        Some(&surface),
        wgpu::PowerPreference::HighPerformance,
    ))?;
    let descriptors = Arc::new(Descriptors::new(instance, adapter, device, queue));
    let target = SwapChainTarget::new(surface, &descriptors.adapter, size, &descriptors.device);
    let renderer = WgpuRenderBackend::new(descriptors, target)?;
    if backends.contains(wgpu::Backends::GL) {
        log::info!(target: "ruffle", "GLES debug functions disabled (Adreno crash workaround)");
    }
    Ok(renderer)
}
