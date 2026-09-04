package dev.lindroid.app.desktop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NativeDesktopView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bitmap: Bitmap? = null
    private var pixels: IntArray = IntArray(0)
    private var fbWidth = 0
    private var fbHeight = 0
    private val srcRect = Rect()
    private val dstRect = Rect()

    var client: RfbClient? = null
    var onConnectionLost: (() -> Unit)? = null

    fun attach(client: RfbClient) {
        this.client = client
        fbWidth = client.width
        fbHeight = client.height
        pixels = IntArray(fbWidth * fbHeight)
        bitmap = Bitmap.createBitmap(fbWidth, fbHeight, Bitmap.Config.ARGB_8888)
        client.startLoop { rect ->
            updateFrame(rect)
        }
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun updateFrame(dirty: RfbClient.Rect) {
        val client = client ?: return
        client.snapshot(pixels)
        bitmap?.setPixels(pixels, 0, fbWidth, 0, 0, fbWidth, fbHeight)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = bitmap ?: return
        canvas.drawColor(0xFF000000.toInt())
        if (fbWidth == 0 || fbHeight == 0) return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val scale = minOf(viewW / fbWidth, viewH / fbHeight)
        val drawW = (fbWidth * scale).toInt()
        val drawH = (fbHeight * scale).toInt()
        val left = ((viewW - drawW) / 2).toInt()
        val top = ((viewH - drawH) / 2).toInt()
        srcRect.set(0, 0, fbWidth, fbHeight)
        dstRect.set(left, top, left + drawW, top + drawH)
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val point = toFramebuffer(event.x, event.y) ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                postPointer(point.first, point.second, BUTTON_LEFT)
            }
            MotionEvent.ACTION_MOVE -> {
                postPointer(point.first, point.second, BUTTON_LEFT)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                postPointer(point.first, point.second, 0)
            }
        }
        return true
    }

    private fun toFramebuffer(viewX: Float, viewY: Float): Pair<Int, Int>? {
        if (fbWidth == 0 || fbHeight == 0) return null
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW == 0f || viewH == 0f) return null
        val scale = minOf(viewW / fbWidth, viewH / fbHeight)
        val drawW = fbWidth * scale
        val drawH = fbHeight * scale
        val left = (viewW - drawW) / 2
        val top = (viewH - drawH) / 2
        val fx = ((viewX - left) / scale).toInt()
        val fy = ((viewY - top) / scale).toInt()
        if (fx !in 0 until fbWidth || fy !in 0 until fbHeight) return null
        return fx to fy
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                text.forEach { char ->
                    sendChar(char)
                }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                postKey(KEYSYM_ENTER)
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    sendAndroidKey(event)
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                postKey(KEYSYM_BACKSPACE)
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        sendAndroidKey(event)
        return true
    }

    private fun sendChar(char: Char) {
        val keysym = when (char) {
            '\n' -> KEYSYM_ENTER
            else -> char.code
        }
        postKey(keysym)
    }

    private fun postPointer(x: Int, y: Int, buttons: Int) {
        ioScope.launch {
            runCatching { client?.sendPointer(x, y, buttons) }
        }
    }

    private fun postKey(keysym: Int) {
        ioScope.launch {
            runCatching { client?.typeKey(keysym) }
        }
    }

    private fun sendAndroidKey(event: KeyEvent): Boolean {
        val keysym = when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> KEYSYM_BACKSPACE
            KeyEvent.KEYCODE_ENTER -> KEYSYM_ENTER
            KeyEvent.KEYCODE_TAB -> KEYSYM_TAB
            KeyEvent.KEYCODE_ESCAPE -> KEYSYM_ESCAPE
            KeyEvent.KEYCODE_DPAD_LEFT -> KEYSYM_LEFT
            KeyEvent.KEYCODE_DPAD_UP -> KEYSYM_UP
            KeyEvent.KEYCODE_DPAD_RIGHT -> KEYSYM_RIGHT
            KeyEvent.KEYCODE_DPAD_DOWN -> KEYSYM_DOWN
            KeyEvent.KEYCODE_DPAD_CENTER -> KEYSYM_ENTER
            KeyEvent.KEYCODE_FORWARD_DEL -> KEYSYM_DELETE
            KeyEvent.KEYCODE_MOVE_HOME -> KEYSYM_HOME
            KeyEvent.KEYCODE_MOVE_END -> KEYSYM_END
            KeyEvent.KEYCODE_PAGE_UP -> KEYSYM_PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> KEYSYM_PAGE_DOWN
            else -> {
                val char = event.unicodeChar
                if (char != 0) char else return false
            }
        }
        postKey(keysym)
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ioScope.cancel()
    }

    companion object {
        private const val BUTTON_LEFT = 1
        private const val KEYSYM_BACKSPACE = 0xFF08
        private const val KEYSYM_TAB = 0xFF09
        private const val KEYSYM_ENTER = 0xFF0D
        private const val KEYSYM_ESCAPE = 0xFF1B
        private const val KEYSYM_HOME = 0xFF50
        private const val KEYSYM_LEFT = 0xFF51
        private const val KEYSYM_UP = 0xFF52
        private const val KEYSYM_RIGHT = 0xFF53
        private const val KEYSYM_DOWN = 0xFF54
        private const val KEYSYM_PAGE_UP = 0xFF55
        private const val KEYSYM_PAGE_DOWN = 0xFF56
        private const val KEYSYM_END = 0xFF57
        private const val KEYSYM_DELETE = 0xFFFF
    }
}
