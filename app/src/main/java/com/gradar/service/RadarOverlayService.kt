package com.gradar.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.gradar.GRadarApp
import com.gradar.MainActivity
import com.gradar.R

/**
 * Overlay Service for displaying the radar
 */
class RadarOverlayService : android.app.Service() {

    companion object {
        const val TAG = "RadarOverlayService"
        const val ACTION_START = "com.gradar.action.OVERLAY_START"
        const val ACTION_STOP = "com.gradar.action.OVERLAY_STOP"
        const val NOTIFICATION_ID = 1002
        const val DEFAULT_RADAR_SIZE = 300
    }

    private var windowManager: android.view.WindowManager? = null
    private var radarView: View? = null
    private var isShowing = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!isShowing) showOverlay()
            ACTION_STOP -> { hideOverlay(); stopSelf() }
        }
        return START_STICKY
    }

    private fun showOverlay() {
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to start foreground: ${e.message}")
        }
        
        radarView = createRadarView()
        
        val layoutParams = android.view.WindowManager.LayoutParams(
            DEFAULT_RADAR_SIZE,
            DEFAULT_RADAR_SIZE,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                android.view.WindowManager.LayoutParams.TYPE_PHONE
            },
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 100
        }
        
        try {
            windowManager?.addView(radarView, layoutParams)
            isShowing = true
            android.util.Log.d(TAG, "Overlay shown successfully")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to show overlay: ${e.message}")
        }
    }

    private fun createRadarView(): View {
        return FrameLayout(this).apply {
            setBackgroundColor(Color.argb(128, 0, 0, 0))
            addView(TextView(context).apply {
                text = "G Radar\nStep 1 Complete"
                setTextColor(Color.GREEN)
                gravity = Gravity.CENTER
                textSize = 12f
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }
    }

    private fun hideOverlay() {
        try {
            radarView?.let {
                windowManager?.removeView(it)
                radarView = null
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to hide overlay: ${e.message}")
        }
        isShowing = false
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
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
