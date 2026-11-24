package com.shilapi.xcertplay.vehicle

import android.content.Context
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import com.shilapi.xcertplay.transport.VehicleStatusProvider

/** Selects the vehicle-specific read path without exposing vendor details to the CarPlay host. */
object VehicleIntegration {
    fun isMg4(context: Context): Boolean = Mg4Vehicle.available(context)

    /** MG4 has a native read-only telemetry source, so EV routing must not depend on a legacy pref. */
    fun vehicleStatusEnabled(context: Context): Boolean =
        if (isMg4(context)) {
            Mg4Vehicle.start(context)
            true
        } else {
            com.shilapi.xcertplay.hud.BydOutputSettings.batteryToIphone(context)
        }

    fun onAppOpened(context: Context) {
        runCatching {
            if (isMg4(context)) {
                // Supported MG firmware exposes battery, range and wheel speed without the BYD-only ADB bridge.
                com.shilapi.xcertplay.hud.BydOutputSettings.enableBatteryToIphoneByDefault(context)
                com.shilapi.xcertplay.hud.BydOutputSettings.enableWheelSpeedToIphoneByDefault(context)
                Mg4Vehicle.start(context)
            } else {
                BydNavigationOutputs.onAppOpened(context)
            }
        }
    }

    fun batteryStatus(context: Context): VehicleStatusProvider =
        if (isMg4(context)) Mg4Vehicle.also { it.start(context) }
        else BydNavigationOutputs.batteryStatus(context)

    fun wheelSpeed(context: Context): VehicleSpeedSource =
        if (isMg4(context)) Mg4WheelSpeedSource.attach(context)
        else BydNavigationOutputs.wheelSpeed(context)

    fun parked(context: Context): Boolean? =
        if (isMg4(context)) Mg4Vehicle.parked(context) else BydNavigationOutputs.parked(context)
}
