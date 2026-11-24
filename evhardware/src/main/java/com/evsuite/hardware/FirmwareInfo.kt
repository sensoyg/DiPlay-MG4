package com.evsuite.hardware

import android.annotation.SuppressLint

/**
 * Detects MG4 SAIC firmware generations from `ro.build.mt2712.version` (falling back to
 * `ro.build.version.incremental`), covering both SAIC `SWI*` and distributor `DBI*` (e.g. Turkish
 * `DBI68`, which uses the `SWI68` SDK stack).
 */
object FirmwareInfo {
    enum class Gen { SWI68, SWI165, SWI69, SWI131, SWI132, SWI133, UNKNOWN }

    private val PREFIXES = listOf("SWI", "DBI")

    @Volatile private var cachedGen: Gen? = null
    @Volatile private var cachedRaw: String? = null

    fun parseGeneration(version: String?): Gen {
        val normalized = version?.trim()?.uppercase() ?: return Gen.UNKNOWN
        val prefix = PREFIXES.firstOrNull { normalized.startsWith(it) } ?: return Gen.UNKNOWN
        val digits = normalized.substring(prefix.length).takeWhile { it.isDigit() }
        return when (digits) {
            "68" -> Gen.SWI68
            "165" -> Gen.SWI165
            "69" -> Gen.SWI69
            "131", "173" -> Gen.SWI131
            "132" -> Gen.SWI132
            "133" -> Gen.SWI133
            else -> Gen.UNKNOWN
        }
    }

    fun getGeneration(): Gen {
        cachedGen?.let { return it }
        val raw = readSystemProperty("ro.build.mt2712.version")
            ?: readSystemProperty("ro.build.version.incremental")
        cachedRaw = raw
        return parseGeneration(raw).also { cachedGen = it }
    }

    fun isDetectedGenerationSupported(): Boolean = getGeneration() != Gen.UNKNOWN

    fun getDetectedString(): String {
        if (cachedRaw == null && cachedGen == null) getGeneration()
        return cachedRaw ?: "?"
    }

    fun isSwi68Family(): Boolean {
        val gen = getGeneration()
        return gen == Gen.SWI68 || gen == Gen.SWI165
    }

    fun isA9Family(): Boolean {
        val gen = getGeneration()
        return gen == Gen.SWI69 || gen == Gen.SWI131 || gen == Gen.SWI132
    }

    @SuppressLint("PrivateApi")
    private fun readSystemProperty(key: String): String? = runCatching {
        val sysProps = Class.forName("android.os.SystemProperties")
        val get = sysProps.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, key, "") as? String)?.takeIf { it.isNotBlank() && it != "0" }
    }.getOrNull()
}

