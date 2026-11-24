package com.shilapi.xcertplay.vehicle

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.transport.VehicleGear
import java.lang.reflect.Proxy
import kotlin.math.abs

/**
 * Direct read-only SAIC / MG4 signal bridge covering `DBI68` (`SWI68` distributor firmware) as
 * well as `SWI68`, `SWI165`, `SWI69`, `SWI131`, `SWI132`, `SWI133` and `SWI173`.
 */
object Mg4SaicSignals {
    private const val TAG = "DiPlay-MG4-SAIC"
    private const val AREA_GLOBAL = 0x1000000
    private const val LAUNCHER68_PKG = "com.saicmotor.hmi.launcher"
    private const val LAUNCHER69_PKG = "com.saicmotor.launcher"

    private const val PROP_SOC = 0x2160f404
    private const val PROP_RANGE = 0x2140f41c
    private const val PROP_VEHICLE_SPEED = 0x11600207
    private const val PROP_PACK_VOLTAGE = 0x2160f406
    private const val PROP_PACK_CURRENT = 0x2160f407
    private const val PROP_CHARGE_STATUS = 0x2140f409
    private const val PROP_PLUG_AC = 0x2140f43e
    private const val PROP_PLUG_DC = 0x2140f43f
    private const val PROP_VEH_SIDE_LGHT = 0x21409323
    private const val PROP_GEAR_VPM_133 = 0x5030043

    private const val SENTINEL_MEASURE = 82.3f
    private const val SENTINEL_HALF = 511.5f
    private const val SENTINEL_INT = 1023
    private val CHARGING_STATES = setOf(1, 10, 11, 12)
    private val FIRMWARE_PREFIXES = listOf("SWI", "DBI")

    enum class Generation { SWI68, SWI165, SWI69, SWI131, SWI132, SWI133, UNKNOWN }

    data class BatteryFallback(
        val socPercent: Double,
        val rangeKm: Int,
        val charging: Boolean,
    )

    @Volatile private var appContext: Context? = null
    @Volatile private var car: Any? = null
    @Volatile private var carPropertyManager: Any? = null
    @Volatile private var carBindAttempted = false
    @Volatile private var vcm: Any? = null
    @Volatile private var vcmAttempted = false
    @Volatile private var carStateClient: Any? = null
    @Volatile private var carStateAttempted = false
    @Volatile private var vpm133: Any? = null
    @Volatile private var vpm133Attempted = false

    fun parseGeneration(version: String?): Generation {
        val normalized = version?.trim()?.uppercase() ?: return Generation.UNKNOWN
        val prefix = FIRMWARE_PREFIXES.firstOrNull { normalized.startsWith(it) } ?: return Generation.UNKNOWN
        val digits = normalized.substring(prefix.length).takeWhile { it.isDigit() }
        return when (digits) {
            "68" -> Generation.SWI68
            "165" -> Generation.SWI165
            "69" -> Generation.SWI69
            "131", "173" -> Generation.SWI131
            "132" -> Generation.SWI132
            "133" -> Generation.SWI133
            else -> Generation.UNKNOWN
        }
    }

    fun detectedMt2712Version(): String? = readSystemProperty("ro.build.mt2712.version")

    fun detectedIncrementalVersion(): String? = readSystemProperty("ro.build.version.incremental")

    fun detectedVersionString(): String? = detectedMt2712Version() ?: detectedIncrementalVersion()

    fun detectedGeneration(): Generation = parseGeneration(detectedVersionString())

    fun isSupportedMg4Firmware(): Boolean = detectedGeneration() != Generation.UNKNOWN

    fun isSwi68Family(): Boolean {
        val gen = detectedGeneration()
        return gen == Generation.SWI68 || gen == Generation.SWI165
    }

    fun gearFromRaw(raw: Int?): VehicleGear? = when (raw) {
        1 -> VehicleGear.PARK
        2 -> VehicleGear.REVERSE
        3 -> VehicleGear.NEUTRAL
        4 -> VehicleGear.DRIVE
        else -> null
    }

    fun speedMetersPerSecondFromKmh(kmh: Float?): Double? =
        kmh?.let { abs(it).toDouble() }?.takeIf { it in 0.0..300.0 }?.div(3.6)

    fun readSpeedKmh(context: Context): Float? {
        ensureCar(context.applicationContext)
        val raw = readFloat(PROP_VEHICLE_SPEED) ?: readFloat(PROP_VEHICLE_SPEED, 0) ?: return null
        return abs(raw).takeIf { it in 0f..300f }
    }

