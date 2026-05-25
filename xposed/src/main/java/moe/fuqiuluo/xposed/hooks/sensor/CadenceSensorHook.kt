@file:Suppress("PrivateApi")

package moe.fuqiuluo.xposed.hooks.sensor

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import java.lang.reflect.Method
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.random.Random

object CadenceSensorHook {
    const val ACTION_TOGGLE_CADENCE = "moe.fuqiuluo.portal.action.TOGGLE_CADENCE"
    const val ACTION_REQUEST_STATE = "moe.fuqiuluo.portal.action.REQUEST_CADENCE_STATE"
    const val EXTRA_STATE = "STATE"
    const val EXTRA_MIN_CADENCE = "MIN_CADENCE"
    const val EXTRA_MAX_CADENCE = "MAX_CADENCE"
    const val EXTRA_SILENT = "SILENT"

    private const val MODULE_PACKAGE = "moe.fuqiuluo.portal"
    private const val DEFAULT_MIN_CADENCE = 172f
    private const val DEFAULT_MAX_CADENCE = 182f

    @Volatile private var isModifyCadence = false
    @Volatile private var mockStartTime = 0L
    @Volatile private var baseStepOffset = -1f
    @Volatile private var minCadenceConfig = DEFAULT_MIN_CADENCE
    @Volatile private var maxCadenceConfig = DEFAULT_MAX_CADENCE
    @Volatile private var currentCadence = 176f

    private var lastLoggedSec = -1

    private val excludedPackages = setOf(
        MODULE_PACKAGE,
        "com.uy_li.runhook",
        "com.yijin.joyrunhook",
        "android",
        "com.android.phone",
        "com.android.systemui",
        "com.android.launcher",
        "com.android.settings",
        "com.android.packageinstaller",
        "org.lsposed.manager"
    )
    private val registeredApplications = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val hookedManagerMethods = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val hookedListenerClasses = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun shouldHookPackage(packageName: String): Boolean {
        return packageName !in excludedPackages
    }

    operator fun invoke(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName ?: return
        if (!shouldHookPackage(packageName)) return

        Logger.info("CadenceSensorHook loaded for $packageName")
        hookApplicationReceiver(packageName)
        hookSensorManagers(lpparam.classLoader, packageName)
    }

