package com.evsuite.hardware.saic

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.evsuite.hardware.FirmwareInfo
import java.lang.reflect.Proxy
import kotlin.math.abs

/**
 * Reflection-based read-only bridge to SAIC Android Automotive services (`CarPropertyManager`,
 * `VehicleConditionManager`, `CarStateClient`, `VehiclePropertyManager`).
 */
internal object SaicCarBridge {
    private const val TAG = "EVHardware-SAIC"
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
    private const val PROP_BATTERY_CAPACITY = 0x11600106
    private const val PROP_ODOMETER = 0x21401566
    private const val PROP_VEH_SIDE_LGHT = 0x21409323
    private const val PROP_GEAR_VPM_133 = 0x5030043

    private const val SENTINEL_MEASURE = 82.3f
    private const val SENTINEL_HALF = 511.5f
    private const val SENTINEL_INT = 1023
    private val CHARGING_STATES = setOf(1, 10, 11, 12)

    @Volatile private var appContext: Context? = null
    @Volatile private var car: Any? = null
    @Volatile private var carPropertyManager: Any? = null
    @Volatile private var carConnectAttempted = false
    @Volatile private var vcm: Any? = null
    @Volatile private var vcmAttempted = false
    @Volatile private var carStateClient: Any? = null
    @Volatile private var vpm133: Any? = null

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        ensureCar(app)
        ensureGearSource(app)
    }

    fun isCarConnected(): Boolean = carPropertyManager != null

    fun readSocPercent(): Float? =
        readFloat(PROP_SOC)?.takeIf { it in 0f..100f }

    fun readRangeKm(): Float? =
        readInt(PROP_RANGE)?.takeIf { it in 1..1500 }?.toFloat()

    /** SAIC VHAL reports speed directly in km/h (signed in reverse). */
    fun readSpeedKmh(): Float? {
        val raw = readFloat(PROP_VEHICLE_SPEED) ?: readFloat(PROP_VEHICLE_SPEED, 0) ?: return null
        return abs(raw).takeIf { it in 0f..300f }
    }

    fun readBatteryPowerKw(): Float? {
        val volts = readFloat(PROP_PACK_VOLTAGE)?.takeIf { it in 200f..500f } ?: return null
        val amps = readFloat(PROP_PACK_CURRENT)?.takeIf { it in -1000f..2276.75f } ?: return null
        return volts * amps / 1000f
    }

    fun readBatteryCapacityKwh(): Float? {
        val raw = readFloat(PROP_BATTERY_CAPACITY) ?: readFloat(PROP_BATTERY_CAPACITY, 0) ?: return null
        val kwh = if (raw > 1000f) raw / 1000f else raw
        return kwh.takeIf { it in 20f..150f }
    }

    fun readOdometerKm(): Float? =
        readInt(PROP_ODOMETER)?.takeIf { it > 0 }?.toFloat()

    fun isCharging(): Boolean? =
        readInt(PROP_CHARGE_STATUS)?.let { it in CHARGING_STATES }

    fun isChargePortConnected(): Boolean? {
        val ac = readInt(PROP_PLUG_AC)
        val dc = readInt(PROP_PLUG_DC)
        if (ac == null && dc == null) return null
        val dcPlugged = dc != null && dc != 0 && dc != SENTINEL_INT
        val acPlugged = ac != null && ac != 0 && ac != SENTINEL_INT
        return dcPlugged || acPlugged
    }

    /** `VEH_SIDE_LGHT == 3` indicates sidelights / night illumination active on MG4. */
    fun isSideLightOn(): Boolean? =
        readInt(PROP_VEH_SIDE_LGHT)?.let { it == 3 }

    fun readGearOrNull(): Int? {
        val ctx = appContext
        if (ctx != null) ensureGearSource(ctx)
        val raw = when (FirmwareInfo.getGeneration()) {
            FirmwareInfo.Gen.SWI68, FirmwareInfo.Gen.SWI165 -> readVcmGear()
            FirmwareInfo.Gen.SWI69, FirmwareInfo.Gen.SWI131, FirmwareInfo.Gen.SWI132 -> readA9Gear()
            FirmwareInfo.Gen.SWI133 -> readVpm133Gear()
            FirmwareInfo.Gen.UNKNOWN -> readVcmGear() ?: readA9Gear()
        }
        return raw?.takeIf { it in 1..4 }
    }

    @SuppressLint("PrivateApi")
    @Synchronized
    private fun ensureCar(context: Context) {
        if (carPropertyManager != null) return
        if (carConnectAttempted && car != null) {
            tryFetchPropertyManager()
            return
        }
        carConnectAttempted = true
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
            Log.w(TAG, "android.car.Car unavailable: ${it.javaClass.simpleName}")
        }
    }

    @SuppressLint("PrivateApi")
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

    private fun ensureGearSource(context: Context) {
        when (FirmwareInfo.getGeneration()) {
            FirmwareInfo.Gen.SWI68, FirmwareInfo.Gen.SWI165 -> ensureVcm(context)
            FirmwareInfo.Gen.SWI69, FirmwareInfo.Gen.SWI131, FirmwareInfo.Gen.SWI132 -> ensureA9CarState(context)
            FirmwareInfo.Gen.SWI133 -> ensureVpm133(context)
            FirmwareInfo.Gen.UNKNOWN -> ensureVcm(context)
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
            Log.w(TAG, "VehicleConditionManager unavailable: ${it.javaClass.simpleName}")
        }
    }

    @Synchronized
    private fun ensureA9CarState(context: Context) {
        if (carStateClient != null) return
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
        if (vpm133 != null) return
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
        if (carPropertyManager == null) appContext?.let(::ensureCar)
        val cpm = carPropertyManager ?: return null
        val value = runCatching {
            cpm.javaClass.getMethod("getFloatProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(cpm, propId, areaId) as? Float
        }.getOrNull() ?: return null
        if (!value.isFinite() || value == SENTINEL_MEASURE || value == SENTINEL_HALF) return null
        return value
    }

    private fun readInt(propId: Int, areaId: Int = AREA_GLOBAL): Int? {
        if (carPropertyManager == null) appContext?.let(::ensureCar)
        val cpm = carPropertyManager ?: return null
        return runCatching {
            cpm.javaClass.getMethod("getIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(cpm, propId, areaId) as? Int
        }.getOrNull()
    }
}

