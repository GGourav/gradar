package com.gradar.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import com.gradar.GRadarApp
import com.gradar.MainActivity
import com.gradar.R
import com.gradar.radar.EntityProcessor
import kotlin.math.sqrt

/**
 * Overlay Service for displaying the radar
 */
class RadarOverlayService : android.app.Service() {

    companion object {
        const val TAG = "RadarOverlayService"
        const val ACTION_START = "com.gradar.action.OVERLAY_START"
        const val ACTION_STOP = "com.gradar.action.OVERLAY_STOP"
        const val NOTIFICATION_ID = 1002
        const val DEFAULT_RADAR_SIZE = 400
        const val RADAR_RANGE = 30f
    }

    private var windowManager: android.view.WindowManager? = null
    private var radarView: RadarView? = null
    private var isShowing = false
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!isShowing) showOverlay()
            ACTION_STOP -> {
                hideOverlay()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun showOverlay() {
        startForeground(NOTIFICATION_ID, createNotification())

        radarView = RadarView(this)

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

        windowManager?.addView(radarView, layoutParams)
        isShowing = true

        startUpdateLoop()
    }

    private fun startUpdateLoop() {
        updateRunnable = object : Runnable {
            override fun run() {
                radarView?.invalidate()
                handler.postDelayed(this, 100)
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun hideOverlay() {
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null

        radarView?.let {
            windowManager?.removeView(it)
            radarView = null
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

    /**
     * Custom View for drawing the radar
     */
    class RadarView(private val service: RadarOverlayService) : View(service) {

        private val paintBackground = Paint().apply {
            color = Color.argb(180, 0, 0, 0)
            style = Paint.Style.FILL
        }

        private val paintBorder = Paint().apply {
            color = Color.argb(255, 0, 150, 0)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        private val paintText = Paint().apply {
            color = Color.WHITE
            textSize = 10f
            isAntiAlias = true
        }

        private val paintTextSmall = Paint().apply {
            color = Color.LTGRAY
            textSize = 9f
            isAntiAlias = true
        }

        private val paintPlayer = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val paintMob = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val paintMobEnchanted = Paint().apply {
            color = Color.MAGENTA
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val paintMobBoss = Paint().apply {
            color = Color.argb(255, 255, 165, 0)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val paintResource = Paint().apply {
            color = Color.argb(255, 100, 149, 237)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val paintResourceRare = Paint().apply {
            color = Color.RED
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val paintChest = Paint().apply {
            color = Color.YELLOW
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val paintFishing = Paint().apply {
            color = Color.CYAN
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val paintLocalPlayer = Paint().apply {
            color = Color.argb(255, 0, 255, 0)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val width = width.toFloat()
            val height = height.toFloat()
            val centerX = width / 2
            val centerY = height / 2
            val scale = minOf(width, height) / (RADAR_RANGE * 2)

            // Draw background
            canvas.drawRect(0f, 0f, width, height, paintBackground)

            // Draw range circles
            paintBorder.alpha = 100
            for (i in 1..3) {
                val radius = (RADAR_RANGE / 3 * i) * scale
                canvas.drawCircle(centerX, centerY, radius, paintBorder)
            }
            paintBorder.alpha = 255

            // Draw border
            canvas.drawRect(0f, 0f, width, height, paintBorder)

            // Draw crosshair
            canvas.drawLine(centerX - 10, centerY, centerX + 10, centerY, paintBorder)
            canvas.drawLine(centerX, centerY - 10, centerX, centerY + 10, paintBorder)

            // Get local player position
            val (localX, localY) = EntityProcessor.getLocalPlayerPosition()

            // Draw entities
            val entities = EntityProcessor.getEntities()

            for (entity in entities) {
                val relX = (entity.posX - localX) * scale
                val relY = (entity.posY - localY) * scale

                val distance = sqrt(relX * relX + relY * relY)
                if (distance > (RADAR_RANGE * scale)) continue

                val screenX = centerX + relX
                val screenY = centerY - relY

                when (entity.type) {
                    EntityProcessor.EntityType.PLAYER -> {
                        canvas.drawCircle(screenX, screenY, 4f, paintPlayer)
                    }
                    EntityProcessor.EntityType.MOB -> {
                        val paint = when {
                            entity.enchant >= 3 -> paintMobBoss
                            entity.enchant >= 1 -> paintMobEnchanted
                            else -> paintMob
                        }
                        canvas.drawCircle(screenX, screenY, 3f, paint)
                    }
                    EntityProcessor.EntityType.HARVESTABLE -> {
                        val paint = when {
                            entity.tier >= 8 -> paintResourceRare
                            entity.enchant >= 1 -> paintMobEnchanted
                            else -> paintResource
                        }
                        canvas.drawCircle(screenX, screenY, 3f, paint)
                    }
                    EntityProcessor.EntityType.CHEST -> {
                        canvas.drawCircle(screenX, screenY, 5f, paintChest)
                    }
                    EntityProcessor.EntityType.FISHING_ZONE -> {
                        canvas.drawCircle(screenX, screenY, 4f, paintFishing)
                    }
                }
            }

            // Draw local player at center
            canvas.drawCircle(centerX, centerY, 5f, paintLocalPlayer)

            // Draw stats
            canvas.drawText(EntityProcessor.getStats(), 5f, 15f, paintText)
            canvas.drawText(EntityProcessor.getPacketStats(), 5f, 28f, paintTextSmall)

            // Draw legend
            var legendY = height - 60f
            canvas.drawText("Legend:", 5f, legendY, paintTextSmall)
            legendY += 12f
            canvas.drawCircle(10f, legendY - 3f, 3f, paintPlayer)
            canvas.drawText("Player", 18f, legendY, paintTextSmall)
            legendY += 10f
            canvas.drawCircle(10f, legendY - 3f, 3f, paintMob)
            canvas.drawText("Mob", 18f, legendY, paintTextSmall)
            legendY += 10f
            canvas.drawCircle(10f, legendY - 3f, 3f, paintResource)
            canvas.drawText("Resource", 18f, legendY, paintTextSmall)
            legendY += 10f
            canvas.drawCircle(10f, legendY - 3f, 3f, paintChest)
            canvas.drawText("Chest", 18f, legendY, paintTextSmall)
        }
    }
}
