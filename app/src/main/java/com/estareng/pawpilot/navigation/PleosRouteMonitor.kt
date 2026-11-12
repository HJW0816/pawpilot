package com.estareng.pawpilot.navigation

import ai.pleos.playground.navi.constants.*
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Reads the user's native navigation route. No route is set as a side effect of connecting. */
class PleosRouteMonitor(private val context: Context, private val changed: (RouteContext) -> Unit, private val allowMutation: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private var helper: NaviHelper? = null
    private var listener: NaviHelperEventListener? = null
    private var generation = 0
    var state = RouteContext()
        private set
    private var pendingStop: NaviPlace? = null
    private var pendingRouteKey: String? = null
    private var pendingDestination: String? = null
    private var writeSent = false
    private var freshDestination = false
    private var freshWaypoints = false
    private var requestSerial = 0
    var savedPlaces: List<NaviPlace> = emptyList(); private set
    var libraryTitle = ""; private set
    var libraryMessage = ""; private set
    private var libraryPending = false
    private var libraryRecent = false
    private var librarySerial = 0
    var locationText = ""; private set
    var chargerText = ""; private set
    var controlMessage = ""; private set
    var controlBusy = false; private set
    private var commandSerial = 0
    private var expectedPlan: NativeRoutePlan? = null
    private var expectedOption: RouteOption? = null
    private var expectedCancel = false
    private var expectedReroute = false
    private var controlDestinationFresh = false
    private var controlWaypointsFresh = false
    private var preparedKey: String? = null
    private var preparedAction: (() -> Unit)? = null
    private var readSerial = 0
    private fun extrasChanged() = changed(state)
    private fun completeControl(message: String) {
        expectedPlan = null; expectedOption = null; expectedCancel = false; expectedReroute = false
        preparedKey = null; preparedAction = null; controlBusy = false; controlMessage = message
        extrasChanged()
    }
    private fun observeControl() {
        if (!controlDestinationFresh || !controlWaypointsFresh) return
        val action = preparedAction
        if (action != null) {
            preparedAction = null
            if (!allowMutation() || state.key != preparedKey) { completeControl("경로가 바뀌어 요청하지 않았어요. 다시 선택해 주세요."); return }
            preparedKey = null; controlDestinationFresh = false; controlWaypointsFresh = false
            request(action)
        } else if (expectedPlan?.matches(state) == true) completeControl("목적지와 경유지 반영을 확인했어요.")
    }

    fun start() {
        stop()
        val token = generation
        publish(RouteContext())
        val sdk = NaviHelper(context.applicationContext)
        helper = sdk
        fun deliver(action: () -> Unit) { main.post { if (generation == token) action() } }
        listener = object : NaviHelperEventListener {
            override fun onRouteStateInfo(info: RouteStateInfo) = deliver {
                val guiding = info.naviMode == NaviMode.ROUTE_GUIDING || info.naviMode == NaviMode.SIMULATION_GUIDING
                Log.i(TAG, "Route state: mode=${info.naviMode}, calculating=${info.isRouting}")
                if (!guiding) {
                    pendingStop = null
                    publish(state.copy(connected = true, loading = false, guiding = false, destination = null, waypoints = emptyList(), waypointInfoReceived = true, instructions = emptyList(), instructionInfoReceived = false, remainingMeters = null, remainingSeconds = null, message = "내비에서 목적지를 먼저 설정해 주세요."))
                    if (expectedCancel) completeControl("내비 경로 종료를 확인했어요. 휴식 타이머는 별도로 유지됩니다.")
                    controlDestinationFresh = true; controlWaypointsFresh = true; observeControl()
                } else {
                    publish(state.copy(guiding = true, message = "현재 경로 정보를 불러오고 있어요…"))
                    request { sdk.getDestinationInfo(); sdk.getWaypointInfo(); sdk.getTBTInfo() }
                }
            }
            override fun onDestinationInfo(info: DestinationInfo) = deliver {
                if (!state.guiding) return@deliver
                val destination = NaviPlace(info.name, info.latitude, info.longitude, info.poiId, info.poiSubId, info.poiProvider, info.address)
                if (pendingDestination != null && pendingDestination != destination.identity) cancelPending("목적지가 바뀌었어요. 새 경로를 확인해 주세요.")
                publish(state.copy(destination = destination, loading = false, remainingMeters = info.remainDistance,
                    remainingSeconds = info.remainTime, message = "현대 내비의 현재 경로"))
                Log.i(TAG, "Destination received; coordinatesValid=${info.latitude != 0.0 && info.longitude != 0.0}")
                freshDestination = true
                controlDestinationFresh = true; observeControl()
                checkPending()
            }
            override fun onWayPointInfo(info: List<WaypointInfo>) = deliver {
                if (!state.guiding) return@deliver
                val points = info.map { NaviPlace(it.name, it.latitude, it.longitude, it.poiId, it.poiSubId, it.poiProvider, it.address) }
                publish(state.copy(waypoints = points, waypointInfoReceived = true))
                Log.i(TAG, "Waypoints received: ${points.size}")
                freshWaypoints = true
                controlWaypointsFresh = true; observeControl()
                checkPending()
            }
            override fun onTBTInfo(info: List<TBTInfo>) = deliver {
                if (!state.guiding) return@deliver
                publish(state.copy(instructions = info.map { it.description }, instructionInfoReceived = true))
                // Log counts only; do not persist the user's route/address.
                Log.i(TAG, "Guidance received: ${info.size}; restAreaMentions=${info.count { it.description.contains("휴게소") }}")
            }
            override fun onDestinationChanged() = deliver { if (controlBusy) request { sdk.getRouteStateInfo() } else refresh() }
            override fun onReRouteEnded() = deliver { if (expectedReroute) completeControl("내비 재탐색 완료 응답을 받았어요."); refresh() }
            override fun onDestinationArrived(info: DestinationArrivedInfo) = deliver { publish(state.copy(arrivalName = info.name)); refresh() }
            override fun onWayPointArrived(info: WaypointArrivedInfo) = deliver { publish(state.copy(arrivalName = info.name)); request { sdk.getWaypointInfo() } }
            override fun onRouteOptionChanged(option: RouteOption) = deliver { if (expectedOption == option) completeControl("경로 옵션 변경을 확인했어요.") }
            override fun onBookmarkInfo(info: List<BookmarkInfo>) = deliver {
                if (libraryPending && !libraryRecent) finishLibrary(info.map { NaviPlace(it.name, it.latitude, it.longitude, it.poiId, it.poiSubId, it.poiProvider, it.address) })
            }
            override fun onRecentDestinationInfo(info: List<RecentDestinationInfo>) = deliver {
                if (libraryPending && libraryRecent) finishLibrary(info.map { NaviPlace(it.name, it.latitude, it.longitude, it.poiId, it.poiSubId, it.poiProvider, it.address) })
            }
            override fun onCurrentLocationInfo(info: CurrentLocationInfo) = deliver {
                locationText = if (validCoordinates(info.latitude, info.longitude)) "내비 위치 응답: ${info.address.ifBlank { "주소 없음" }} · 위치 측정 시각은 제공되지 않음" else "유효한 내비 위치를 받지 못했어요."
                extrasChanged()
            }
            override fun onChargerOperatorInfo(info: List<ChargerOperator>) = deliver {
                chargerText = if (info.isEmpty()) "내비가 제공한 충전 사업자가 없어요." else "충전 사업자: " + info.take(20).joinToString { it.name } + " · 충전기 가용 상태는 별도 확인"
                extrasChanged()
            }
            override fun onRoutingCompleted() = deliver { if (controlBusy) request { sdk.getRouteStateInfo() } else if (pendingStop != null) request { sdk.getDestinationInfo(); sdk.getWaypointInfo() } else refresh() }
            override fun onRouteCancelled() = deliver { if (expectedCancel) completeControl("내비 경로 종료 응답을 받았어요."); refresh() }
            override fun onWaypointChanged(info: WaypointChangedInfo) = deliver { request { sdk.getDestinationInfo(); sdk.getWaypointInfo() } }
            override fun onError(errorCode: NaviErrorCode) = deliver {
                if (controlBusy) completeControl("내비가 요청을 처리하지 못했어요. 실제 경로를 확인해 주세요.")
                if (libraryPending) { libraryPending = false; libraryMessage = "장소 목록을 불러오지 못했어요."; extrasChanged() }
                cancelPending("내비가 경로 요청을 처리하지 못했어요. 내비 화면을 확인해 주세요.")
            }
        }.also(sdk::addListener)
        armReadTimeout()
        try {
            sdk.initialize(onConnected = { deliver {
                publish(state.copy(connected = true))
                request { sdk.getRouteStateInfo() }
            } }, onFailed = { error -> deliver {
                publish(RouteContext(loading = false, message = if (error is SecurityException) "내비 정보 조회 권한을 확인해 주세요." else "내비에 연결하지 못했어요. 다시 확인을 눌러 주세요."))
            } })
        } catch (_: Exception) { publish(RouteContext(loading = false, message = "내비 연결을 확인해 주세요.")) }
    }

    private fun refresh() {
        pendingStop = null; pendingRouteKey = null; pendingDestination = null; writeSent = false
        publish(state.copy(loading = true, waypointInfoReceived = false, instructionInfoReceived = false))
        armReadTimeout()
        request { helper?.getRouteStateInfo() }
    }

    private fun armReadTimeout() {
        val token = generation
        val serial = ++requestSerial
        main.postDelayed({ if (generation == token && serial == requestSerial && state.loading)
            cancelPending("경로 응답이 늦어지고 있어요. 다시 확인을 눌러 주세요.") }, 20_000)
    }

    /** Candidate must carry a validated native POI; never accepts an invented/blank ID. */
    fun appendVerifiedStop(stop: NaviPlace, expectedRouteKey: String) {
        if (pendingStop != null || controlBusy) return
        if (!allowMutation() || !stop.usable || !canAppendStop(state, expectedRouteKey, stop)) {
            publish(state.copy(message = "경로가 바뀌었거나 경유지가 가득 찼어요. 경로를 다시 확인해 주세요.")); return
        }
        freshDestination = false; freshWaypoints = false
        pendingStop = stop; pendingRouteKey = expectedRouteKey; pendingDestination = state.destination?.identity; writeSent = false
        publish(state.copy(loading = true, waypointInfoReceived = false, message = "기존 목적지와 경유지를 다시 확인하고 있어요…"))
        request { helper?.getDestinationInfo(); helper?.getWaypointInfo() }
        val token = generation
        val serial = ++requestSerial
        main.postDelayed({ if (generation == token && serial == requestSerial && pendingStop != null) cancelPending("경유지 반영을 확인하지 못했어요. 내비에서 경유지를 확인해 주세요.") }, 15_000)
    }

    private fun checkPending() {
        val target = pendingStop ?: return
        if (!state.waypointInfoReceived || !freshDestination || !freshWaypoints) return
        if (writeSent) {
            if (state.waypoints.any { it.samePlace(target) } && state.destination?.identity == pendingDestination) {
                cancelPending("${target.name} 경유지가 반영됐어요. 기존 목적지는 유지됩니다.")
            }
            return
        }
        val checked = state.copy(loading = false)
        if (!allowMutation() || !canAppendStop(checked, pendingRouteKey ?: "", target)) {
            cancelPending("경로가 변경되어 경유지를 추가하지 않았어요. 다시 확인해 주세요."); return
        }
        writeSent = true
        freshDestination = false; freshWaypoints = false
        request {
            helper?.addWaypoint(RequestWaypointInfo(target.latitude, target.longitude, target.name,
                if (state.waypoints.isEmpty()) WaypointIndex.FIRST else WaypointIndex.SECOND,
                target.poiId, target.address, requireNotNull(target.poiSubId), target.provider))
            publish(state.copy(loading = true, message = "경유지 반영을 확인하고 있어요…"))
            helper?.getDestinationInfo(); helper?.getWaypointInfo()
        }
    }

    fun loadPlaces(recent: Boolean) {
        if (libraryPending) return
        if (!state.connected || helper == null) { libraryMessage = "내비 연결 후 다시 눌러 주세요."; extrasChanged(); return }
        libraryPending = true; libraryRecent = recent; val serial = ++librarySerial
        savedPlaces = emptyList(); libraryTitle = if (recent) "최근 목적지" else "내비 즐겨찾기"
        libraryMessage = "장소 목록을 불러오고 있어요…"; extrasChanged()
        request { if (recent) helper?.getRecentDestinationInfo() else helper?.getBookmarkInfo() }
        main.postDelayed({ if (librarySerial == serial && libraryPending) {
            libraryPending = false; libraryMessage = "목록 응답이 없어요. 다시 불러오거나 내비 검색을 이용해 주세요."; extrasChanged()
        } }, 15_000)
    }
    private fun finishLibrary(places: List<NaviPlace>) {
        libraryPending = false; savedPlaces = places.distinctBy { it.identity }.take(100)
        libraryMessage = if (savedPlaces.isEmpty()) "저장된 장소가 없어요." else "${savedPlaces.size}곳 · 반려동물 동반 가능 여부는 별도 확인"
        extrasChanged()
    }
    fun readLocationAndChargers() {
        if (!state.connected || helper == null) { locationText = "내비 연결 후 다시 눌러 주세요."; extrasChanged(); return }
        val serial = ++readSerial
        locationText = "내비 위치 응답 대기…"; chargerText = "충전 사업자 응답 대기…"; extrasChanged()
        request { helper?.getCurrentLocationInfo(); helper?.getChargerOperatorInfo() }
        main.postDelayed({ if (serial == readSerial) {
            if (locationText.endsWith("대기…")) locationText = "내비 위치 응답을 받지 못했어요."
            if (chargerText.endsWith("대기…")) chargerText = "충전 사업자 응답을 받지 못했어요."
            extrasChanged()
        } }, 15_000)
    }
    private fun beginControl(key: String, action: () -> Unit) {
        if (!allowMutation() || !state.connected || helper == null || state.loading || controlBusy || pendingStop != null || state.key != key) {
            controlMessage = "경로를 다시 확인한 뒤 시도해 주세요."; extrasChanged(); return
        }
        controlBusy = true; controlMessage = "현재 경로를 다시 확인하고 있어요…"
        preparedKey = key; preparedAction = action
        controlDestinationFresh = false; controlWaypointsFresh = false
        val serial = ++commandSerial; extrasChanged()
        request { helper?.getRouteStateInfo() }
        main.postDelayed({ if (serial == commandSerial && controlBusy) completeControl("변경 결과를 확인하지 못했어요. 재요청 전에 내비 경로를 확인해 주세요.") }, 20_000)
    }
    fun setPlan(plan: NativeRoutePlan, key: String) {
        if (!plan.valid) { controlMessage = "정확한 내비 장소 정보가 없어 검색으로 확인해야 합니다."; extrasChanged(); return }
        beginControl(key) {
            expectedPlan = plan
            controlMessage = "경로 요청을 보냈어요. 반영 확인 중…"; extrasChanged()
            val target = plan.destination
            val destination = RouteInfo(latitude = target.latitude, longitude = target.longitude,
                poiName = target.name, poiId = target.poiId, poiSubId = requireNotNull(target.poiSubId),
                address = target.address, poiProvider = target.provider)
            if (plan.stops.isEmpty()) helper?.requestRoute(destination)
            else helper?.requestRouteWithWaypoints(RouteWithWaypointsRequest(destination, plan.stops.mapIndexed { index, p ->
                RequestWaypointInfo(p.latitude, p.longitude, p.name, if (index == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
                    p.poiId, p.address, requireNotNull(p.poiSubId), p.provider)
            }))
            helper?.getRouteStateInfo()
        }
    }
    fun removeStop(index: Int, key: String) {
        val destination = state.destination ?: return
        if (index !in state.waypoints.indices || !state.waypointInfoReceived) return
        val plan = NativeRoutePlan(destination, state.waypoints.filterIndexed { i, _ -> i != index })
        beginControl(key) {
            expectedPlan = plan
            helper?.removeWaypoint(if (index == 0) WaypointIndex.FIRST else WaypointIndex.SECOND)
            helper?.getDestinationInfo(); helper?.getWaypointInfo()
        }
    }
    fun changeOption(option: RouteOption, key: String) { beginControl(key) {
        if (!state.guiding) { completeControl("안내 중인 경로가 없어요."); return@beginControl }
        expectedOption = option; helper?.changeRouteOption(option)
    } }
    fun reroute(key: String) { beginControl(key) {
        if (!state.guiding) { completeControl("안내 중인 경로가 없어요."); return@beginControl }
        expectedReroute = true; helper?.requestReRoute()
    } }
    fun cancelRoute(key: String) { beginControl(key) {
        expectedCancel = true; helper?.cancelRoute(); helper?.getRouteStateInfo()
    } }
    fun dismissArrival() { publish(state.copy(arrivalName = null)) }

    fun openNativeRoute() {
        if (!state.connected || helper == null) {
            publish(state.copy(message = "내비 연결 후 다시 눌러 주세요.")); return
        }
        request { helper?.requestNavigationScreen(NavigationScreenRequest.EditRoute) }
    }
    private fun cancelPending(message: String) {
        pendingStop = null; pendingRouteKey = null; pendingDestination = null; writeSent = false
        publish(state.copy(loading = false, message = message))
    }
    private fun request(action: () -> Unit) {
        try { action() } catch (error: Exception) {
            if (controlBusy) completeControl("내비 요청에 실패했어요. 실제 경로를 확인해 주세요.")
            cancelPending(if (error is SecurityException) "내비 정보·경로 권한을 확인해 주세요." else "내비 응답을 확인하지 못했어요. 다시 확인해 주세요.")
        }
    }
    private fun publish(value: RouteContext) { if (state != value) { state = value; changed(value) } }
    fun stop() {
        generation++; commandSerial++; librarySerial++; readSerial++; main.removeCallbacksAndMessages(null)
        libraryPending = false; savedPlaces = emptyList(); libraryMessage = ""; libraryTitle = ""
        locationText = ""; chargerText = ""; controlMessage = ""; controlBusy = false
        preparedAction = null; preparedKey = null; expectedPlan = null; expectedOption = null; expectedCancel = false; expectedReroute = false
        val sdk = helper; helper = null
        listener?.let { runCatching { sdk?.removeListener(it) } }; listener = null
        runCatching { sdk?.release() }
        pendingStop = null; pendingRouteKey = null; pendingDestination = null; writeSent = false
    }
    private companion object { const val TAG = "PawPilotRoute" }
}
