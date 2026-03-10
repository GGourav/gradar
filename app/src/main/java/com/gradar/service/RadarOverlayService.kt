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

class RadarOverlayService : android.app.Service() {

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val OVERLAY_SIZE = 300
    }

    private var overlayView: View? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> showOverlay()
            "STOP" -> hideOverlay()
        }
        return START_STICKY
    }

    private fun showOverlay() {
        if (overlayView != null) return
        
        startForeground(NOTIFICATION_ID, createNotification())
        
        val params = android.view.WindowManager.LayoutParams(
            OVERLAY_SIZE,
            OVERLAY_SIZE,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                android.view.WindowManager.LayoutParams.TYPE_PHONE
            },
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 100
        }

        overlayView = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(128, 0, 0, 0))
            addView(TextView(context).apply {
                text = "G Radar\nStep 1"
                setTextColor(Color.GREEN)
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }

        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        wm.addView(overlayView, params)
    }

    private fun hideOverlay() {
        overlayView?.let {
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            wm.removeView(it)
            overlayView = null
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, GRadarApp.CHANNEL_OVERLAY)
            .setContentTitle("G Radar")
            .setContentText("Overlay Active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
