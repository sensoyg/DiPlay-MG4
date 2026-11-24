package com.evsuite.hardware.telemetry

import android.content.Context
import com.evsuite.hardware.saic.SaicCarBridge
import com.evsuite.hardware.saic.SaicVehicleCondition

data class ClimateSnapshot(
    val driverTargetCelsius: Float? = null,
)

data class TirePressuresSnapshot(
    val frontLeftKpa: Float? = null,
    val frontRightKpa: Float? = null,
    val rearLeftKpa: Float? = null,
    val rearRightKpa: Float? = null,
)

data class EnergyTelemetrySnapshot(
    val socPercent: Float? = null,
    val rangeKm: Float? = null,
    val batteryEnergyKwh: Float? = null,
    val batteryCapacityKwh: Float? = null,
    val chargePortConnected: Boolean? = null,
    val batteryPowerKw: Float? = null,
    val speedKmh: Float? = null,
    val parked: Boolean? = null,
    val odometerKm: Float? = null,
    val batteryTempCelsius: Float? = null,
    val cabinTempCelsius: Float? = null,
    val climate: ClimateSnapshot = ClimateSnapshot(),
    val tirePressures: TirePressuresSnapshot = TirePressuresSnapshot(),
)

/** Reads MG4 energy and driving telemetry through SAIC AAOS / CarPropertyManager paths. */
class EnergyTelemetryReader(context: Context) {
    init {
        SaicCarBridge.init(context.applicationContext)
    }

    fun read(): EnergyTelemetrySnapshot? {
        val soc = SaicCarBridge.readSocPercent()
        val range = SaicCarBridge.readRangeKm()
        val capacity = SaicCarBridge.readBatteryCapacityKwh()
        val energy = if (soc != null && capacity != null) capacity * (soc / 100f) else null
        val gear = SaicVehicleCondition.gearOrNull()
        val parked = gear?.let { it == 1 }
        val speed = SaicCarBridge.readSpeedKmh()
        val power = SaicCarBridge.readBatteryPowerKw()
        val plugged = SaicCarBridge.isChargePortConnected() ?: SaicCarBridge.isCharging()
        val odometer = SaicCarBridge.readOdometerKm()

        if (soc == null && range == null && speed == null && gear == null) return null

        return EnergyTelemetrySnapshot(
            socPercent = soc,
            rangeKm = range,
            batteryEnergyKwh = energy,
            batteryCapacityKwh = capacity,
            chargePortConnected = plugged,
            batteryPowerKw = power,
            speedKmh = speed,
            parked = parked,
            odometerKm = odometer,
        )
    }
}

