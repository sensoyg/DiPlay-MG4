package com.shilapi.xcertplay

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.shilapi.xcertplay.vehicle.Mg4SaicSignals

internal object Mg4LauncherOverlaySupport {
    data class OverlayProfile(
        val launcherPackage: String,
        val baseWidth: Int = 1920,
        val baseHeight: Int = 720,
        val x: Int = 1326,
        val y: Int = 88,
        val width: Int = 410,
        val height: Int = 298,
    ) {
        fun scaledFor(screenWidth: Int, screenHeight: Int): OverlayProfile {
            if (screenWidth <= 0 || screenHeight <= 0 ||
                (screenWidth == baseWidth && screenHeight == baseHeight)
            ) {
                return this
            }
            val sx = screenWidth.toFloat() / baseWidth
            val sy = screenHeight.toFloat() / baseHeight
            return copy(
                baseWidth = screenWidth,
                baseHeight = screenHeight,
                x = (x * sx).toInt(),
                y = (y * sy).toInt(),
                width = (width * sx).toInt().coerceAtLeast(1),
                height = (height * sy).toInt().coerceAtLeast(1),
            )
        }
    }

    fun profileForVersion(version: String?): OverlayProfile? =
        when (Mg4SaicSignals.parseGeneration(version)) {
            Mg4SaicSignals.Generation.SWI68,
            Mg4SaicSignals.Generation.SWI165,
            Mg4SaicSignals.Generation.SWI133 -> OverlayProfile(launcherPackage = "com.saicmotor.hmi.launcher")
            Mg4SaicSignals.Generation.SWI69,
            Mg4SaicSignals.Generation.SWI131,
            Mg4SaicSignals.Generation.SWI132 -> OverlayProfile(launcherPackage = "com.saicmotor.launcher")
            Mg4SaicSignals.Generation.UNKNOWN -> null
        }

    fun profileForDetected(): OverlayProfile? =
        profileForVersion(Mg4SaicSignals.detectedVersionString())

    fun isDetectedSupported(): Boolean = profileForDetected() != null

    fun isDetectedSwi69(): Boolean =
        Mg4SaicSignals.detectedMt2712Version()?.startsWith("SWI69", ignoreCase = true) == true
}

/**
 * Replaces the click action of the stock MG4 launcher's Apple CarPlay card.
 *
 * Supports both SWI69 (`com.saicmotor.launcher`) and DBI68/SWI68 (`com.saicmotor.hmi.launcher`).
 * The view is fully transparent: the icon, text, divider and spacing remain the stock
 * launcher's own UI. This service exists only in the platform-signed mobile manifest.
 */
class Mg4LauncherOverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var touchTarget: View
    private var profile: Mg4LauncherOverlaySupport.OverlayProfile? = null
    private var attached = false

    private val monitor = object : Runnable {
        override fun run() {
            if (!AirPlayPersistence.loadLauncherOverlayEnabled(this@Mg4LauncherOverlayService)) {
                detachOverlay()
                stopSelf()
                return
            }
            val activeProfile = profile
            val shouldShow = activeProfile != null && isStockLauncherVisible(activeProfile.launcherPackage)
            if (shouldShow && !attached) {
                windowManager.addView(touchTarget, layoutParams(activeProfile!!))
                attached = true
            } else if (!shouldShow && attached) {
                detachOverlay()
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val detected = Mg4LauncherOverlaySupport.profileForDetected()
        if (Process.myUid() != Process.SYSTEM_UID ||
            detected == null ||
            !AirPlayPersistence.loadLauncherOverlayEnabled(this)
        ) {
            stopSelf()
            return
        }
        profile = detected
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        touchTarget = View(this).apply {
            setBackgroundColor(0x00000000)
            isClickable = true
            setOnClickListener { openDiPlay() }
        }
        handler.post(monitor)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    @Suppress("DEPRECATION")
    private fun isStockLauncherVisible(launcherPackage: String): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val top = manager.getRunningTasks(1).firstOrNull()?.topActivity
        return top?.packageName == launcherPackage
    }

    private fun layoutParams(activeProfile: Mg4LauncherOverlaySupport.OverlayProfile): WindowManager.LayoutParams {
        val metrics = resources.displayMetrics
        val scaled = activeProfile.scaledFor(metrics.widthPixels, metrics.heightPixels)
        return WindowManager.LayoutParams(
            scaled.width,
            scaled.height,
            WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = scaled.x
            y = scaled.y
        }
    }

    private fun detachOverlay() {
        if (!attached) return
        runCatching { windowManager.removeView(touchTarget) }
        attached = false
    }

    private fun openDiPlay() {
        startActivity(Intent(this, DiPlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        })
    }

    override fun onDestroy() {
        handler.removeCallbacks(monitor)
        detachOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val POLL_INTERVAL_MS = 400L
    }
}
