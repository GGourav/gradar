package com.gradar.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import com.gradar.GRadarApp
import com.gradar.MainActivity
import com.gradar.R
import com.gradar.model.GameEntity
import com.gradar.protocol.PhotonProtocol
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * Overlay Service for displaying the radar
 */
class RadarOverlayService : android.app.Service(), SurfaceHolder.Callback {

    companion object {
        const val TAG = "RadarOverlayService"
        const val ACTION_START = "com.gradar.action.OVERLAY_START"
        const val ACTION_STOP = "com.gradar.action.OVERLAY_STOP"
        const val NOTIFICATION_ID = 1002
        const val DEFAULT_RADAR_SIZE = 300
    }

    private var windowManager: android.view.WindowManager? = null
    private var containerLayout: FrameLayout? = null
    private var surfaceView: SurfaceView? = null
    private var isShowing = false
    
    // Entity tracking
    private val entities = mutableMapOf<Long, GameEntity>()
    private var playerX: Float = 0f
    private var playerY: Float = 0f
    
    // Rendering
    private var renderThread: Thread? = null
    private var isRendering = false
    
    // Paint objects
    private val backgroundPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }
    
    private val gridPaint = Paint().apply {
        color = Color.argb(60, 0, 255, 136)
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }
    
    private val playerPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 10f
        isAntiAlias = true
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        EventBus.getDefault().register(this)
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
        
        containerLayout = FrameLayout(this)
        
        surfaceView = SurfaceView(this).apply {
            holder.addCallback(this@RadarOverlayService)
            setZOrderOnTop(true)
            holder.setFormat(PixelFormat.TRANSLUCENT)
        }
        
        containerLayout?.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        
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
            windowManager?.addView(containerLayout, layoutParams)
            isShowing = true
            android.util.Log.d(TAG, "Overlay shown successfully")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to show overlay: ${e.message}")
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        startRendering(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Not needed
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopRendering()
    }

    private fun startRendering(holder: SurfaceHolder) {
        isRendering = true
        renderThread = Thread {
            while (isRendering) {
                try {
                    val canvas = holder.lockCanvas()
                    if (canvas != null) {
                        drawRadar(canvas)
                        holder.unlockCanvasAndPost(canvas)
                    }
                    Thread.sleep(33) // ~30 FPS
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Render error: ${e.message}")
                }
            }
        }.apply { start() }
    }

    private fun stopRendering() {
        isRendering = false
        renderThread?.interrupt()
        renderThread = null
    }

    private fun drawRadar(canvas: Canvas) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        val centerX = width / 2
        val centerY = height / 2
        val radius = minOf(width, height) / 2 - 10

        // Draw background
        canvas.drawCircle(centerX, centerY, radius, backgroundPaint)

        // Draw grid rings
        canvas.drawCircle(centerX, centerY, radius * 0.33f, gridPaint)
        canvas.drawCircle(centerX, centerY, radius * 0.66f, gridPaint)
        canvas.drawCircle(centerX, centerY, radius, gridPaint)

        // Draw cross
        canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, gridPaint)
        canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, gridPaint)

        // Draw entities
        synchronized(entities) {
            for (entity in entities.values) {
                val dx = (entity.posX - playerX) / 50f // Scale factor
                val dy = (entity.posY - playerY) / 50f
                
                val x = centerX + dx.coerceIn(-radius, radius)
                val y = centerY + dy.coerceIn(-radius, radius)
                
                val paint = getEntityPaint(entity)
                canvas.drawCircle(x, y, 5f, paint)
            }
        }

        // Draw player (center)
        canvas.drawCircle(centerX, centerY, 6f, playerPaint)
        
        // Draw entity count
        canvas.drawText("Entities: ${entities.size}", 10f, 20f, textPaint)
    }

    private fun getEntityPaint(entity: GameEntity): Paint {
        val paint = Paint()
        paint.style = Paint.Style.FILL
        paint.isAntiAlias = true
        
        when {
            entity.isResource() -> {
                // Blue for resources
                paint.color = when (entity.tier) {
                    in 1..3 -> Color.rgb(100, 150, 255)
                    in 4..5 -> Color.rgb(70, 130, 255)
                    in 6..7 -> Color.rgb(50, 100, 255)
                    else -> Color.rgb(30, 70, 255)
                }
            }
            entity.isMob() -> {
                when {
                    entity.isBoss() -> paint.color = Color.rgb(255, 136, 0) // Orange
                    entity.isEnchanted() -> paint.color = Color.rgb(170, 68, 255) // Purple
                    else -> paint.color = Color.rgb(68, 255, 68) // Green
                }
            }
            entity.isPlayer() -> {
                paint.color = Color.WHITE // White for players
            }
            entity.isMist() -> {
                paint.color = Color.rgb(0, 255, 200) // Cyan for mists
            }
            else -> {
                paint.color = Color.GRAY
            }
        }
        
        return paint
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEntityUpdate(event: RadarVpnService.EntityUpdateEvent) {
        synchronized(entities) {
            entities[event.entity.id] = event.entity
        }
    }

    private fun hideOverlay() {
        stopRendering()
        try {
            containerLayout?.let {
                windowManager?.removeView(it)
                containerLayout = null
                surfaceView = null
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to hide overlay: ${e.message}")
        }
        isShowing = false
        entities.clear()
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
        EventBus.getDefault().unregister(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
