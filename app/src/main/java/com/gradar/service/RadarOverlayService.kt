package com.gradar.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.gradar.GRadarApp
import com.gradar.MainActivity
import com.gradar.R

/**
 * Overlay Service for displaying the radar on top of Albion Online
 * Uses SYSTEM_ALERT_WINDOW permission
 */
class RadarOverlayService : android.app.Service() {

    companion object {
        const val TAG = "RadarOverlayService"
        const val ACTION_START = "com.gradar.action.OVERLAY_START"
        const val ACTION_STOP = "com.gradar.action.OVERLAY_STOP"
        const val NOTIFICATION_ID_OVERLAY = 1002
        
        // Default radar size (will be configurable in Step 4)
        const val DEFAULT_RADAR_SIZE = 300
    }

    private var windowManager: WindowManager? = null
    private var radarView: View? = null
    private var isShowing = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!isShowing) {
                    showOverlay()
                }
            }
            ACTION_STOP -> {
                hideOverlay()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun showOverlay() {
        // Start foreground service
        startForeground(NOTIFICATION_ID_OVERLAY, createNotification())
        
        // Create radar view
        radarView = createRadarView()
        
        // Configure layout parameters
        val layoutParams = WindowManager.LayoutParams(
            DEFAULT_RADAR_SIZE,
            DEFAULT_RADAR_SIZE,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 100
        }
        
        // Add view to window
        windowManager?.addView(radarView, layoutParams)
        isShowing = true
        
        android.util.Log.d(TAG, "Overlay shown")
    }

    private fun createRadarView(): View {
        // Create a simple placeholder view for Step 1
        // Will be replaced with actual radar SurfaceView in Step 4
        val view = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.argb(128, 0, 0, 0))  // Semi-transparent black
            
            // Add placeholder content
            addView(android.widget.TextView(context).apply {
                text = "G Radar\nStep 1 Complete"
                setTextColor(android.graphics.Color.GREEN)
                gravity = Gravity.CENTER
                textSize = 12f
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }
        return view
    }

    private fun hideOverlay() {
        radarView?.let {
            windowManager?.removeView(it)
            radarView = null
        }
        isShowing = false
        android.util.Log.d(TAG, "Overlay hidden")
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return Notification.Builder(this, GRadarApp.CHANNEL_OVERLAY_SERVICE)
            .setContentTitle(getString(R.string.notification_overlay_title))
            .setContentText(getString(R.string.notification_overlay_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
