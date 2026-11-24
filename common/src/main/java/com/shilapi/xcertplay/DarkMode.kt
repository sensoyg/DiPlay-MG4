package com.shilapi.xcertplay

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

internal enum class NightModePreference {
    AUTO,
    SYSTEM,
    DARK,
    LIGHT,
}

internal fun isDarkMode(uiMode: Int): Boolean =
    uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/**
 * On SAIC SWI68/DBI68/SWI133 units the launcher updates [UiModeManager.getNightMode] without
 * always propagating the change into [Configuration.uiMode]. Check UiModeManager first.
 */
internal fun isSystemDarkMode(context: Context, configurationUiMode: Int): Boolean {
    val ummNight = runCatching {
        context.getSystemService(UiModeManager::class.java)?.nightMode
    }.getOrNull()
    return when (ummNight) {
        UiModeManager.MODE_NIGHT_YES -> true
        UiModeManager.MODE_NIGHT_NO -> false
        else -> isDarkMode(configurationUiMode)
    }
}

internal fun resolveNightMode(
    preference: NightModePreference,
    systemDark: Boolean,
    sideLightsOn: Boolean?,
): Boolean = when (preference) {
    NightModePreference.DARK -> true
    NightModePreference.LIGHT -> false
    NightModePreference.SYSTEM -> systemDark
    NightModePreference.AUTO -> sideLightsOn ?: systemDark
}
