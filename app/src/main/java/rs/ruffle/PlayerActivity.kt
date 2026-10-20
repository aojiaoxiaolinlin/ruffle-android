package rs.ruffle

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.androidgamesdk.GameActivity
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import kotlin.math.abs

class PlayerActivity : GameActivity() {
    @Volatile private var playerCrashed = false
    private var toolbarOnRight = false
    private var toolbarPosition = 0.5f

    @Suppress("unused")
    // Used by Rust
    private val swfBytes: ByteArray?
        get() {
            val uri = intent.data
            if (uri?.scheme == "content") {
                try {
                    contentResolver.openInputStream(uri).use { inputStream ->
                        if (inputStream == null) {
                            return null
                        }
                        val bytes = ByteArray(inputStream.available())
                        val dataInputStream = DataInputStream(inputStream)
                        dataInputStream.readFully(bytes)
                        return bytes
                    }
                } catch (ignored: IOException) {
                }
            }
            return null
        }

    @Suppress("unused")
    // Used by Rust
    private val swfUri: String?
        get() {
            return intent.dataString
        }

    @Suppress("unused")
    // Used by Rust
    private val baseUrl: String?
        get() = intent.getStringExtra("baseUrl")

    @Suppress("unused")
    // Used by Rust
    private val traceOutput: String?
        get() {
            return intent.getStringExtra("traceOutput")
        }

    @Suppress("unused")
    // Used by Rust
    private fun navigateToUrl(url: String?) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private var loc = IntArray(2)

    @Suppress("unused")
    // Handle of an EventLoopProxy over in rust-land
    private val eventLoopHandle: Long = 0

    @Suppress("unused")
    // Used by Rust
    private val locInWindow: IntArray
        get() {
            mSurfaceView.getLocationInWindow(loc)
            return loc
        }

    @Suppress("unused")
    // Used by Rust
    private val surfaceWidth: Int
        get() = mSurfaceView.width

    @Suppress("unused")
    // Used by Rust
    private val surfaceHeight: Int
        get() = mSurfaceView.height

    @Suppress("unused")
    // Used by Rust
    private val stageQuality: Int
        get() = PlayerSettings.stageQuality(this)

    @Suppress("unused")
    // Used by Rust
    private val renderScale: Float
        get() = PlayerSettings.renderScale(this)

    @Suppress("unused")
    // Used by Rust
    private val graphicsBackend: Int
        get() = PlayerSettings.graphicsBackend(this)

    @Suppress("unused")
    // Used by Rust; some OEMs encrypt logcat for non-debuggable apps.
    private val externalFilesDirPath: String?
        get() = getExternalFilesDir(null)?.absolutePath

    private var appliedSurfaceWidth = 0
    private var appliedSurfaceHeight = 0

    private external fun keydown(keyTag: String)
    private external fun keyup(keyTag: String)
    private external fun requestContextMenu()
    private external fun runContextMenuCallback(index: Int)
    private external fun clearContextMenu()
    private external fun setStageQuality(quality: Int)

    @Suppress("unused")
    // Used by Rust
    private fun showContextMenu(items: Array<String>) {
        runOnUiThread {
            val popup = PopupMenu(this, findViewById(R.id.button_cm))
            val menu = popup.menu
            if (Build.VERSION.SDK_INT >= VERSION_CODES.P) {
                menu.setGroupDividerEnabled(true)
            }
            var group = 1
            for (i in items.indices) {
                val elements = items[i].split(" ".toRegex(), limit = 4).toTypedArray()
                val enabled = elements[0].toBoolean()
                val separatorBefore = elements[1].toBoolean()
                val checked = elements[2].toBoolean()
                val caption = elements[3]
                if (separatorBefore) group += 1
                val item = menu.add(group, i, Menu.NONE, caption)
                item.setEnabled(enabled)
                if (checked) {
                    item.setCheckable(true)
                    item.setChecked(true)
                }
            }
            val reloadItemId = items.size
            val exitItemId = items.size + 1
            menu.add(group + 1, reloadItemId, Menu.NONE, R.string.reload_movie)
            menu.add(group + 1, exitItemId, Menu.NONE, R.string.exit_player)
            popup.setOnMenuItemClickListener { item: MenuItem ->
                when (item.itemId) {
                    reloadItemId -> {
                        popup.dismiss()
                        // Retain the source URI, grants and launch options in the Intent.
                        recreate()
                    }
                    exitItemId -> finish()
                    else -> runContextMenuCallback(item.itemId)
                }
                true
            }
            popup.setOnDismissListener { clearContextMenu() }
            popup.show()
        }
    }

