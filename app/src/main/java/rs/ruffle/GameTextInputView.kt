package rs.ruffle

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/** An IME endpoint. Flash keeps the actual text field, selection and caret. */
internal class GameTextInputView(
    context: Context,
    private val commit: (String) -> Unit,
    private val delete: (Int, Int, Boolean) -> Unit,
    private val key: (KeyEvent) -> Unit,
    private val done: () -> Unit
) : View(context) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return object : BaseInputConnection(this, true) {
            private var closing = false

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!closing && !text.isNullOrEmpty()) commit(text.toString())
                // Keep only the uncommitted composition locally. Never send pinyin to Flash.
                super.finishComposingText()
                editable?.clear()
                return true
            }

            override fun finishComposingText(): Boolean {
                if (!closing && !editable.isNullOrEmpty()) commit(editable.toString())
                super.finishComposingText()
                editable?.clear()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (!editable.isNullOrEmpty()) {
                    return super.deleteSurroundingText(beforeLength, afterLength)
                }
                if (!closing) {
                    delete(
                        beforeLength.coerceAtLeast(0),
                        afterLength.coerceAtLeast(0),
                        false
                    )
                }
                return true
            }

            override fun deleteSurroundingTextInCodePoints(
                beforeLength: Int,
                afterLength: Int
            ): Boolean {
                if (!editable.isNullOrEmpty()) {
                    return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                }
                if (!closing) {
                    delete(
                        beforeLength.coerceAtLeast(0),
                        afterLength.coerceAtLeast(0),
                        true
                    )
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (!closing) key(event)
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                finishComposingText()
                done()
                return true
            }

            override fun closeConnection() {
                // Moving focus must not commit an old composition into a different Flash field.
                closing = true
                super.closeConnection()
                editable?.clear()
            }
        }
    }
}
