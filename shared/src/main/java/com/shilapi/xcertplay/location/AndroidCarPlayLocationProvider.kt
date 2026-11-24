package com.shilapi.xcertplay.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.transport.CarPlayLocationFix
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.NmeaLocationEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground Android location source for iAP2 LocationInformation.
 *
 * The freshest usable fix from GPS, fused, or network location is sent.
 */
class AndroidCarPlayLocationProvider(
    context: Context,
    private val preferredLocationAgeMillis: Long = DEFAULT_PREFERRED_LOCATION_AGE_MILLIS,
) : Iap2LocationProvider {
    private val locationManager =
        context.applicationContext.getSystemService(LocationManager::class.java)
    private val stateLock = Any()
    private val preferredProviders = buildList {
        // SAIC MG4 T-Box feeds Android's "fused" provider even on Android 9, where it is often
        // omitted from LocationManager.allProviders; keep it in the candidate list unconditionally.
        add(FUSED_PROVIDER)
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
    }
    private val latestFixes = ConcurrentHashMap<String, Location>()
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val provider = location.provider ?: return
            if (provider in preferredProviders && isUsableFix(location)) {
                latestFixes[provider] = location
            }
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) = Unit
    }

    private var started = false

    @SuppressLint("MissingPermission")
    override fun start(): Boolean = synchronized(stateLock) {
        if (started) {
            true
        } else {
            seedLastKnownLocations()
            var subscribedProviders = 0
            for (provider in preferredProviders) {
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        UPDATE_INTERVAL_MILLIS,
                        MIN_DISTANCE_METERS,
                        listener,
                        Looper.getMainLooper(),
                    )
                    subscribedProviders++
                } catch (error: Exception) {
                    Log.w(TAG, "Could not subscribe to Android location provider $provider", error)
                }
            }
            started = subscribedProviders > 0
            if (!started) {
                Log.w(TAG, "No Android location provider could be started")
            }
            started
        }
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnownLocations() {
        for (provider in preferredProviders) {
            val location = try {
                locationManager.getLastKnownLocation(provider)
            } catch (error: Exception) {
                Log.w(TAG, "Could not read last known location from $provider", error)
                null
            }?.takeIf(::isUsableFix) ?: continue
            latestFixes[provider] = location
            Log.i(
                TAG,
                "Seeded $provider location ageMs=${(System.currentTimeMillis() - location.time).coerceAtLeast(0)}",
            )
        }
    }

    override fun stop() {
        val shouldUnregister = synchronized(stateLock) {
            val wasStarted = started
            started = false
            wasStarted
        }
        if (!shouldUnregister) return
        try {
            locationManager.removeUpdates(listener)
        } catch (_: Exception) {
            // Already unregistered.
        }
    }

    override fun latestNmea(): String? {
        val fix = latestFix() ?: return null
        return try {
            NmeaLocationEncoder.encode(
                CarPlayLocationFix(
                    latitudeDegrees = fix.latitude,
                    longitudeDegrees = fix.longitude,
                    altitudeMeters = fix.altitude.takeIf { fix.hasAltitude() }?.toDouble(),
                    bearingDegrees = fix.bearing.takeIf { fix.hasBearing() }?.toDouble(),
                    speedMetersPerSecond = fix.speed.takeIf { fix.hasSpeed() }?.toDouble(),
                    accuracyMeters = fix.accuracy.takeIf { fix.hasAccuracy() }?.toDouble(),
                    timestampMillis = fix.time,
                ),
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Could not encode Android location as NMEA", error)
            null
        }
    }

    private fun latestFix(): Location? {
        val now = System.currentTimeMillis()
        return preferredProviders.asSequence()
            .mapNotNull(latestFixes::get)
            .filter { isUsableFix(it) && now - it.time <= preferredLocationAgeMillis }
            .maxByOrNull { it.time }
    }

    private fun isUsableFix(location: Location): Boolean =
        !(location.latitude == 0.0 && location.longitude == 0.0)

    companion object {
        private const val TAG = "CarPlayLocation"
        private const val FUSED_PROVIDER = "fused"
        private const val UPDATE_INTERVAL_MILLIS = 1_000L
        private const val MIN_DISTANCE_METERS = 0f
        private const val DEFAULT_PREFERRED_LOCATION_AGE_MILLIS = 30_000L

        /** Privacy-safe provider diagnostics (no coordinates) for the diagnostic export. */
        @SuppressLint("MissingPermission")
        fun providerDiagnostics(context: Context): String {
            val manager = context.applicationContext.getSystemService(LocationManager::class.java)
                ?: return "locationManager=unavailable"
            val listed = runCatching { manager.allProviders.joinToString(",") }.getOrDefault("unavailable")
            val now = System.currentTimeMillis()
            val states = listOf(FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .joinToString(" ") { provider ->
                    val fix = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                    when {
                        fix == null -> "$provider=none"
                        fix.latitude == 0.0 && fix.longitude == 0.0 -> "$provider=zero"
                        else -> "$provider=ageMs:${(now - fix.time).coerceAtLeast(0)}"
                    }
                }
            return "allProviders=[$listed] $states"
        }
    }
}