    fun readGearOrNull(context: Context): Int? {
        val app = context.applicationContext
        appContext = app
        val raw = when (detectedGeneration()) {
            Generation.SWI68, Generation.SWI165 -> {
                ensureVcm(app)
                readVcmGear()
            }
            Generation.SWI69, Generation.SWI131, Generation.SWI132 -> {
                ensureA9CarState(app)
                readA9Gear()
            }
            Generation.SWI133 -> {
                ensureVpm133(app)
                readVpm133Gear()
            }
            Generation.UNKNOWN -> {
                ensureVcm(app)
                readVcmGear() ?: run {
                    ensureA9CarState(app)
                    readA9Gear()
                }
            }
        }
        return raw?.takeIf { it in 1..4 }
    }

    /** `VEH_SIDE_LGHT == 3` indicates sidelights / headlights night state on MG4. */
    fun isSideLightOn(context: Context): Boolean? {
        ensureCar(context.applicationContext)
        val raw = readInt(PROP_VEH_SIDE_LGHT) ?: return null
        return if (raw < 0) null else raw == 3
    }

    fun readBatteryFallback(context: Context): BatteryFallback? {
        ensureCar(context.applicationContext)
        val soc = readFloat(PROP_SOC)?.takeIf { it in 0f..100f }?.toDouble() ?: return null
        val range = readInt(PROP_RANGE)?.takeIf { it in 1..1500 } ?: return null
        val chargeStatus = readInt(PROP_CHARGE_STATUS)?.let { it in CHARGING_STATES } ?: false
        val plugAc = readInt(PROP_PLUG_AC)?.let { it != 0 && it != SENTINEL_INT } ?: false
        val plugDc = readInt(PROP_PLUG_DC)?.let { it != 0 && it != SENTINEL_INT } ?: false
        val powerKw = readBatteryPowerKw()
        val charging = chargeStatus || plugAc || plugDc || (powerKw != null && powerKw < -0.3f)
        return BatteryFallback(socPercent = soc, rangeKm = range, charging = charging)
    }

    fun diagnosticsSummary(context: Context): String {
        val app = context.applicationContext
        ensureCar(app)
        val soc = readFloat(PROP_SOC)?.takeIf { it in 0f..100f }
        val range = readInt(PROP_RANGE)?.takeIf { it in 1..1500 }
        val gear = readGearOrNull(app)
        val speed = readSpeedKmh(app)
        val sideLight = isSideLightOn(app)
        return buildString {
            appendLine("mt2712.version=${detectedMt2712Version() ?: "unset"} incremental=${detectedIncrementalVersion() ?: "unset"}")
            appendLine("detectedGen=${detectedGeneration()} swi68Family=${isSwi68Family()}")
            appendLine("carPropertyManager=${carPropertyManager != null} vcm=${vcm != null} carState=${carStateClient != null}")
            append("soc=${soc ?: "unavailable"} rangeKm=${range ?: "unavailable"} gear=${gear ?: "unavailable"} ")
            append("speedKmh=${speed ?: "unavailable"} sideLightOn=${sideLight ?: "unavailable"}")
        }
    }

    private fun readBatteryPowerKw(): Float? {
        val volts = readFloat(PROP_PACK_VOLTAGE)?.takeIf { it in 200f..500f } ?: return null
        val amps = readFloat(PROP_PACK_CURRENT)?.takeIf { it in -1000f..2276.75f } ?: return null
        return volts * amps / 1000f
    }

