package com.shilapi.xcertplay.vehicle

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.evsuite.hardware.saic.SaicVehicleCondition
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Native MG4 wheel-speed and gear sampler (`$PASCD` dead reckoning in tunnels/garages) using
 * SAIC's read-only `CarPropertyManager` and `VehicleConditionManager`/`CarStateClient` without ADB.
 */
object Mg4WheelSpeedSource : VehicleSpeedSource {
    private const val TAG = "DiPlay-MG4-Speed"
    private const val READ_MILLIS = 250L
    private const val GEAR_EVERY_READS = 4
    private const val MAX_SAMPLES = 40

    private var context: Context? = null
    private var executor: ScheduledExecutorService? = null
    private val samples = ArrayList<VehicleSpeedSample>()
    private var gear: VehicleGear? = null
    private var reads = 0
    private var firstSampleLogged = false

    fun attach(appContext: Context) = apply { context = appContext.applicationContext }

    @Synchronized
    override fun start() {
        if (executor != null) return
        samples.clear()
        gear = null
        reads = 0
        firstSampleLogged = false
        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "diplay-mg4-wheel-speed").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay(::poll, 0, READ_MILLIS, TimeUnit.MILLISECONDS) }
        Log.i(TAG, "MG4 wheel speed started")
    }

    override fun stop() {
        val running = synchronized(this) { executor.also { executor = null } } ?: return
        running.shutdown()
        Log.i(TAG, "MG4 wheel speed stopped")
    }

    @Synchronized
    override fun drain(): VehicleSpeedReading? {
        val current = gear ?: return null
        if (samples.isEmpty()) return null
        return VehicleSpeedReading(current, samples.toList()).also { samples.clear() }
    }

    private fun poll() {
        val app = context ?: return
        if (reads++ % GEAR_EVERY_READS == 0) {
            val rawGear = runCatching { SaicVehicleCondition.gearOrNull() }.getOrNull()
                ?: Mg4SaicSignals.readGearOrNull(app)
            Mg4SaicSignals.gearFromRaw(rawGear)?.let { next ->
                val changed = synchronized(this) { (gear != next).also { gear = next } }
                if (changed) Log.i(TAG, "gear $next")
            }
        }
        val before = SystemClock.elapsedRealtime()
        val speedMps = Mg4SaicSignals.speedMetersPerSecondFromKmh(Mg4SaicSignals.readSpeedKmh(app)) ?: return
        val sample = VehicleSpeedSample((before + SystemClock.elapsedRealtime()) / 2, speedMps)
        synchronized(this) {
            if (samples.size == MAX_SAMPLES) samples.removeAt(0)
            samples += sample
        }
        if (!firstSampleLogged) {
            firstSampleLogged = true
            Log.i(TAG, "first wheel speed ${"%.1f".format(speedMps * 3.6)} km/h")
        }
    }
}

