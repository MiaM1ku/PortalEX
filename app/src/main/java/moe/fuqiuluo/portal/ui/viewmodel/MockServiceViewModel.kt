package moe.fuqiuluo.portal.ui.viewmodel

import android.app.Activity
import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.ViewModel
import com.tencent.bugly.crashreport.CrashReport
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.fuqiuluo.portal.android.coro.CoroutineController
import moe.fuqiuluo.portal.android.coro.CoroutineRouteMock
import moe.fuqiuluo.portal.ext.Loc4j
import moe.fuqiuluo.portal.ext.accuracy
import moe.fuqiuluo.portal.ext.altitude
import moe.fuqiuluo.portal.ext.reportDuration
import moe.fuqiuluo.portal.ext.speed
import moe.fuqiuluo.portal.service.MockServiceHelper
import moe.fuqiuluo.portal.ui.mock.HistoricalLocation
import moe.fuqiuluo.portal.ui.mock.HistoricalRoute
import moe.fuqiuluo.portal.ui.mock.Rocker
import moe.fuqiuluo.xposed.utils.FakeLoc
import net.sf.geographiclib.Geodesic
import kotlin.math.abs

class MockServiceViewModel : ViewModel() {
    lateinit var rocker: Rocker
    private lateinit var rockerJob: Job
    private lateinit var routeMockJob: Job
    var isRockerLocked = false
    var routeStage = 0
    val rockerCoroutineController = CoroutineController()
    val routeMockCoroutine = CoroutineRouteMock()

    var isRouteStart = false
    var isRouteLoopEnabled = false

    var locationManager: LocationManager? = null
        set(value) {
            field = value
            if (value != null)
                MockServiceHelper.tryInitService(value)
        }

    var selectedLocation: HistoricalLocation? = null
    var selectedRoute: HistoricalRoute? = null

    // ---------- 速度衰减相关 ----------
    private var totalDistanceMoved = 0.0          // 累计移动距离（米）
    private val decayDistanceThreshold = 14000.0 // 衰减阈值（米），对应步频模拟的20000步（平均步幅0.7m）
    private val minSpeedFactor = 130.0 / 190.0   // 最小速度因子 ≈ 0.6842

    /**
     * 根据累计移动距离计算当前速度衰减因子（线性衰减）
     * 距离从 0 → decayDistanceThreshold，因子从 1.0 → minSpeedFactor
     */
    private fun getCurrentSpeedFactor(): Double {
        val progress = (totalDistanceMoved / decayDistanceThreshold).coerceIn(0.0, 1.0)
        return 1.0 - (1.0 - minSpeedFactor) * progress
    }

    /**
     * 重置累计距离（例如重新开始路线模拟时调用）
     */
    private fun resetDistanceAccumulator() {
        totalDistanceMoved = 0.0
        Log.d("MockServiceViewModel", "速度衰减累计距离已重置")
    }
    // ---------------------------------

