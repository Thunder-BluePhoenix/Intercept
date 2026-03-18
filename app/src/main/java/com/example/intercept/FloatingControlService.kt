package com.example.intercept

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat

class FloatingControlService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var isRecording = false

    companion object {
        private const val TAG = "FloatingService"
        private const val NOTIFICATION_ID = 102
        private const val CHANNEL_ID = "floating_service_channel"
        var isRunning = false
            private set
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            isRecording = intent?.getBooleanExtra("isRecording", false) ?: false
            updateButtonState()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundSafe()
        
        try {
            val filter = IntentFilter("com.example.intercept.RECORDING_STATUS")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(statusReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering receiver", e)
        }
    }

    private fun startForegroundSafe() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Control",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Intercept")
            .setContentText("Floating control active")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Settings.canDrawOverlays(this)) {
            showFloatingView()
        } else {
            stopSelf()
        }
        return START_STICKY
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun showFloatingView() {
        if (floatingView != null) return

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val layoutParams = WindowManager.LayoutParams().apply {
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 100
            }

            floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_control, null)
            
            val recordIcon = floatingView?.findViewById<ImageView>(R.id.img_record_toggle)
            val controlsLayout = floatingView?.findViewById<LinearLayout>(R.id.ll_controls)
            val btnRecord = floatingView?.findViewById<Button>(R.id.btn_float_record)
            val btnClose = floatingView?.findViewById<Button>(R.id.btn_float_close)

            val btnOpenApp = floatingView?.findViewById<Button>(R.id.btn_float_open_app)

            btnOpenApp?.setOnClickListener {
                val mainIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(mainIntent)
                controlsLayout?.visibility = View.GONE
            }

            btnRecord?.setOnClickListener {
                if (isRecording) {
                    val stopIntent = Intent(this, RecordingService::class.java).apply {
                        action = RecordingService.ACTION_STOP
                    }
                    startService(stopIntent)
                } else {
                    if (RecordingService.hasResultData()) {
                        val startIntent = Intent(this, RecordingService::class.java).apply {
                            action = RecordingService.ACTION_START
                        }
                        startService(startIntent)
                    } else {
                        val mainIntent = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(mainIntent)
                    }
                }
                controlsLayout?.visibility = View.GONE
            }

            btnClose?.setOnClickListener {
                stopSelf()
            }

            recordIcon?.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private var isDragging = false

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = layoutParams.x
                            initialY = layoutParams.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isDragging = false
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - initialTouchX
                            val dy = event.rawY - initialTouchY
                            if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                                isDragging = true
                            }
                            if (isDragging) {
                                layoutParams.x = initialX + dx.toInt()
                                layoutParams.y = initialY + dy.toInt()
                                windowManager?.updateViewLayout(floatingView, layoutParams)
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!isDragging) {
                                // Treat as click
                                controlsLayout?.visibility = if (controlsLayout?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                            }
                            return true
                        }
                    }
                    return false
                }
            })

            windowManager?.addView(floatingView, layoutParams)
            isRecording = RecordingService.isRecording
            updateButtonState()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing floating view", e)
        }
    }

    private fun updateButtonState() {
        val btnRecord = floatingView?.findViewById<Button>(R.id.btn_float_record)
        btnRecord?.text = if (isRecording) "Stop" else "Start"
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {}
        
        if (floatingView != null) {
            try {
                windowManager?.removeView(floatingView)
            } catch (e: Exception) {}
            floatingView = null
        }
    }
}
