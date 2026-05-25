package moe.fuqiuluo.portal.ui.cadence

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import moe.fuqiuluo.portal.ext.cadenceFloatingEnabled
import moe.fuqiuluo.portal.ext.cadenceMockEnabled
import moe.fuqiuluo.portal.ext.hookSensor

class CadenceFloatingService : Service() {
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var lastClickTime = 0L

    private val colorOn = Color.parseColor("#5F5F5F")
    private val colorOff = Color.parseColor("#90A4AE")

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        applicationContext.cadenceFloatingEnabled = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        val background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
        }

        val title = TextView(this).apply {
            text = "RUN"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(8), 0, 0, 0)
        }

        container.addView(title)
        container.addView(status)
        updateFloatingState(background, container, status)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 300
        }

        container.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        setBackgroundColor(background, container, currentColor(), 220)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - initialTouchX).toInt()
                        val deltaY = (event.rawY - initialTouchY).toInt()
                        if (kotlin.math.abs(deltaX) > 8 || kotlin.math.abs(deltaY) > 8) {
                            isClick = false
                        }
                        params.x = initialX + deltaX
                        params.y = initialY + deltaY
                        floatingView?.let { windowManager?.updateViewLayout(it, params) }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            val now = System.currentTimeMillis()
                            if (now - lastClickTime >= 500) {
                                lastClickTime = now
                                val nextState = !applicationContext.cadenceMockEnabled
                                applicationContext.cadenceMockEnabled = nextState
                                applicationContext.hookSensor = nextState
                                updateFloatingState(background, container, status)
                                CadenceBroadcasts.broadcastCurrentState(this@CadenceFloatingService)
                            }
                        } else {
                            updateFloatingState(background, container, status)
                        }
                        return true
                    }
                }
                return false
            }
        })

        floatingView = container
        runCatching {
            windowManager?.addView(floatingView, params)
            CadenceBroadcasts.broadcastCurrentState(this, silent = true)
        }.onFailure {
            stopSelf()
        }
    }

    override fun onDestroy() {
        floatingView?.let {
            runCatching { windowManager?.removeView(it) }
        }
        floatingView = null
        applicationContext.cadenceFloatingEnabled = false
        super.onDestroy()
    }

    private fun updateFloatingState(
        background: GradientDrawable,
        container: LinearLayout,
        status: TextView
    ) {
        val enabled = applicationContext.cadenceMockEnabled
        status.text = if (enabled) "ON" else "OFF"
        setBackgroundColor(background, container, currentColor(), 180)
    }

    private fun currentColor(): Int {
        return if (applicationContext.cadenceMockEnabled) colorOn else colorOff
    }

    private fun setBackgroundColor(
        background: GradientDrawable,
        container: LinearLayout,
        color: Int,
        alpha: Int
    ) {
        background.setColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)))
        container.background = background
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