    @SuppressLint("PrivateApi")
    @Synchronized
    private fun ensureCar(context: Context) {
        appContext = context
        if (carPropertyManager != null) return
        if (carBindAttempted && car != null) {
            tryFetchPropertyManager()
            return
        }
        carBindAttempted = true
        runCatching {
            val carClass = Class.forName("android.car.Car")
            val created = runCatching {
                carClass.getMethod("createCar", Context::class.java).invoke(null, context)
            }.getOrNull() ?: runCatching {
                carClass.getMethod("createCar", Context::class.java, Handler::class.java)
                    .invoke(null, context, Handler(Looper.getMainLooper()))
            }.getOrNull() ?: runCatching {
                val sc = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        tryFetchPropertyManager()
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {
                        carPropertyManager = null
                    }
                }
                carClass.getMethod("createCar", Context::class.java, ServiceConnection::class.java)
                    .invoke(null, context, sc)
            }.getOrNull()

            if (created != null) {
                car = created
                runCatching { carClass.getMethod("connect").invoke(created) }
                tryFetchPropertyManager()
            }
        }.onFailure {
            Log.d(TAG, "android.car.Car unavailable: ${it.javaClass.simpleName}")
        }
    }

    private fun tryFetchPropertyManager() {
        val currentCar = car ?: return
        if (carPropertyManager != null) return
        runCatching {
            val carClass = currentCar.javaClass
            val connected = runCatching {
                carClass.getMethod("isConnected").invoke(currentCar) as? Boolean
            }.getOrNull() ?: true
            if (!connected) return
            val propService = carClass.getField("PROPERTY_SERVICE").get(null) as String
            carPropertyManager = carClass.getMethod("getCarManager", String::class.java)
                .invoke(currentCar, propService)
        }
    }

    @Synchronized
    private fun ensureVcm(context: Context) {
        if (vcm != null || vcmAttempted) return
        vcmAttempted = true
        runCatching {
            val launcherCtx = context.createPackageContext(
                LAUNCHER68_PKG,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            )
            val cls = launcherCtx.classLoader.loadClass("com.saicmotor.sdk.vehiclesettings.manager.VehicleConditionManager")
            val field = runCatching { cls.getDeclaredField("sVehicleConditionManager").apply { isAccessible = true } }.getOrNull()
            field?.get(null)?.let {
                vcm = it
                return
            }
            val initMethod = cls.methods.firstOrNull {
                it.name == "init" && it.parameterCount == 2 && Context::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            if (initMethod != null) {
                val listenerType = initMethod.parameterTypes[1]
                val listener = if (listenerType.isInterface) {
                    Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { _, method, _ ->
                        if (method.name == "onServiceConnected") {
                            runCatching { field?.get(null)?.let { vcm = it } }
                        }
                        null
                    }
                } else null
                initMethod.invoke(null, context, listener)
                field?.get(null)?.let { vcm = it }
            }
            if (vcm == null) {
                vcm = runCatching { cls.getMethod("getInstance", Context::class.java).invoke(null, context) }.getOrNull()
                    ?: runCatching { cls.getMethod("getInstance").invoke(null) }.getOrNull()
            }
        }.onFailure {
            Log.d(TAG, "VehicleConditionManager unavailable: ${it.javaClass.simpleName}")
        }
    }

    @Synchronized
    private fun ensureA9CarState(context: Context) {
        if (carStateClient != null || carStateAttempted) return
        carStateAttempted = true
        runCatching {
            val launcherCtx = context.createPackageContext(
                LAUNCHER69_PKG,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            )
            val cl = launcherCtx.classLoader
            val adapterCls = cl.loadClass("com.saicmotor.carapi.CarAdapterClient")
            val adapter = adapterCls.getMethod("getInstance", Context::class.java).invoke(null, context)
            val binder = adapterCls.getMethod("queryClient", Int::class.javaPrimitiveType).invoke(adapter, 0xb) as? IBinder
                ?: return
            val stateCls = cl.loadClass("com.saicmotor.carapi.client.CarStateClient")
            carStateClient = stateCls.getConstructor(IBinder::class.java).newInstance(binder)
        }
    }

    @Synchronized
    private fun ensureVpm133(context: Context) {
        if (vpm133 != null || vpm133Attempted) return
        vpm133Attempted = true
        runCatching {
            val launcherCtx = context.createPackageContext(
                LAUNCHER68_PKG,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            )
            val cls = launcherCtx.classLoader.loadClass("com.saicmotor.sdk.vehiclesettings.manager.VehiclePropertyManager")
            val instance = runCatching { cls.getConstructor(Context::class.java).newInstance(launcherCtx) }.getOrNull()
                ?: runCatching { cls.getMethod("getInstance", Context::class.java).invoke(null, launcherCtx) }.getOrNull()
                ?: return
            runCatching { cls.getMethod("init", Context::class.java).invoke(instance, context) }
            runCatching { cls.getMethod("bindService").invoke(instance) }
            vpm133 = instance
        }
    }

    private fun readVcmGear(): Int? = runCatching {
        val manager = vcm ?: return null
        manager.javaClass.getMethod("getCarGear").invoke(manager) as? Int
    }.getOrNull()

    private fun readA9Gear(): Int? = runCatching {
        val client = carStateClient ?: return null
        client.javaClass.getMethod("getGearState").invoke(client) as? Int
    }.getOrNull()

    private fun readVpm133Gear(): Int? = runCatching {
        val manager = vpm133 ?: return null
        (manager.javaClass.getMethod("getMixProperty", Class::class.java, Int::class.java)
            .invoke(manager, Int::class.javaObjectType, PROP_GEAR_VPM_133) as? Number)?.toInt()
    }.getOrNull()

    private fun readFloat(propId: Int, areaId: Int = AREA_GLOBAL): Float? {
        val cpm = carPropertyManager ?: return null
        val value = runCatching {
            cpm.javaClass.getMethod("getFloatProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(cpm, propId, areaId) as? Float
        }.getOrNull() ?: return null
        if (!value.isFinite() || value == SENTINEL_MEASURE || value == SENTINEL_HALF) return null
        return value
    }

    private fun readInt(propId: Int, areaId: Int = AREA_GLOBAL): Int? {
        val cpm = carPropertyManager ?: return null
        return runCatching {
            cpm.javaClass.getMethod("getIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(cpm, propId, areaId) as? Int
        }.getOrNull()
    }

    @SuppressLint("PrivateApi")
    private fun readSystemProperty(key: String): String? = runCatching {
        val sp = Class.forName("android.os.SystemProperties")
        val get = sp.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, key, "") as? String)?.takeIf { it.isNotBlank() && it != "0" }
    }.getOrNull()
}

