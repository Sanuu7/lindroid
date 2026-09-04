package dev.lindroid.app.desktop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

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
    var onConnectionLost: ((String?) -> Unit)? = null

    // Touch-to-mouse gestures: a single finger moves the cursor, a quick tap
    // left-clicks, a long-press right-clicks, double-tap-hold drags, and two
    // fingers scroll.
    private enum class Gesture { IDLE, TAP_PENDING, HOVER, DRAG, SCROLL }

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private var gesture = Gesture.IDLE
    private var startX = 0f
    private var startY = 0f
    private var longPressFired = false
    private var doubleTapArmed = false
    private var lastTapUpTime = 0L
    private var scrollAccum = 0f
    private var lastScrollY = 0f

    private val longPressRunnable = Runnable {
        if (gesture == Gesture.TAP_PENDING) {
            longPressFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    fun attach(client: RfbClient) {
        this.client = client
        fbWidth = client.width
        fbHeight = client.height
        pixels = IntArray(fbWidth * fbHeight)
        bitmap = Bitmap.createBitmap(fbWidth, fbHeight, Bitmap.Config.ARGB_8888)
        client.startLoop(
            onFrame = ::updateFrame,
            onError = { message -> onConnectionLost?.invoke(message) },
        )
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
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(longPressRunnable)
                gesture = Gesture.TAP_PENDING
                startX = event.x
                startY = event.y
                longPressFired = false
                doubleTapArmed = event.eventTime - lastTapUpTime <= doubleTapTimeout
                handler.postDelayed(longPressRunnable, longPressTimeout)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(longPressRunnable)
                if (gesture == Gesture.DRAG) postPointer(event.x, event.y, 0)
                gesture = Gesture.SCROLL
                scrollAccum = 0f
                lastScrollY = averageY(event)
            }
            MotionEvent.ACTION_MOVE -> when (gesture) {
                Gesture.TAP_PENDING -> {
                    val moved = hypot(event.x - startX, event.y - startY) > touchSlop
                    when {
                        longPressFired && !moved -> Unit
                        moved && doubleTapArmed -> beginDrag(event)
                        moved -> {
                            gesture = Gesture.HOVER
                            postPointer(event.x, event.y, 0)
                        }
                    }
                }
                Gesture.HOVER -> postPointer(event.x, event.y, 0)
                Gesture.DRAG -> postPointer(event.x, event.y, BUTTON_LEFT)
                Gesture.SCROLL -> handleScroll(event)
                Gesture.IDLE -> Unit
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                when (gesture) {
                    Gesture.TAP_PENDING -> {
                        if (longPressFired) {
                            postPointer(event.x, event.y, BUTTON_RIGHT)
                            postPointer(event.x, event.y, 0)
                        } else {
                            postPointer(event.x, event.y, BUTTON_LEFT)
                            postPointer(event.x, event.y, 0)
                        }
                        lastTapUpTime = event.eventTime
                    }
                    Gesture.DRAG -> postPointer(event.x, event.y, 0)
                    else -> lastTapUpTime = event.eventTime
                }
                gesture = Gesture.IDLE
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (gesture == Gesture.DRAG) postPointer(event.x, event.y, 0)
                gesture = Gesture.IDLE
            }
        }
        return true
    }

    private fun beginDrag(event: MotionEvent) {
        gesture = Gesture.DRAG
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        postPointer(event.x, event.y, BUTTON_LEFT)
    }

    private fun handleScroll(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val y = averageY(event)
        scrollAccum += y - lastScrollY
        lastScrollY = y
        // Trackpad-style scrolling: fingers move the content with them, so
        // fingers moving down equals a wheel-up tick and fingers up equals wheel-down.
        while (abs(scrollAccum) >= SCROLL_STEP) {
            val button = if (scrollAccum > 0) BUTTON_WHEEL_UP else BUTTON_WHEEL_DOWN
            scrollAccum -= if (scrollAccum > 0) SCROLL_STEP else -SCROLL_STEP
            postPointer(event.x, event.y, button)
            postPointer(event.x, event.y, 0)
        }
    }

    private fun averageY(event: MotionEvent): Float {
        var sum = 0f
        for (index in 0 until event.pointerCount) sum += event.getY(index)
        return sum / event.pointerCount.coerceAtLeast(1)
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

    private fun postPointer(viewX: Float, viewY: Float, buttons: Int) {
        val point = toFramebuffer(viewX, viewY) ?: return
        ioScope.launch {
            runCatching { client?.sendPointer(point.first, point.second, buttons) }
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
        handler.removeCallbacks(longPressRunnable)
        ioScope.cancel()
    }

    companion object {
        private const val BUTTON_LEFT = 1
        private const val BUTTON_RIGHT = 4
        private const val BUTTON_WHEEL_UP = 8
        private const val BUTTON_WHEEL_DOWN = 16
        private const val SCROLL_STEP = 40f
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
