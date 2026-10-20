package rs.ruffle

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
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
    private var menuExpanded = false

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
    // Used by Rust; 0 follows the SWF, otherwise frames per second.
    private val frameRateOverride: Int
        get() = PlayerSettings.frameRate(this)

    @Suppress("unused")
    // Used by Rust; 50, 75 or 100 percent.
    private val softFilterScale: Int
        get() = PlayerSettings.softFilterScale(this)

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
    private external fun setFrameRate(frameRate: Int)
    private external fun setSoftFilterScale(percent: Int)

    @Suppress("unused")
    // Used by Rust
    private fun showContextMenu(items: Array<String>) {
        runOnUiThread {
            val popup = PopupMenu(this, findViewById(R.id.button_menu))
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
            val exitItemId = items.size
            menu.add(group + 1, exitItemId, Menu.NONE, R.string.exit_player)
            popup.setOnMenuItemClickListener { item: MenuItem ->
                when (item.itemId) {
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
        layout.findViewById<View>(R.id.button_menu).setOnClickListener {
            setMenuExpanded(!menuExpanded)
        }
        layout.findViewById<View>(R.id.button_kb).setOnClickListener {
            setMenuExpanded(false)
            val keyboard = layout.getViewById(R.id.keyboard)
            if (keyboard.visibility == View.VISIBLE) {
                keyboard.visibility = View.GONE
            } else {
                keyboard.visibility = View.VISIBLE
            }
            layout.post { positionToolbar() }
        }
        layout.findViewById<View>(R.id.button_cm).setOnClickListener {
            setMenuExpanded(false)
            requestContextMenu()
        }
        layout.findViewById<View>(R.id.button_reload).setOnClickListener {
            setMenuExpanded(false)
            // Retain the source URI, grants and launch options in the Intent.
            recreate()
        }
        layout.findViewById<View>(R.id.button_settings).setOnClickListener {
            setMenuExpanded(false)
            showPlayerSettings()
        }
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
        var startMarginX = 0
        var startMarginY = 0
        var dragging = false
        val listener = View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    val params = toolbar.layoutParams as? ConstraintLayout.LayoutParams
                    startMarginX = params?.marginStart ?: toolbar.left
                    startMarginY = params?.topMargin ?: toolbar.top
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
                        val desiredX = startMarginX + event.rawX - startX
                        val desiredY = startMarginY + event.rawY - startY
                        var x = desiredX
                        var y = desiredY
                        val limits = toolbarLimits(toolbar)
                        if (limits != null) {
                            // Keep the button inside the visible area while it is
                            // being dragged; otherwise the parent clips it.
                            x = x.coerceIn(limits[0].toFloat(), limits[2].toFloat())
                            y = y.coerceIn(limits[1].toFloat(), limits[3].toFloat())
                        }
                        // Move through layout params instead of translation:
                        // a layout pass reliably repaints the view over the
                        // game SurfaceView on Adreno devices.
                        moveToolbarTo(x.toInt(), y.toInt())
                        toolbarOnRight =
                            x + toolbar.width / 2f > layout.width / 2f
                        toolbarPosition =
                            (y / layout.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    if (dragging) {
                        positionToolbar()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
        layout.findViewById<View>(R.id.button_menu).setOnTouchListener(listener)
        layout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionToolbar() }
        // The first post can run before the button has a measured size; wait
        // for the first real layout before applying the saved position.
        toolbar.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (toolbar.width == 0 || toolbar.height == 0) return
                    toolbar.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    positionToolbar()
                }
            }
        )
        toolbar.post { positionToolbar() }
    }

    /** Expand or collapse the side-docked action menu. */
    private fun setMenuExpanded(expanded: Boolean) {
        if (menuExpanded == expanded) return
        menuExpanded = expanded
        val actions = findViewById<View>(R.id.menu_actions)
        val toggle = findViewById<ImageButton>(R.id.button_menu)
        toggle.setImageResource(if (expanded) R.drawable.ic_close else R.drawable.ic_menu)
        toggle.contentDescription = getString(
            if (expanded) R.string.close_player_menu else R.string.player_menu
        )
        val offset = 8 * resources.displayMetrics.density
        actions.animate().cancel()
        if (expanded) {
            actions.visibility = View.VISIBLE
            actions.alpha = 0f
            actions.translationY = -offset
            actions.animate().alpha(1f).translationY(0f).setDuration(140).start()
            actions.post { positionToolbar() }
        } else {
            actions.animate().alpha(0f).translationY(-offset).setDuration(120)
                .withEndAction {
                    actions.visibility = View.GONE
                    actions.translationY = 0f
                    positionToolbar()
                }
                .start()
        }
    }

    /**
     * Returns the visible bounds for the toolbar's top-left corner as
     * `[left, top, maxX, maxY]`, excluding system bars, cutouts and the
     * keyboard. Returns null until the toolbar has been laid out.
     */
    private fun toolbarLimits(toolbar: View): IntArray? {
        val parent = toolbar.parent as? View ?: return null
        if (parent.width == 0 || toolbar.height == 0) return null
        val insets = ViewCompat.getRootWindowInsets(parent)?.getInsets(
            WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars()
        )
        val margin = (8 * resources.displayMetrics.density).toInt()
        val left = (insets?.left ?: 0) + margin
        val maxX = (parent.width - (insets?.right ?: 0) - toolbar.width - margin)
            .coerceAtLeast(left)
        val top = (insets?.top ?: 0) + margin
        val keyboard = findViewById<View>(R.id.keyboard)
        val bottomEdge = if (keyboard.visibility == View.VISIBLE) {
            keyboard.top
        } else {
            parent.height - (insets?.bottom ?: 0)
        }
        val maxY = (bottomEdge - toolbar.height - margin).coerceAtLeast(top)
        return intArrayOf(left, top, maxX, maxY)
    }

    private fun moveToolbarTo(x: Int, y: Int) {
        val toolbar = findViewById<View>(R.id.toolbar)
        val params = toolbar.layoutParams as? ConstraintLayout.LayoutParams ?: return
        if (params.marginStart == x && params.topMargin == y) return
        params.marginStart = x
        params.topMargin = y
        toolbar.layoutParams = params
        toolbar.requestLayout()
    }

    private fun positionToolbar() {
        val toolbar = findViewById<View>(R.id.toolbar)
        val parent = toolbar.parent as? View ?: return
        val limits = toolbarLimits(toolbar) ?: return
        val x = if (toolbarOnRight) limits[2] else limits[0]
        val y = (toolbarPosition * parent.height).coerceIn(
            limits[1].toFloat(),
            limits[3].toFloat()
        ).toInt()
        moveToolbarTo(x, y)
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
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val scales = floatArrayOf(1.0f, 0.8f, 0.65f, 0.5f)
        val scaleLabels = listOf("100%", "80%", "65%", "50%")
        val currentScale = PlayerSettings.renderScale(this)
        var selectedScale = scales.indices.minByOrNull {
            kotlin.math.abs(scales[it] - currentScale)
        } ?: 2

        val qualityLabels = listOf(
            getString(R.string.quality_low_short),
            getString(R.string.quality_medium_short),
            getString(R.string.quality_high_short)
        )
        var selectedQuality = PlayerSettings.stageQuality(this)

        val softFilterScales = intArrayOf(100, 75, 50)
        val softFilterLabels = listOf("100%", "75%", "50%")
        var selectedSoftFilterScale =
            softFilterScales.indexOf(PlayerSettings.softFilterScale(this)).takeIf { it >= 0 } ?: 1

        val frameRates = intArrayOf(
            PlayerSettings.FRAME_RATE_AUTO, 24, 30, 45, PlayerSettings.MAX_FRAME_RATE
        )
        val frameRateLabels = listOf(
            getString(R.string.frame_rate_auto_short), "24", "30", "45", "60"
        )
        var selectedFrameRate = frameRates.indexOf(PlayerSettings.frameRate(this))
            .takeIf { it >= 0 } ?: 0

        val backendLabels = listOf(
            getString(R.string.backend_gles_short),
            getString(R.string.backend_vulkan_short)
        )
        val previousBackend = PlayerSettings.graphicsBackend(this)
        var selectedBackend = previousBackend

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.settings_panel)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.player_settings)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFF7E9D7.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val share = ImageButton(this).apply {
            setImageResource(R.drawable.ic_share)
            imageTintList = ColorStateList.valueOf(0xFFD7C3AE.toInt())
            contentDescription = getString(R.string.share_diagnostics)
            val ripple = TypedValue()
            theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, ripple, true
            )
            setBackgroundResource(ripple.resourceId)
            setOnClickListener {
                dialog.dismiss()
                Diagnostics.shareReport(this@PlayerActivity, null)
            }
        }
        header.addView(share, LinearLayout.LayoutParams(dp(40), dp(40)))
        val close = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(0xFFD7C3AE.toInt())
            contentDescription = getString(R.string.close_player_settings)
            val ripple = TypedValue()
            theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, ripple, true
            )
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(40), dp(40)))
        panel.addView(header, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val scroll = ScrollView(this).apply {
            isVerticalFadingEdgeEnabled = true
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        fun addSection(
            parent: LinearLayout,
            title: String,
            labels: List<String>,
            selected: Int,
            hint: String? = null,
            onSelected: (Int) -> Unit
        ) {
            parent.addView(TextView(this).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(0xFFE8C68E.toInt())
                letterSpacing = 0.03f
                setPadding(dp(2), dp(10), 0, dp(6))
            })
            val control = SegmentedControl(this)
            control.setOptions(labels, selected, onSelected)
            parent.addView(
                control,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (hint != null) {
                parent.addView(TextView(this).apply {
                    text = hint
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(0x99D7C3AE.toInt())
                    setPadding(dp(2), dp(6), dp(2), 0)
                }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }

        val metrics = resources.displayMetrics
        val landscape = metrics.widthPixels > metrics.heightPixels
        if (landscape) {
            // Two columns keep all four sections visible without scrolling
            // in landscape, where the dialog is only ~400dp tall.
            val columns = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            columns.addView(
                left,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(14)
                }
            )
            columns.addView(
                right,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            content.addView(
                columns,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addSection(
                left, getString(R.string.render_scale), scaleLabels, selectedScale
            ) { selectedScale = it }
            addSection(
                left, getString(R.string.stage_quality), qualityLabels, selectedQuality
            ) { selectedQuality = it }
            addSection(
                left, getString(R.string.soft_filter_scale), softFilterLabels,
                selectedSoftFilterScale, getString(R.string.soft_filter_hint)
            ) { selectedSoftFilterScale = it }
            addSection(
                right, getString(R.string.frame_rate), frameRateLabels, selectedFrameRate,
                getString(R.string.frame_rate_hint)
            ) { selectedFrameRate = it }
            addSection(
                right, getString(R.string.graphics_backend), backendLabels, selectedBackend,
                getString(R.string.backend_reload_hint)
            ) { selectedBackend = it }
        } else {
            addSection(
                content, getString(R.string.render_scale), scaleLabels, selectedScale
            ) { selectedScale = it }
            addSection(
                content, getString(R.string.stage_quality), qualityLabels, selectedQuality
            ) { selectedQuality = it }
            addSection(
                content, getString(R.string.soft_filter_scale), softFilterLabels,
                selectedSoftFilterScale, getString(R.string.soft_filter_hint)
            ) { selectedSoftFilterScale = it }
            addSection(
                content, getString(R.string.frame_rate), frameRateLabels, selectedFrameRate,
                getString(R.string.frame_rate_hint)
            ) { selectedFrameRate = it }
            addSection(
                content, getString(R.string.graphics_backend), backendLabels, selectedBackend,
                getString(R.string.backend_reload_hint)
            ) { selectedBackend = it }
        }

        content.addView(TextView(this).apply {
            text = getString(R.string.diagnostics)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFE8C68E.toInt())
            letterSpacing = 0.03f
            setPadding(dp(2), dp(16), 0, dp(6))
        })
        val diagnosticsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val shareLogs = TextView(this).apply {
            text = getString(R.string.share_diagnostics)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF2A1800.toInt())
            background = ContextCompat.getDrawable(
                this@PlayerActivity, R.drawable.settings_apply
            )
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                Diagnostics.shareReport(this@PlayerActivity, null)
            }
        }
        val copyInfo = TextView(this).apply {
            text = getString(R.string.copy_device_info)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFFE8C68E.toInt())
            background = ContextCompat.getDrawable(
                this@PlayerActivity, R.drawable.settings_cancel
            )
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Diagnostics.copyReport(this@PlayerActivity, null)
            }
        }
        diagnosticsRow.addView(
            shareLogs,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        )
        diagnosticsRow.addView(
            copyInfo,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        content.addView(
            diagnosticsRow,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        content.addView(TextView(this).apply {
            text = getString(R.string.diagnostics_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0x99D7C3AE.toInt())
            setPadding(dp(2), dp(6), dp(2), 0)
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        val cancel = TextView(this).apply {
            text = getString(R.string.settings_cancel)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFFE8C68E.toInt())
            background = ContextCompat.getDrawable(
                this@PlayerActivity, R.drawable.settings_cancel
            )
            setPadding(dp(20), dp(10), dp(20), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }
        val apply = TextView(this).apply {
            text = getString(R.string.settings_apply)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF2A1800.toInt())
            background = ContextCompat.getDrawable(
                this@PlayerActivity, R.drawable.settings_apply
            )
            setPadding(dp(22), dp(10), dp(22), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                PlayerSettings.setRenderScale(this@PlayerActivity, scales[selectedScale])
                // Force a re-apply even if the rounded pixel size is unchanged.
                appliedSurfaceWidth = 0
                appliedSurfaceHeight = 0
                applyRenderScale()
                PlayerSettings.setStageQuality(this@PlayerActivity, selectedQuality)
                setStageQuality(selectedQuality)
                val frameRate = frameRates[selectedFrameRate]
                PlayerSettings.setFrameRate(this@PlayerActivity, frameRate)
                setFrameRate(frameRate)
                val softFilterScale = softFilterScales[selectedSoftFilterScale]
                PlayerSettings.setSoftFilterScale(this@PlayerActivity, softFilterScale)
                setSoftFilterScale(softFilterScale)
                PlayerSettings.setGraphicsBackend(this@PlayerActivity, selectedBackend)
                if (selectedBackend != previousBackend) {
                    Toast.makeText(
                        this@PlayerActivity, R.string.backend_reload_hint, Toast.LENGTH_SHORT
                    ).show()
                }
                dialog.dismiss()
            }
        }
        footer.addView(
            cancel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(10) }
        )
        footer.addView(
            apply,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        panel.addView(footer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.65f)
            val width = (metrics.widthPixels * if (landscape) 0.55f else 0.92f)
                .toInt()
                .coerceAtMost(dp(540))
            val height = (metrics.heightPixels * if (landscape) 0.96f else 0.7f).toInt()
            setLayout(width, height)
        }
        dialog.setOnShowListener { scroll.scrollTo(0, 0) }
        dialog.show()
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
        Diagnostics.installCrashHandler(this)
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