    @Suppress("unused")
    // Used by Rust
    private fun getAndroidDataStorageDir(): String {
        // TODO It can also be placed in an external storage path in the future to share archived content
        val storageDirPath = "${filesDir.absolutePath}/ruffle/shared_objects"
        val storageDir = File(storageDirPath)
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        return storageDirPath
    }

    override fun onCreateSurfaceView() {
        val inflater = layoutInflater

        @SuppressLint("InflateParams")
        val layout = inflater.inflate(R.layout.keyboard, null) as ConstraintLayout

        contentViewId = View.generateViewId()
        layout.id = contentViewId
        setContentView(layout)
        mSurfaceView = InputEnabledSurfaceView(this)

        mSurfaceView.contentDescription = "Ruffle Player"

        val placeholder = findViewById<View>(R.id.placeholder)
        val pars = placeholder.layoutParams as ConstraintLayout.LayoutParams
        val parent = placeholder.parent as ViewGroup
        val index = parent.indexOfChild(placeholder)
        parent.removeView(placeholder)
        parent.addView(mSurfaceView, index)
        mSurfaceView.setLayoutParams(pars)
        mSurfaceView.addOnLayoutChangeListener { _, _, _, right, bottom, _, _, oldRight, oldBottom ->
            if (right != oldRight || bottom != oldBottom) {
                applyRenderScale()
            }
        }
        val keys = gatherAllDescendantsOfType<Button>(
            layout.getViewById(R.id.keyboard),
            Button::class.java
        )
        for (b in keys) {
            b.setOnTouchListener { view: View, motionEvent: MotionEvent ->
                val tag = view.tag as String
                if (motionEvent.action == MotionEvent.ACTION_DOWN) keydown(tag)
                if (motionEvent.action == MotionEvent.ACTION_UP) keyup(tag)
                view.performClick()
                false
            }
        }
        layout.findViewById<View>(R.id.button_kb).setOnClickListener {
            val keyboard = layout.getViewById(R.id.keyboard)
            if (keyboard.visibility == View.VISIBLE) {
                keyboard.visibility = View.GONE
            } else {
                keyboard.visibility = View.VISIBLE
            }
            layout.post { positionToolbar() }
        }
        layout.findViewById<View>(R.id.button_cm)
            .setOnClickListener { requestContextMenu() }
        layout.findViewById<View>(R.id.button_settings)
            .setOnClickListener { showPlayerSettings() }
        installFloatingToolbar(layout)
        layout.requestLayout()
        layout.requestFocus()
        mSurfaceView.holder.addCallback(this)
        ViewCompat.setOnApplyWindowInsetsListener(mSurfaceView, this)
        mSurfaceView.post { applyRenderScale() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemUI()
        val keyboard = findViewById<View>(R.id.keyboard)
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        keyboard.visibility = if (isLandscape) View.GONE else View.VISIBLE
        findViewById<View>(R.id.toolbar).post {
            positionToolbar()
            applyRenderScale()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installFloatingToolbar(layout: ViewGroup) {
        val toolbar = layout.findViewById<View>(R.id.toolbar)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var toolbarX = 0f
        var toolbarY = 0f
        var dragging = false
        val listener = View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    toolbarX = toolbar.x
                    toolbarY = toolbar.y
                    dragging = false
                    view.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - startX) > slop || abs(event.rawY - startY) > slop) {
                        dragging = true
                        view.isPressed = false
                    }
                    if (dragging) {
                        toolbar.x = toolbarX + event.rawX - startX
                        toolbar.y = toolbarY + event.rawY - startY
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    if (dragging) {
                        toolbarOnRight = toolbar.x + toolbar.width / 2f > layout.width / 2f
                        toolbarPosition =
                            (toolbar.y / layout.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        positionToolbar()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
        toolbar.setOnTouchListener(listener)
        layout.findViewById<View>(R.id.button_kb).setOnTouchListener(listener)
        layout.findViewById<View>(R.id.button_cm).setOnTouchListener(listener)
        layout.findViewById<View>(R.id.button_settings).setOnTouchListener(listener)
        layout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionToolbar() }
        toolbar.post { positionToolbar() }
    }

    private fun positionToolbar() {
        val toolbar = findViewById<View>(R.id.toolbar)
        val parent = toolbar.parent as View
        if (parent.width == 0 || toolbar.height == 0) return
        val insets = ViewCompat.getRootWindowInsets(parent)?.getInsets(
            WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars()
        )
        val margin = 8 * resources.displayMetrics.density
        val left = (insets?.left ?: 0) + margin
        val right = (parent.width - (insets?.right ?: 0) - toolbar.width - margin).coerceAtLeast(
            left
        )
        val top = (insets?.top ?: 0) + margin
        val keyboard = findViewById<View>(R.id.keyboard)
        val bottom = if (keyboard.visibility ==
            View.VISIBLE
        ) {
            keyboard.top
        } else {
            parent.height - (insets?.bottom ?: 0)
        }
        toolbar.x = if (toolbarOnRight) right else left
        toolbar.y =
            (toolbarPosition * parent.height).coerceIn(
                top,
                (bottom - toolbar.height - margin).coerceAtLeast(top)
            )
    }

    /**
     * Render at a fraction of the view's physical size. SurfaceView scales
     * this buffer to its bounds in hardware, which is much cheaper than
     * rendering every physical pixel.
     */
    private fun applyRenderScale() {
        val view = mSurfaceView
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return
        val scale = PlayerSettings.renderScale(this)
        val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
        if (scaledWidth == appliedSurfaceWidth && scaledHeight == appliedSurfaceHeight) return
        appliedSurfaceWidth = scaledWidth
        appliedSurfaceHeight = scaledHeight
        view.holder.setFixedSize(scaledWidth, scaledHeight)
        Log.i("ruffle", "Render scale $scale: ${width}x$height -> ${scaledWidth}x${scaledHeight}")
    }

    private fun showPlayerSettings() {
        val scales = floatArrayOf(1.0f, 0.8f, 0.65f, 0.5f)
        val scaleLabels = arrayOf("100%", "80%", "65%", "50%")
        val currentScale = PlayerSettings.renderScale(this)
        var selectedScale = scales.indices.minByOrNull {
            kotlin.math.abs(scales[it] - currentScale)
        } ?: 2

        val qualityLabels = arrayOf(
            getString(R.string.quality_low),
            getString(R.string.quality_medium),
            getString(R.string.quality_high)
        )
        var selectedQuality = PlayerSettings.stageQuality(this)
        val previousBackend = PlayerSettings.graphicsBackend(this)
        var selectedBackend = previousBackend

        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), 0)
        }

        fun addSectionLabel(text: String) {
            container.addView(TextView(this).apply {
                this.text = text
                setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
            })
        }

        addSectionLabel(getString(R.string.render_scale))
        val scaleIds = scales.indices.map { 1000 + it }
        val scaleGroup = RadioGroup(this)
        scales.indices.forEach { index ->
            scaleGroup.addView(RadioButton(this).apply {
                id = scaleIds[index]
                text = scaleLabels[index]
                isChecked = index == selectedScale
            })
        }
        scaleGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedScale = scaleIds.indexOf(checkedId).takeIf { it >= 0 } ?: selectedScale
        }
        container.addView(scaleGroup)

