package ai.nhg.capturepoc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object FloatingBubbleManager {
    private const val TAG = "NhgFloatingBubble"

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var statusDot: View? = null
    private var timerText: TextView? = null
    private var pauseResumeBtn: ImageView? = null
    private var stopBtn: ImageView? = null

    private var isCurrentlyPaused = false
    private var actionCallback: ((String) -> Unit)? = null

    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 23) {
            try {
                Settings.canDrawOverlays(context)
            } catch (_: Throwable) {
                false
            }
        } else {
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context, onAction: (String) -> Unit) {
        if (!canDrawOverlays(context)) {
            Log.w(TAG, "Cannot show bubble: overlay permission not granted")
            return
        }
        if (bubbleView != null) return

        actionCallback = onAction
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }

        val layoutType = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }
        params = lp

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(0xEE141C24.toInt())
                setStroke(dp(1), 0x3300E676.toInt())
            }
            elevation = dp(6).toFloat()
        }

        // Status indicator dot
        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                marginEnd = dp(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF00E676.toInt())
            }
        }
        statusDot = dot
        root.addView(dot)

        // Timer
        val timer = TextView(context).apply {
            text = "00:00"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, 0, dp(10), 0)
        }
        timerText = timer
        root.addView(timer)

        // Pause/Resume button
        val prBtn = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginEnd = dp(6)
            }
            setImageResource(R.drawable.ic_pause)
            setColorFilter(0xFF00E676.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = circleDrawable(0x2200E676.toInt(), dp)
            setOnClickListener {
                if (isCurrentlyPaused) {
                    actionCallback?.invoke(CaptureService.ACTION_RESUME)
                } else {
                    actionCallback?.invoke(CaptureService.ACTION_PAUSE)
                }
            }
        }
        pauseResumeBtn = prBtn
        root.addView(prBtn)

        // Stop button
        val sBtn = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginEnd = dp(6)
            }
            setImageResource(R.drawable.ic_stop)
            setColorFilter(0xFFFF5252.toInt())
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = circleDrawable(0x22FF5252.toInt(), dp)
            setOnClickListener {
                actionCallback?.invoke(CaptureService.ACTION_STOP)
                hide()
            }
        }
        stopBtn = sBtn
        root.addView(sBtn)

        // Dismiss X button
        val xBtn = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            setImageResource(R.drawable.ic_close)
            setColorFilter(0x88FFFFFF.toInt())
            setPadding(dp(3), dp(3), dp(3), dp(3))
            setOnClickListener {
                hide()
            }
        }
        root.addView(xBtn)

        // Drag listener
        root.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = lp.x
                        initialY = lp.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5)) {
                            isClick = false
                        }
                        lp.x = initialX + dx
                        lp.y = initialY + dy
                        try {
                            wm.updateViewLayout(root, lp)
                        } catch (_: Throwable) {}
                        return !isClick
                    }
                }
                return false
            }
        })

        try {
            wm.addView(root, lp)
            bubbleView = root
            Log.i(TAG, "Floating bubble displayed successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to attach floating bubble to window manager", t)
            bubbleView = null
        }
    }

    fun update(isPaused: Boolean, elapsedSec: Long) {
        val root = bubbleView ?: return
        isCurrentlyPaused = isPaused

        root.post {
            val mins = elapsedSec / 60
            val secs = elapsedSec % 60
            timerText?.text = String.format("%02d:%02d", mins, secs)

            if (isPaused) {
                statusDot?.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFFFC400.toInt())
                }
                pauseResumeBtn?.setImageResource(R.drawable.ic_play)
                pauseResumeBtn?.setColorFilter(0xFFFFC400.toInt())
            } else {
                statusDot?.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF00E676.toInt())
                }
                pauseResumeBtn?.setImageResource(R.drawable.ic_pause)
                pauseResumeBtn?.setColorFilter(0xFF00E676.toInt())
            }
        }
    }

    fun hide() {
        val view = bubbleView ?: return
        val wm = windowManager ?: return
        try {
            wm.removeView(view)
            Log.i(TAG, "Floating bubble removed")
        } catch (_: Throwable) {}
        bubbleView = null
        params = null
        windowManager = null
        actionCallback = null
    }

    private fun circleDrawable(fillColor: Int, dp: (Int) -> Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
        }
    }
}
