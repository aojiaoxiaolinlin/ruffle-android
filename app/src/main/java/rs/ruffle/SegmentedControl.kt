package rs.ruffle

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * A pill-shaped segmented selector used by the in-game settings panel.
 * Replaces platform radio buttons so the panel matches the player's theme.
 */
internal class SegmentedControl(context: Context) : LinearLayout(context) {
    private var labels: List<String> = emptyList()
    private var buttons: List<TextView> = emptyList()
    private var onSelected: ((Int) -> Unit)? = null

    var selectedIndex: Int = 0
        private set

    init {
        orientation = HORIZONTAL
        background = ContextCompat.getDrawable(context, R.drawable.segment_track)
        val padding = dp(3)
        setPadding(padding, padding, padding, padding)
    }

    fun setOptions(labels: List<String>, selected: Int, onSelected: (Int) -> Unit) {
        removeAllViews()
        this.labels = labels
        this.onSelected = onSelected
        selectedIndex = if (labels.isEmpty()) 0 else selected.coerceIn(0, labels.lastIndex)
        buttons = labels.mapIndexed { index, label ->
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginStart = if (index == 0) 0 else dp(2)
                }
                setOnClickListener { select(index) }
            }.also { addView(it) }
        }
        render()
    }

    fun select(index: Int) {
        if (index !in labels.indices || index == selectedIndex) return
        selectedIndex = index
        render()
        onSelected?.invoke(index)
    }

    private fun render() {
        buttons.forEachIndexed { index, button ->
            val selected = index == selectedIndex
            button.background = ContextCompat.getDrawable(
                context,
                if (selected) R.drawable.segment_selected else R.drawable.segment_unselected
            )
            button.setTextColor(if (selected) 0xFF2A1800.toInt() else 0xFFD7C3AE.toInt())
            button.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
