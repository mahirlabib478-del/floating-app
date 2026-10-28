package com.floatclip.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
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
import com.floatclip.app.data.ClipboardSheetEntity
import com.floatclip.app.data.FloatClipDatabase
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
    private var actionButtons: List<TextView> = emptyList()

    private val preferences by lazy {
        getSharedPreferences("floatclip_bubble", Context.MODE_PRIVATE)
    }

    private var settingsReceiverRegistered = false
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_APPLY_SETTINGS) applyBubbleAppearance()
        }
    }

    override fun onCreate() {
        super.onCreate()
        database = FloatClipDatabase.get(this)
        val filter = IntentFilter(ACTION_APPLY_SETTINGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(settingsReceiver, filter)
        }
        settingsReceiverRegistered = true
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
        val initialBubbleSize = bubbleSizeDp()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(0, dp(4), 0, 0)
            background = null
            elevation = 0f
        }
        actionPanel = panel
        actionButtons = createActionButtons()
        actionButtons.forEach(panel::addView)

        val bubble = TextView(this).apply {
            text = "FC"
            textSize = (initialBubbleSize * 0.27f).coerceIn(12f, 22f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = bubbleBackground()
            elevation = dp(8).toFloat()
            contentDescription = "FloatClip floating menu"
        }

        root.addView(
            bubble,
            LinearLayout.LayoutParams(dp(initialBubbleSize), dp(initialBubbleSize)).apply {
                topMargin = dp(6)
            }
        )
        root.addView(panel)

        windowParams = WindowManager.LayoutParams(
            dp(initialBubbleSize + ROOT_HORIZONTAL_PADDING_DP),
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
                            val opening = panel.visibility != View.VISIBLE
                            panel.visibility = if (opening) View.VISIBLE else View.GONE

                            // WRAP_CONTENT can keep the old narrow overlay width after a
                            // GONE panel becomes visible on some Android launchers.
                            // Set the overlay width explicitly so every action button is visible.
                            windowParams.width = dp(bubbleSizeDp() + ROOT_HORIZONTAL_PADDING_DP)
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


    private fun bubbleSizeDp(): Int =
        preferences.getInt(KEY_BUBBLE_SIZE_DP, DEFAULT_BUBBLE_SIZE_DP).coerceIn(40, 80)

    private fun bubbleOpacityPercent(): Int =
        preferences.getInt(KEY_BUBBLE_OPACITY_PERCENT, DEFAULT_BUBBLE_OPACITY_PERCENT).coerceIn(25, 100)

    private fun glassButtonBackground(): GradientDrawable {
        val alpha = (bubbleOpacityPercent() * 255 / 100).coerceIn(0, 255)
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(alpha, 92, 96, 102))
            setStroke(dp(2), Color.argb(230, 255, 255, 255))
        }
    }

    private fun bubbleBackground(): GradientDrawable = glassButtonBackground()

    private fun applyBubbleAppearance() {
        val size = bubbleSizeDp()
        val bubble = bubbleView
        if (bubble != null) {
            bubble.textSize = (size * 0.27f).coerceIn(12f, 22f)
            bubble.background = glassButtonBackground()
            val bubbleLayout = bubble.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(dp(size), dp(size))
            bubbleLayout.width = dp(size)
            bubbleLayout.height = dp(size)
            bubbleLayout.topMargin = dp(6)
            bubble.layoutParams = bubbleLayout
        }

        actionPanel?.let { panel ->
            panel.removeAllViews()
            actionButtons = createActionButtons()
            actionButtons.forEach(panel::addView)
        }
        actionButtons.forEach { button ->
            button.textSize = (size * if (button.text == "2FA") 0.22f else 0.27f).coerceIn(11f, 20f)
            button.background = glassButtonBackground()
            val params = button.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(dp(size), dp(size))
            params.width = dp(size)
            params.height = dp(size)
            params.bottomMargin = dp(8)
            button.layoutParams = params
        }

        windowParams.width = dp(size + ROOT_HORIZONTAL_PADDING_DP)
        rootView?.requestLayout()
        try {
            rootView?.let { windowManager.updateViewLayout(it, windowParams) }
        } catch (_: IllegalArgumentException) {
            // The overlay may be closing while settings are being applied.
        }
    }

    private fun createActionButtons(): List<TextView> {
        val buttons = mutableListOf(
            actionButton("TXT", "#EC4899") { copyNextQuickText() },
            actionButton("2FA", "#22D3EE") { generateAndCopyTotp() }
        )
        if (preferences.getBoolean(KEY_SHOW_BUTTON_A, true)) {
            buttons.add(actionButton("A", "#EC4899") { saveClipboardTo("A") })
        }
        if (preferences.getBoolean(KEY_SHOW_BUTTON_B, true)) {
            buttons.add(actionButton("B", "#EC4899") { saveClipboardTo("B") })
        }
        if (preferences.getBoolean(KEY_SHOW_BUTTON_C, true)) {
            buttons.add(actionButton("C", "#EC4899") { saveClipboardTo("C") })
        }
        buttons.add(actionButton("💔", "#F87171") { stopSelf() })
        return buttons
    }

    private fun actionButton(label: String, color: String, action: () -> Unit): TextView {
        val size = bubbleSizeDp()
        return TextView(this).apply {
            text = label
            textSize = (size * if (label == "2FA") 0.22f else 0.27f).coerceIn(11f, 20f)
            setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            background = glassButtonBackground()
            isClickable = true
            isFocusable = true
            elevation = dp(5).toFloat()
            contentDescription = when (label) {
                "TXT" -> "Copy next Quick Text"
                "2FA" -> "Generate and copy 2FA code"
                "A" -> "Save clipboard text to Column A"
                "B" -> "Save clipboard text to Column B"
                "C" -> "Save clipboard text to Column C"
                else -> "Close FloatClip bubble"
            }
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply {
                bottomMargin = dp(8)
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
            val dao = database.dao()
            // Every clipboard save creates a new row; earlier records are preserved.
            val position = (dao.maxClipboardSheetPosition() ?: -1) + 1
            val row = when (slot) {
                "A" -> ClipboardSheetEntity(position = position, colA = content)
                "B" -> ClipboardSheetEntity(position = position, colB = content)
                else -> ClipboardSheetEntity(position = position, colC = content)
            }
            dao.insertClipboardSheet(row)

            withContext(Dispatchers.Main) {
                ClipboardTools.toast(
                    this@FloatingBubbleService,
                    "Saved to Clipboard Sheet, column $slot. Added as a new row."
                )
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
        actionButtons = emptyList()
        if (settingsReceiverRegistered) {
            try {
                unregisterReceiver(settingsReceiver)
            } catch (_: IllegalArgumentException) {
                // Receiver may already be unregistered during shutdown.
            }
            settingsReceiverRegistered = false
        }
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "floatclip_bubble"
        private const val NOTIFICATION_ID = 4102
        private const val KEY_LAST_QUICK_TEXT_ID = "last_quick_text_id"
        const val ACTION_APPLY_SETTINGS = "com.floatclip.app.ACTION_APPLY_BUBBLE_SETTINGS"
        private const val KEY_BUBBLE_SIZE_DP = "bubble_size_dp"
        private const val KEY_BUBBLE_OPACITY_PERCENT = "bubble_opacity_percent"
        private const val KEY_SHOW_BUTTON_A = "show_bubble_button_a"
        private const val KEY_SHOW_BUTTON_B = "show_bubble_button_b"
        private const val KEY_SHOW_BUTTON_C = "show_bubble_button_c"
        private const val DEFAULT_BUBBLE_SIZE_DP = 58
        private const val DEFAULT_BUBBLE_OPACITY_PERCENT = 75
        private const val ROOT_HORIZONTAL_PADDING_DP = 8

    }
}