    private fun hookApplicationReceiver(packageName: String) {
        runCatching {
            XposedHelpers.findAndHookMethod(Application::class.java, "onCreate", object : XC_MethodHook() {
                @SuppressLint("UnspecifiedRegisterReceiverFlag")
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as? Application ?: return
                    val key = "$packageName@${System.identityHashCode(app)}"
                    if (!registeredApplications.add(key)) return

                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            if (intent.action != ACTION_TOGGLE_CADENCE) return

                            val state = intent.getBooleanExtra(EXTRA_STATE, false)
                            val minCadence = intent.getFloatExtra(EXTRA_MIN_CADENCE, DEFAULT_MIN_CADENCE)
                            val maxCadence = intent.getFloatExtra(EXTRA_MAX_CADENCE, DEFAULT_MAX_CADENCE)
                            val silent = intent.getBooleanExtra(EXTRA_SILENT, false)
                            applyCadenceConfig(context, packageName, state, minCadence, maxCadence, silent)
                        }
                    }

                    val filter = IntentFilter(ACTION_TOGGLE_CADENCE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                    } else {
                        app.registerReceiver(receiver, filter)
                    }

                    app.sendBroadcast(Intent(ACTION_REQUEST_STATE).setPackage(MODULE_PACKAGE))
                }
            })
        }.onFailure {
            Logger.warn("CadenceSensorHook: failed to hook Application.onCreate for $packageName", it)
        }
    }

    private fun applyCadenceConfig(
        context: Context,
        packageName: String,
        enabled: Boolean,
        rawMinCadence: Float,
        rawMaxCadence: Float,
        silent: Boolean
    ) {
        val minCadence = rawMinCadence.coerceIn(140f, 220f)
        val maxCadence = rawMaxCadence.coerceIn(140f, 220f)
        val normalizedMin = kotlin.math.min(minCadence, maxCadence)
        val normalizedMax = kotlin.math.max(minCadence, maxCadence)
        val changed = isModifyCadence != enabled ||
                minCadenceConfig != normalizedMin ||
                maxCadenceConfig != normalizedMax

        minCadenceConfig = normalizedMin
        maxCadenceConfig = normalizedMax
        isModifyCadence = enabled

        if (enabled && changed) {
            resetMockState()
        }

        if (!silent) {
            val message = if (enabled) {
                "步频模拟已开启 (${normalizedMin.toInt()}-${normalizedMax.toInt()})"
            } else {
                "步频模拟已关闭"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        Logger.info("CadenceSensorHook: $packageName state=$enabled range=${normalizedMin.toInt()}-${normalizedMax.toInt()}")
    }

    private fun hookSensorManagers(classLoader: ClassLoader, packageName: String) {
        hookRegisterListenerMethods(SensorManager::class.java, packageName)

        runCatching {
            XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager", classLoader)
                ?.let { hookRegisterListenerMethods(it, packageName) }
        }.onFailure {
            Logger.warn("CadenceSensorHook: SystemSensorManager hook skipped for $packageName", it)
        }

        runCatching {
            XposedHelpers.findClassIfExists("com.oplus.sensor.OplusSensorManager", classLoader)
                ?.let {
                    hookRegisterListenerMethods(it, packageName)
                    Logger.info("CadenceSensorHook: OplusSensorManager found in $packageName")
                }
        }.onFailure {
            Logger.warn("CadenceSensorHook: OplusSensorManager hook skipped for $packageName", it)
        }
    }

    private fun hookRegisterListenerMethods(managerClass: Class<*>, packageName: String) {
        managerClass.declaredMethods
            .filter { it.name == "registerListener" || it.name == "registerListenerImpl" }
            .forEach { method ->
                hookRegisterListenerMethod(method, managerClass, packageName)
            }
    }

    private fun hookRegisterListenerMethod(method: Method, managerClass: Class<*>, packageName: String) {
        val key = "${managerClass.name}#${method.toGenericString()}"
        if (!hookedManagerMethods.add(key)) return

        runCatching {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val listener = param.args.firstOrNull { it is SensorEventListener } as? SensorEventListener
                        ?: return
                    hookSensorEventListener(listener, packageName)
                }
            })
            if (FakeLoc.enableDebugLog) {
                Logger.debug("CadenceSensorHook: hooked ${method.name} on ${managerClass.name} for $packageName")
            }
        }.onFailure {
            Logger.warn("CadenceSensorHook: failed to hook ${managerClass.name}.${method.name}", it)
        }
    }

    private fun hookSensorEventListener(listener: SensorEventListener, packageName: String) {
        val listenerClass = listener.javaClass
        val key = "${listenerClass.classLoader}@${listenerClass.name}"
        if (!hookedListenerClasses.add(key)) return

        runCatching {
            XposedHelpers.findAndHookMethod(
                listenerClass,
                "onSensorChanged",
                SensorEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val event = param.args.firstOrNull() as? SensorEvent ?: return
                        rewriteSensorEvent(event, packageName)
                    }
                }
            )
            Logger.info("CadenceSensorHook: locked onSensorChanged for ${listenerClass.name}")
        }.onFailure {
            Logger.warn("CadenceSensorHook: failed to hook ${listenerClass.name}.onSensorChanged", it)
        }
    }

    private fun rewriteSensorEvent(event: SensorEvent, packageName: String) {
        if (!isModifyCadence) return

        val sensor = event.sensor ?: return
        val values = event.values ?: return
        val type = sensor.type
        if (type != Sensor.TYPE_ACCELEROMETER &&
            type != Sensor.TYPE_STEP_COUNTER &&
            type != Sensor.TYPE_STEP_DETECTOR
        ) {
            return
        }

        val now = System.currentTimeMillis()
        if (mockStartTime == 0L) {
            resetMockState(now)
        }

        val elapsedSecs = (now - mockStartTime) / 1000f
        val freq = currentCadence / 60f

        when (type) {
            Sensor.TYPE_ACCELEROMETER -> {
                if (values.size < 3) return

                val phase = elapsedSecs.toDouble() * PI * 2.0 * freq.toDouble()
                val zWave = (9.81 + kotlin.math.sin(phase) * 3.5).toFloat()
                val yWave = (kotlin.math.cos(phase) * 1.5).toFloat()
                val jitterY = Random.nextFloat() * 0.3f - 0.15f
                val jitterZ = Random.nextFloat() * 0.3f - 0.15f

                values[0] = 0f
                values[1] = yWave + jitterY
                values[2] = zWave + jitterZ

                val elapsedSecond = elapsedSecs.toInt()
                if (FakeLoc.enableDebugLog && elapsedSecond != lastLoggedSec) {
                    lastLoggedSec = elapsedSecond
                    Logger.debug(
                        "CadenceSensorHook: $packageName accelerometer t=${elapsedSecond}s " +
                                "z=${String.format(Locale.US, "%.2f", zWave)} cadence=${currentCadence.toInt()}"
                    )
                }
            }
            Sensor.TYPE_STEP_COUNTER -> {
                if (values.isEmpty()) return
                if (baseStepOffset < 0f) {
                    baseStepOffset = values[0]
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("CadenceSensorHook: $packageName baseStepOffset=$baseStepOffset")
                    }
                }
                values[0] = baseStepOffset + elapsedSecs * freq
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                if (values.isEmpty()) return
                values[0] = 1f
            }
        }

        event.timestamp = SystemClock.elapsedRealtimeNanos()
    }

    private fun resetMockState(now: Long = System.currentTimeMillis()) {
        mockStartTime = now
        baseStepOffset = -1f
        lastLoggedSec = -1
        currentCadence = randomCadence()
    }

    private fun randomCadence(): Float {
        val min = kotlin.math.min(minCadenceConfig, maxCadenceConfig)
        val max = kotlin.math.max(minCadenceConfig, maxCadenceConfig)
        if (max <= min) return min
        return min + Random.nextFloat() * (max - min)
    }
}
