package com.shilapi.xcertplay.vehicle

import com.shilapi.xcertplay.transport.VehicleGear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Mg4SaicSignalsTest {
    @Test
    fun parsesDbi68AndSwiGenerationsAccurately() {
        assertEquals(
            Mg4SaicSignals.Generation.SWI68,
            Mg4SaicSignals.parseGeneration("DBI68-29558-1X00RXX-20250530"),
        )
        assertEquals(Mg4SaicSignals.Generation.SWI68, Mg4SaicSignals.parseGeneration("swi68-29558"))
        assertEquals(Mg4SaicSignals.Generation.SWI165, Mg4SaicSignals.parseGeneration("SWI165-123"))
        assertEquals(Mg4SaicSignals.Generation.SWI69, Mg4SaicSignals.parseGeneration("SWI69-29958-1300R45"))
        assertEquals(Mg4SaicSignals.Generation.SWI131, Mg4SaicSignals.parseGeneration("SWI131-12345"))
        assertEquals(Mg4SaicSignals.Generation.SWI131, Mg4SaicSignals.parseGeneration("SWI173-29958-1100R08"))
        assertEquals(Mg4SaicSignals.Generation.SWI132, Mg4SaicSignals.parseGeneration("SWI132-001"))
        assertEquals(Mg4SaicSignals.Generation.SWI133, Mg4SaicSignals.parseGeneration("SWI133-001"))

        assertEquals(Mg4SaicSignals.Generation.UNKNOWN, Mg4SaicSignals.parseGeneration("SWI1330-001"))
        assertEquals(Mg4SaicSignals.Generation.UNKNOWN, Mg4SaicSignals.parseGeneration("FOO68-123"))
        assertEquals(Mg4SaicSignals.Generation.UNKNOWN, Mg4SaicSignals.parseGeneration(null))
    }

    @Test
    fun mapsGearAndKmhSpeed() {
        assertEquals(VehicleGear.PARK, Mg4SaicSignals.gearFromRaw(1))
        assertEquals(VehicleGear.REVERSE, Mg4SaicSignals.gearFromRaw(2))
        assertEquals(VehicleGear.NEUTRAL, Mg4SaicSignals.gearFromRaw(3))
        assertEquals(VehicleGear.DRIVE, Mg4SaicSignals.gearFromRaw(4))
        assertNull(Mg4SaicSignals.gearFromRaw(0))
        assertNull(Mg4SaicSignals.gearFromRaw(null))

        assertEquals(10.0, Mg4SaicSignals.speedMetersPerSecondFromKmh(36f)!!, 1e-6)
        assertEquals(5.0, Mg4SaicSignals.speedMetersPerSecondFromKmh(-18f)!!, 1e-6)
        assertNull(Mg4SaicSignals.speedMetersPerSecondFromKmh(350f))
        assertNull(Mg4SaicSignals.speedMetersPerSecondFromKmh(null))
    }
}