    fun initRocker(activity: Activity): Rocker {
        if (!::rocker.isInitialized) {
            rocker = Rocker(activity)
        }

        if (!::rockerJob.isInitialized || rockerJob.isCancelled) {
            rockerCoroutineController.pause()
            val delayTime = activity.reportDuration.toLong()
            val applicationContext = activity.applicationContext
            rockerJob = GlobalScope.launch {
                do {
                    rockerCoroutineController.controlledCoroutine()
                    delay(delayTime)

                    CrashReport.setUserSceneTag(applicationContext, 261773)
                    // 修复：移除 /0.85，使用正确的速度计算公式
                    // 移动距离 = 速度(米/秒) × 时间(秒)
                    val moveDistance = FakeLoc.speed * (delayTime / 1000.0)
                    if(!MockServiceHelper.move(locationManager!!, moveDistance, FakeLoc.bearing)) {
                        Log.e("MockServiceViewModel", "Failed to move")
                    }
                    if (FakeLoc.enableDebugLog) {
                        Log.d("MockServiceViewModel", "摇杆移动: 速度=${FakeLoc.speed}m/s, 间隔=${delayTime}ms, 距离=${moveDistance}m")
                    }
                } while (isActive)
            }
        }

        FakeLoc.speed = activity.speed
        FakeLoc.altitude = activity.altitude
        FakeLoc.accuracy = activity.accuracy

        if (!::routeMockJob.isInitialized || routeMockJob.isCancelled) {
            routeMockCoroutine.pause()
            val delayTime = activity.reportDuration.toLong()
            // 每次启动路线模拟时重置累计距离（保证衰减从头开始）
            resetDistanceAccumulator()

            routeMockJob = GlobalScope.launch {
                do {
                    routeMockCoroutine.routeMockCoroutine()
                    delay(delayTime)
                    val route = selectedRoute?.route
                    if (route.isNullOrEmpty()) {
                        routeMockCoroutine.pause()
                        rocker.autoStatus = false
                        routeStage = 0
                        continue
                    }

                    // 如果是第0阶段，定位到第一个点
                    if (routeStage == 0) {
                        MockServiceHelper.setLocation(
                            locationManager!!,
                            route[0].first,
                            route[0].second
                        )
                        resetDistanceAccumulator()
                        routeStage++
                    }

                    // 处理所有已到达的阶段
                    while (routeStage < route.size) {
                        val target = route[routeStage]
                        val location = MockServiceHelper.getLocation(locationManager!!)
                        val currentLat = location!!.first
                        val currentLon = location.second

                        val inverse = Geodesic.WGS84.Inverse(
                            currentLat,
                            currentLon,
                            target.first,
                            target.second
                        )
                        // 判断距离是否小于1米（可根据需要调整阈值）
                        if (inverse.s12 < 1.0) {
                            // 精确设置位置到目标点并进入下一阶段
                            MockServiceHelper.setLocation(
                                locationManager!!,
                                target.first,
                                target.second
                            )
                            routeStage++
                        } else {
                            // 修复：使用正确的单步移动距离计算
                            val stepMoveDistance = FakeLoc.speed * getCurrentSpeedFactor() * (delayTime / 1000.0)
                            if (inverse.s12 < stepMoveDistance) {
                                // 如果距离小于当前衰减后的单步移动距离，直接移动到目标点
                                MockServiceHelper.setLocation(
                                    locationManager!!,
                                    target.first,
                                    target.second
                                )
                                routeStage++
                            } else {
                                break
                            }
                        }
                    }

                    // 检查是否已完成所有阶段
                    if (routeStage >= route.size) {
                        // 重设阶段
                        routeStage = 0
                        resetDistanceAccumulator()
                        if (isRouteLoopEnabled) {
                            continue
                        } else {
                            routeMockCoroutine.pause()
                            rocker.autoStatus = false
                            continue
                        }
                    }

                    // 处理当前目标点的移动
                    val target = route[routeStage]
                    val location = MockServiceHelper.getLocation(locationManager!!)
                    val currentLat = location!!.first
                    val currentLon = location.second

                    val inverse = Geodesic.WGS84.Inverse(
                        currentLat,
                        currentLon,
                        target.first,
                        target.second
                    )
                    var azimuth = inverse.azi1
                    if (azimuth < 0) {
                        azimuth += 360
                    }

                    // 修复：计算实际移动距离（速度衰减）
                    val decayedSpeed = FakeLoc.speed * getCurrentSpeedFactor()
                    val moveDistance = decayedSpeed * (delayTime / 1000.0)
                    
                    // 累加实际移动的距离（与实际移动保持一致）
                    totalDistanceMoved += moveDistance
                    
                    if (FakeLoc.enableDebugLog) {
                        Log.d("MockServiceViewModel", """
                            路径移动: 速度=${FakeLoc.speed}m/s, 速度因子=${String.format("%.3f", getCurrentSpeedFactor())}
                            间隔=${delayTime}ms, 移动距离=${String.format("%.3f", moveDistance)}m
                            累计距离=${String.format("%.2f", totalDistanceMoved)}m, 剩余距离=${String.format("%.2f", inverse.s12)}m
                            从 (${String.format("%.6f", currentLat)}, ${String.format("%.6f", currentLon)})
                            到 (${String.format("%.6f", target.first)}, ${String.format("%.6f", target.second)}), 方位角: ${String.format("%.2f", azimuth)}°
                        """.trimIndent())
                    }
                    
                    // 使用修正后的移动距离
                    if (!MockServiceHelper.move(
                            locationManager!!,
                            moveDistance,
                            azimuth
                        )
                    ) {
                        Log.e("MockServiceViewModel", "移动失败")
                    }
                } while (isActive)
            }
        }

        return rocker
    }

    fun isServiceStart(): Boolean {
        return locationManager != null && MockServiceHelper.isServiceInit() && MockServiceHelper.isMockStart(
            locationManager!!
        )
    }
}
