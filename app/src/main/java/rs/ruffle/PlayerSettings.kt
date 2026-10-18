package rs.ruffle

import android.content.Context

/**
 * Non-sensitive player preferences shared by the settings dialog and Rust.
 *
 * Render scale is applied to the SurfaceView buffer (the compositor upscales
 * it), while stage quality controls Ruffle's MSAA sample count.
 */
internal object PlayerSettings {
    private const val PREFS_NAME = "player_settings"
    private const val KEY_RENDER_SCALE = "render_scale"
    private const val KEY_STAGE_QUALITY = "stage_quality"
    private const val KEY_GRAPHICS_BACKEND = "graphics_backend"

    const val QUALITY_LOW = 0
    const val QUALITY_MEDIUM = 1
    const val QUALITY_HIGH = 2

    const val BACKEND_GLES = 0
    const val BACKEND_VULKAN = 1

    const val DEFAULT_RENDER_SCALE = 0.65f
    private const val MIN_RENDER_SCALE = 0.4f

    fun renderScale(context: Context): Float =
        prefs(context).getFloat(KEY_RENDER_SCALE, DEFAULT_RENDER_SCALE)
            .coerceIn(MIN_RENDER_SCALE, 1.0f)

    fun setRenderScale(context: Context, scale: Float) {
        prefs(context).edit()
            .putFloat(KEY_RENDER_SCALE, scale.coerceIn(MIN_RENDER_SCALE, 1.0f))
            .apply()
    }

    fun stageQuality(context: Context): Int =
        prefs(context).getInt(KEY_STAGE_QUALITY, QUALITY_LOW)
            .coerceIn(QUALITY_LOW, QUALITY_HIGH)

    fun setStageQuality(context: Context, quality: Int) {
        prefs(context).edit()
            .putInt(KEY_STAGE_QUALITY, quality.coerceIn(QUALITY_LOW, QUALITY_HIGH))
            .apply()
    }

    fun graphicsBackend(context: Context): Int =
        prefs(context).getInt(KEY_GRAPHICS_BACKEND, BACKEND_GLES)
            .coerceIn(BACKEND_GLES, BACKEND_VULKAN)

    fun setGraphicsBackend(context: Context, backend: Int) {
        prefs(context).edit()
            .putInt(KEY_GRAPHICS_BACKEND, backend.coerceIn(BACKEND_GLES, BACKEND_VULKAN))
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