        addSectionLabel(getString(R.string.stage_quality))
        val qualityIds = qualityLabels.indices.map { 2000 + it }
        val qualityGroup = RadioGroup(this)
        qualityLabels.indices.forEach { index ->
            qualityGroup.addView(RadioButton(this).apply {
                id = qualityIds[index]
                text = qualityLabels[index]
                isChecked = index == selectedQuality
            })
        }
        qualityGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedQuality = qualityIds.indexOf(checkedId).takeIf { it >= 0 } ?: selectedQuality
        }
        container.addView(qualityGroup)

        addSectionLabel(getString(R.string.graphics_backend))
        val backendLabels = arrayOf(
            getString(R.string.backend_gles),
            getString(R.string.backend_vulkan)
        )
        val backendIds = backendLabels.indices.map { 3000 + it }
        val backendGroup = RadioGroup(this)
        backendLabels.indices.forEach { index ->
            backendGroup.addView(RadioButton(this).apply {
                id = backendIds[index]
                text = backendLabels[index]
                isChecked = index == selectedBackend
            })
        }
        backendGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedBackend = backendIds.indexOf(checkedId).takeIf { it >= 0 } ?: selectedBackend
        }
        container.addView(backendGroup)

        AlertDialog.Builder(this)
            .setTitle(R.string.player_settings)
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                PlayerSettings.setRenderScale(this, scales[selectedScale])
                // Force a re-apply even if the rounded pixel size is unchanged.
                appliedSurfaceWidth = 0
                appliedSurfaceHeight = 0
                applyRenderScale()
                PlayerSettings.setStageQuality(this, selectedQuality)
                setStageQuality(selectedQuality)
                PlayerSettings.setGraphicsBackend(this, selectedBackend)
                if (selectedBackend != previousBackend) {
                    Toast.makeText(this, R.string.backend_reload_hint, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("toolbarOnRight", toolbarOnRight)
        outState.putFloat("toolbarPosition", toolbarPosition)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        // Start during the transition from a visible Activity, before background
        // execution restrictions can freeze the native game/heartbeat loop.
        if (!isFinishing && !isChangingConfigurations && !playerCrashed) {
            try {
                startForegroundService(
                    Intent(this, GameConnectionService::class.java)
                        .putExtra("playerIntent", Intent(intent))
                )
            } catch (failure: IllegalStateException) {
                Log.w("ruffle", "Background connection service unavailable", failure)
            } catch (failure: SecurityException) {
                Log.w("ruffle", "Background connection service denied", failure)
            }
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        stopService(Intent(this, GameConnectionService::class.java))
        hideSystemUI()
        // The system may restore bars during the app-switch animation after onResume.
        window.decorView.post {
            if (hasWindowFocus()) hideSystemUI()
            applyRenderScale()
        }
    }

    override fun onDestroy() {
        stopService(Intent(this, GameConnectionService::class.java))
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    private fun hideSystemUI() {
        // This will put the game behind any cutouts and waterfalls on devices which have
        // them, so the corresponding insets will be non-zero.
        if (Build.VERSION.SDK_INT >= VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        // From API 30 onwards, this is the recommended way to hide the system UI, rather than
        // using View.setSystemUiVisibility.
        val decorView = window.decorView
        val controller = WindowInsetsControllerCompat(
            window,
            decorView
        )
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.hide(WindowInsetsCompat.Type.displayCutout())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        toolbarOnRight = savedInstanceState?.getBoolean("toolbarOnRight") ?: false
        toolbarPosition = savedInstanceState?.getFloat("toolbarPosition", 0.5f) ?: 0.5f
        nativeInit { message ->
            playerCrashed = true
            stopService(Intent(this, GameConnectionService::class.java))
            Log.e("ruffle", "Handling panic: $message")
            startActivity(
                Intent(this, PanicActivity::class.java).apply {
                    putExtra("message", message)
                }
            )
        }
        // When true, the app will fit inside any system UI windows.
        // When false, we render behind any system UI windows.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemUI()
        // You can set IME fields here or in native code using GameActivity_setImeEditorInfoFields.
        // We set the fields in native_engine.cpp.
        // super.setImeEditorInfoFields(InputType.TYPE_CLASS_TEXT,
        //     IME_ACTION_NONE, IME_FLAG_NO_FULLSCREEN );
        requestNoStatusBarFeature()
        supportActionBar?.hide()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // Used by Rust
    @Suppress("unused")
    val isGooglePlayGames: Boolean
        get() {
            val pm = packageManager
            return pm.hasSystemFeature("com.google.android.play.feature.HPE_EXPERIENCE")
        }

    private fun requestNoStatusBarFeature() {
        // Hiding the status bar this way makes it see through when pulled down
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        WindowInsetsControllerCompat(
            window,
            mSurfaceView
        ).hide(WindowInsetsCompat.Type.statusBars())
    }

    companion object {
        init {
            // load the native activity
            System.loadLibrary("ruffle_android")
        }

        @JvmStatic
        private external fun nativeInit(crashCallback: CrashCallback)

        private fun <T> gatherAllDescendantsOfType(v: View, t: Class<*>): List<T> {
            val result: MutableList<T> = ArrayList()
            @Suppress("UNCHECKED_CAST")
            if (t.isInstance(v)) result.add(v as T)
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    result.addAll(gatherAllDescendantsOfType(v.getChildAt(i), t))
                }
            }
            return result
        }
    }

    fun interface CrashCallback {
        fun onCrash(message: String)
    }
}
