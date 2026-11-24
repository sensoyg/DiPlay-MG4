package com.evsuite.hardware.saic

/** Direct SAIC vehicle condition accessor matching the SWI68/DBI68/SWI69 gear contract (1 = P). */
object SaicVehicleCondition {
    fun gearOrNull(): Int? = SaicCarBridge.readGearOrNull()
}

