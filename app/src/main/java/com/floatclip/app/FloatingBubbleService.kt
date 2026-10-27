package com.floatclip.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.floatclip.app.data.FloatClipDatabase
import com.floatclip.app.data.SavedClipboardEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class FloatingBubbleService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val quickCopyMutex = Mutex()

    private lateinit var database: FloatClipDatabase
    private lateinit var windowManager: WindowManager
    private lateinit var windowParams: WindowManager.LayoutParams
    private var rootView: LinearLayout? = null
    private var actionPanel: LinearLayout? = null
    private var bubbleView: TextView? = null

    private val preferences by lazy {
        getSharedPreferences("floatclip_bubble", Context.MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        database = FloatClipDatabase.get(this)
        startAsForeground()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            ClipboardTools.toast(this, "Allow Display over other apps to use the bubble.")
            stopSelf()
            return
        }
        addBubbleView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (rootView == null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this))) {
            addBubbleView()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "FloatClip floating bubble",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps the user-started FloatClip bubble available."
                }
            )
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("FloatClip is active")
            .setContentText("Floating clipboard controls are ready.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun addBubbleView() {
        if (rootView != null) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(8), dp(8), dp(8), dp(2))
            background = roundedBackground(Color.WHITE, dp(18), Color.rgb(226, 232, 240))
            elevation = dp(8).toFloat()
        }
        panel.addView(actionButton("1   Copy Next", "#2563EB") { copyNextQuickText() })
        panel.addView(actionButton("2   Save to A", "#0F766E") { saveClipboardTo("A") })
        panel.addView(actionButton("3   Save to C", "#7C3AED") { saveClipboardTo("C") })
        panel.addView(actionButton("4   Generate 2FA", "#B45309") { generateAndCopyTotp() })
        panel.addView(actionButton("5   Close Bubble", "#475569") { stopSelf() })

        val bubble = TextView(this).apply {
            text = "FC"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundedBackground(Color.rgb(37, 99, 235), dp(58), Color.WHITE).apply {
                shape = GradientDrawable.OVAL
            }
            elevation = dp(8).toFloat()
            contentDescription = "FloatClip floating menu"
        }

        root.addView(
            bubble,
            LinearLayout.LayoutParams(dp(58), dp(58)).apply {
                topMargin = dp(6)
            }
        )
        root.addView(panel)

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(180)
        }

        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var startTouchX = 0f
            private var startTouchY = 0f
            private var moved = false

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = windowParams.x
                        startY = windowParams.y
                        startTouchX = event.rawX
                        startTouchY = event.rawY
                        moved = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - startTouchX
                        val dy = event.rawY - startTouchY
                        if (kotlin.math.abs(dx) > dp(5) || kotlin.math.abs(dy) > dp(5)) {
                            moved = true
                        }
                        if (moved) {
                            windowParams.x = startX + dx.toInt()
                            windowParams.y = startY + dy.toInt()
                            try {
                                windowManager.updateViewLayout(root, windowParams)
                            } catch (_: IllegalArgumentException) {
                                // The overlay may be removed while the user is dragging it.
                            }
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                            panel.requestLayout()
                            root.requestLayout()
                            try {
                                windowManager.updateViewLayout(root, windowParams)
                            } catch (_: IllegalArgumentException) {
                                // Ignore a late tap after service shutdown.
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })

        rootView = root
        actionPanel = panel
        bubbleView = bubble
        try {
            windowManager.addView(root, windowParams)
        } catch (exception: Exception) {
            rootView = null
            actionPanel = null
            bubbleView = null
            ClipboardTools.toast(this, "Could not show bubble: " + (exception.message ?: "overlay error"))
            stopSelf()
        }
    }

    private fun actionButton(label: String, color: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBackground(Color.parseColor(color), dp(12), Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            elevation = dp(2).toFloat()
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(210), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
    }

    private fun copyNextQuickText() {
        serviceScope.launch {
            quickCopyMutex.withLock {
                val items = database.dao().getQuickTextsOnce().filter { it.content.isNotBlank() }
                if (items.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        ClipboardTools.toast(this@FloatingBubbleService, "Quick Text is empty. Add text in FloatClip first.")
                    }
                    return@withLock
                }

                val lastId = preferences.getLong(KEY_LAST_QUICK_TEXT_ID, -1L)
                val lastIndex = items.indexOfFirst { it.id == lastId }
                val next = items[(lastIndex + 1) % items.size]

                withContext(Dispatchers.Main) {
                    ClipboardTools.copyText(this@FloatingBubbleService, next.title, next.content)
                    preferences.edit().putLong(KEY_LAST_QUICK_TEXT_ID, next.id).apply()
                    ClipboardTools.toast(this@FloatingBubbleService, "Copied: " + next.title)
                }
            }
        }
    }

    private fun saveClipboardTo(slot: String) {
        val content = ClipboardTools.readText(this)
        if (content == null) {
            ClipboardTools.toast(this, "Clipboard is empty. Copy some text first.")
            return
        }

        serviceScope.launch {
            database.dao().insertSaved(SavedClipboardEntity(slot = slot, content = content))
            val count = database.dao().countSaved(slot)
            withContext(Dispatchers.Main) {
                ClipboardTools.toast(this@FloatingBubbleService, "Saved as a new row in $slot Sheet (#$count)")
            }
        }
    }

    private fun generateAndCopyTotp() {
        val secret = ClipboardTools.readText(this)
        if (secret == null) {
            ClipboardTools.toast(this, "Copy your Base32 2FA secret key first.")
            return
        }

        serviceScope.launch {
            try {
                val code = withContext(Dispatchers.Default) { TotpGenerator.generate(secret) }
                withContext(Dispatchers.Main) {
                    ClipboardTools.copyText(this@FloatingBubbleService, "FloatClip 2FA code", code)
                    ClipboardTools.toast(this@FloatingBubbleService, "2FA code copied: $code")
                }
            } catch (exception: Exception) {
                withContext(Dispatchers.Main) {
                    ClipboardTools.toast(
                        this@FloatingBubbleService,
                        exception.message ?: "Could not generate a 2FA code."
                    )
                }
            }
        }
    }

    private fun roundedBackground(fill: Int, radius: Int, stroke: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        rootView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
                // The view can already be detached by the system.
            }
        }
        rootView = null
        actionPanel = null
        bubbleView = null
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "floatclip_bubble"
        private const val NOTIFICATION_ID = 4102
        private const val KEY_LAST_QUICK_TEXT_ID = "last_quick_text_id"
    }
}
