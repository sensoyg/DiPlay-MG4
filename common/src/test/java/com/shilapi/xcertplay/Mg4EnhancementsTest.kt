package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mg4EnhancementsTest {
    @Test
    fun launcherOverlayResolvesDbi68AndSwi69Packages() {
        val dbi68 = Mg4LauncherOverlaySupport.profileForVersion("DBI68-29558-1X00RXX-20250530")
        assertNotNull(dbi68)
        assertEquals("com.saicmotor.hmi.launcher", dbi68!!.launcherPackage)

        val swi69 = Mg4LauncherOverlaySupport.profileForVersion("SWI69-29958-1300R45")
        assertNotNull(swi69)
        assertEquals("com.saicmotor.launcher", swi69!!.launcherPackage)

        assertNull(Mg4LauncherOverlaySupport.profileForVersion("UNKNOWN-BUILD"))

        val scaled = dbi68.scaledFor(960, 360)
        assertEquals(663, scaled.x)
        assertEquals(44, scaled.y)
        assertEquals(205, scaled.width)
        assertEquals(149, scaled.height)
    }

    @Test
    fun nightModePreferenceFollowsHeadlightsThenSystem() {
        assertTrue(resolveNightMode(NightModePreference.AUTO, systemDark = false, sideLightsOn = true))
        assertFalse(resolveNightMode(NightModePreference.AUTO, systemDark = true, sideLightsOn = false))
        assertTrue(resolveNightMode(NightModePreference.AUTO, systemDark = true, sideLightsOn = null))
        assertFalse(resolveNightMode(NightModePreference.AUTO, systemDark = false, sideLightsOn = null))

        assertTrue(resolveNightMode(NightModePreference.SYSTEM, systemDark = true, sideLightsOn = false))
        assertFalse(resolveNightMode(NightModePreference.SYSTEM, systemDark = false, sideLightsOn = true))

        assertTrue(resolveNightMode(NightModePreference.DARK, systemDark = false, sideLightsOn = false))
        assertFalse(resolveNightMode(NightModePreference.LIGHT, systemDark = true, sideLightsOn = true))
    }
}

