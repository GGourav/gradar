package com.gradar.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
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
 * Overlay Service - Full radar rendering with all entity types
 */
class RadarOverlayService : android.app.Service(), SurfaceHolder.Callback {

    companion object {
        const val TAG = "RadarOverlayService"
        const val ACTION_START = "com.gradar.action.OVERLAY_START"
        const val ACTION_STOP = "com.gradar.action.OVERLAY_STOP"
        const val NOTIFICATION_ID = 1002
        var radarSize = 300
    }

    private var windowManager: android.view.WindowManager? = null
    private var containerLayout: FrameLayout? = null
    private var surfaceView: SurfaceView? = null
    private var isShowing = false
    
    private val entities = mutableMapOf<Long, GameEntity>()
    private var playerX: Float = 0f
    private var playerY: Float = 0f
    
    private var renderThread: Thread? = null
    private var isRendering = false
    
    // Paints
    private val backgroundPaint = Paint().apply {
        color = Color.argb(200, 10, 15, 25)
        style = Paint.Style.FILL
    }
    
    private val borderPaint = Paint().apply {
        color = Color.argb(100, 0, 255, 136)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    
    private val gridPaint = Paint().apply {
        color = Color.argb(40, 0, 255, 136)
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }
    
    private val sweepPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 10f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }
    
    private val playerPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Entity paints
    private val resourcePaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val mobPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val bossPaint = Paint().apply { color = Color.rgb(255, 136, 0); style = Paint.Style.FILL; isAntiAlias = true }
    private val enchantedMobPaint = Paint().apply { color = Color.rgb(170, 68, 255); style = Paint.Style.FILL; isAntiAlias = true }
    private val playerDotPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val hostilePlayerPaint = Paint().apply { color = Color.RED; style = Paint.Style.FILL; isAntiAlias = true }
    private val mistPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val dungeonPaint = Paint().apply { color = Color.rgb(128, 0, 128); style = Paint.Style.FILL; isAntiAlias = true }
    private val chestPaint = Paint().apply { color = Color.rgb(255, 215, 0); style = Paint.Style.FILL; isAntiAlias = true }
    private val fishingPaint = Paint().apply { color = Color.CYAN; style = Paint.Style.FILL; isAntiAlias = true }

    // Enchant ring colors
    private val enchantColors = listOf(
        Color.GREEN,        // .0 - Green
        Color.GREEN,        // .1 - Green
        Color.rgb(0, 100, 255), // .2 - Dark Blue
        Color.rgb(170, 68, 255), // .3 - Purple
        Color.rgb(255, 215, 0)   // .4 - Gold
    )
    
    private var sweepAngle = 0f

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
            radarSize,
            radarSize,
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
            android.util.Log.d(TAG, "Overlay shown")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to show overlay: ${e.message}")
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        startRendering(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

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
                    sweepAngle = (sweepAngle + 2) % 360
                    Thread.sleep(33)
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

        // Background
        canvas.drawCircle(centerX, centerY, radius, backgroundPaint)
        canvas.drawCircle(centerX, centerY, radius, borderPaint)

        // Grid rings
        canvas.drawCircle(centerX, centerY, radius * 0.33f, gridPaint)
        canvas.drawCircle(centerX, centerY, radius * 0.66f, gridPaint)

        // Cross
        canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, gridPaint)
        canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, gridPaint)

        // Sweep effect
        sweepPaint.shader = SweepGradient(centerX, centerY, 
            intArrayOf(Color.TRANSPARENT, Color.argb(50, 0, 255, 136), Color.TRANSPARENT),
            null)
        canvas.save()
        canvas.rotate(sweepAngle, centerX, centerY)
        canvas.drawArc(
            RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius),
            0f, 30f, true, sweepPaint
        )
        canvas.restore()

        // Draw entities
        synchronized(entities) {
            for (entity in entities.values) {
                drawEntity(canvas, entity, centerX, centerY, radius)
            }
        }

        // Player (center)
        canvas.drawCircle(centerX, centerY, 6f, playerPaint)
        
        // Stats
        textPaint.color = Color.WHITE
        canvas.drawText("Entities: ${entities.size}", 10f, 20f, textPaint)
    }

    private fun drawEntity(canvas: Canvas, entity: GameEntity, centerX: Float, centerY: Float, radius: Float) {
        val scale = 50f
        val dx = (entity.posX - playerX) / scale
        val dy = (entity.posY - playerY) / scale
        
        val x = centerX + dx.coerceIn(-radius + 5, radius - 5)
        val y = centerY + dy.coerceIn(-radius + 5, radius - 5)

        val paint = getEntityPaint(entity)
        val dotSize = when {
            entity.isBoss() -> 8f
            entity.isPlayer() -> 5f
            else -> 5f
        }
        
        // Draw dot
        canvas.drawCircle(x, y, dotSize, paint)
        
        // Draw enchant ring for resources/mobs
        if (entity.isEnchanted() && (entity.isResource() || entity.isMob())) {
            val ringPaint = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                isAntiAlias = true
                color = enchantColors.getOrElse(entity.getEnchantColor()) { Color.GREEN }
            }
            canvas.drawCircle(x, y, dotSize + 3, ringPaint)
        }
        
        // Diamond shape for mists
        if (entity.isMist()) {
            val path = Path()
            path.moveTo(x, y - 6)
            path.lineTo(x + 5, y)
            path.lineTo(x, y + 6)
            path.lineTo(x - 5, y)
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    private fun getEntityPaint(entity: GameEntity): Paint {
        return when {
            entity.isResource() -> {
                resourcePaint.color = when {
                    entity.tier >= 8 -> Color.rgb(255, 100, 100)  // Red T8
                    entity.tier >= 6 -> Color.rgb(100, 150, 255)  // Blue T6-T7
                    entity.tier >= 4 -> Color.rgb(100, 200, 255)  // Light blue T4-T5
                    else -> Color.rgb(68, 136, 255)               // Blue T1-T3
                }
                resourcePaint
            }
            entity.isMob() -> {
                when {
                    entity.isBoss() -> bossPaint
                    entity.isEnchanted() -> enchantedMobPaint
                    else -> {
                        mobPaint.color = Color.rgb(68, 255, 68)
                        mobPaint
                    }
                }
            }
            entity.isPlayer() -> {
                playerDotPaint.color = if (entity.isHostile) Color.RED else Color.WHITE
                playerDotPaint
            }
            entity.isMist() -> {
                mistPaint.color = when (entity.rarity) {
                    0 -> Color.WHITE          // Common
                    1 -> Color.GREEN          // Uncommon
                    2 -> Color.BLUE           // Rare
                    3 -> Color.rgb(170, 68, 255)  // Legendary
                    4 -> Color.rgb(255, 215, 0)   // Epic
                    else -> Color.CYAN
                }
                mistPaint
            }
            entity.isDungeon() -> dungeonPaint
            entity.isChest() -> chestPaint
            entity.isFishing() -> fishingPaint
            else -> {
                mobPaint.color = Color.GRAY
                mobPaint
            }
        }
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
