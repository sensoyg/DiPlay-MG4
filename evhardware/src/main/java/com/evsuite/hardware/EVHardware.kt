package com.evsuite.hardware

import com.evsuite.hardware.saic.SaicVehicleCondition

/** General MG4 hardware state helper. */
object EVHardware {
    fun isVehicleInPark(): Boolean? = SaicVehicleCondition.gearOrNull()?.let { it == 1 }
}

