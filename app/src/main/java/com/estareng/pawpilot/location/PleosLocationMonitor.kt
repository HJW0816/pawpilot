package com.estareng.pawpilot.location

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.estareng.pawpilot.navigation.NaviPlace

/** Foreground, opt-in only. No coordinate logging or persistence. */
class PleosLocationMonitor(private val context: Context, private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var sdk: FusedLocation? = null
    private var listener: FusedLocationListener? = null
    private var generation = 0
    private var watchGeneration = 0
    private var alertId: String? = null
    private var registering = false
    var fix: LocationFix? = null; private set
    var status = "위치 사용을 켜면 선택한 장소까지의 직선거리를 확인할 수 있어요."; private set
    var watching: NaviPlace? = null; private set
    var proximity = ""; private set
    val active get() = sdk != null
    fun supported(): Boolean = runCatching { FusedLocation(context.applicationContext).isSupported() }.getOrDefault(false)
    fun start(live: Boolean) {
        stop()
        if (!supported()) { status = "이 환경은 Pleos 위치 서비스를 지원하지 않아요."; changed(); return }
        val token = generation
        val client = FusedLocation(context.applicationContext)
        sdk = client
        val callback = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) { main.post {
                if (token != generation) return@post
                val candidate = location?.let { LocationFix(it.latitude, it.longitude, it.timestampMillis, it.confidence) }
                // A late cached response must not replace a newer live fix.
                if (candidate != null && candidate.fresh(System.currentTimeMillis()) && (fix == null || candidate.timestamp >= fix!!.timestamp)) {
                    fix = candidate
                    main.postDelayed({ if (token == generation && fix === candidate && !candidate.fresh(System.currentTimeMillis())) {
                        fix = null; status = "위치가 오래되어 거리를 표시하지 않아요. 다시 조회해 주세요."; changed()
                    } }, 60_001)
                    status = if (live) "위치 수신 중 · 최신 응답 기준" else "마지막 위치 조회 · 수신 시각 기준"
                } else if (fix == null || !fix!!.fresh(System.currentTimeMillis())) {
                    fix = null; status = "사용 가능한 최신 위치가 없어요. 다시 확인해 주세요."
                }
                changed()
            } }
            override fun onError(exception: Exception) { main.post {
                if (token == generation) { fix = null; status = "위치 수신 실패 · 권한과 서비스를 확인해 주세요."; changed() }
            } }
        }
        listener = callback
        try {
            client.initialize()
            status = "위치 응답을 기다리고 있어요…"
            client.getLastKnownLocation(callback)
            if (live) client.registerFusedLocationCallback(callback)
            changed()
            main.postDelayed({ if (token == generation && fix == null) { status = "위치 응답이 없어요. 서비스와 권한을 확인해 주세요."; changed() } }, 15_000)
        } catch (_: Exception) { stop(); status = "위치 서비스를 시작하지 못했어요."; changed() }
    }
    fun watch(place: NaviPlace) {
        val client = sdk ?: return
        if (!place.usable) return
        clearWatch()
        val token = generation; val watch = watchGeneration
        watching = place; registering = true; proximity = "접근 알림 등록 대기 중…"; changed()
        try { client.addProximityAlert(ProximityAlertRequest(
            latitude = place.latitude, longitude = place.longitude, radiusMeters = 200f,
            transitionTypes = setOf(ProximityTransition.ENTER, ProximityTransition.EXIT)
        ), object : ProximityAlertListener {
            override fun onRegistered(id: String) { main.post {
                if (generation != token || watchGeneration != watch) { runCatching { client.removeProximityAlert(id) }; return@post }
                alertId = id; registering = false; proximity = "200m 영역 접근 알림 켜짐 · 방문 확정 아님"; changed()
            } }
            override fun onTransition(event: ProximityAlertEvent) { main.post {
                if (generation != token || watchGeneration != watch || event.alertId != alertId) return@post
                val text = if (event.transition == ProximityTransition.ENTER) "선택 장소 주변에 접근했어요. 진입 위치와 정차 상태를 확인해 주세요." else "선택 장소 주변에서 벗어났어요."
                if (proximity != text) { proximity = text; changed() }
            } }
            override fun onError(exception: Exception) { main.post {
                if (generation == token && watchGeneration == watch) { clearWatch(); proximity = "접근 알림을 등록하지 못했어요."; changed() }
            } }
        })
            main.postDelayed({ if (generation == token && watchGeneration == watch && registering) {
                clearWatch(); proximity = "접근 알림 등록을 확인하지 못했어요. 다시 시도해 주세요."; changed()
            } }, 15_000)
        } catch (_: Exception) { clearWatch(); proximity = "접근 알림을 사용할 수 없어요."; changed() }
    }
    fun clearWatch() {
        watchGeneration++; registering = false
        alertId?.let { runCatching { sdk?.removeProximityAlert(it) } }
        alertId = null; watching = null; proximity = ""; changed()
    }
    fun showBlocked() {
        if (!supported()) { status = "이 환경은 Pleos 위치 서비스를 지원하지 않아요."; changed(); return }
        runCatching { (sdk ?: FusedLocation(context.applicationContext)).showLocationAccessBlockedPopup() }
            .onFailure { status = "위치 접근 안내를 열지 못했어요. 차량 설정에서 권한을 확인해 주세요."; changed() }
    }
    fun stop() {
        generation++; clearWatch(); main.removeCallbacksAndMessages(null)
        listener?.let { runCatching { sdk?.unregisterFusedLocationCallback(it) } }
        runCatching { sdk?.release() }; sdk = null; listener = null; fix = null
        status = "위치 수신을 중지했어요. 위치 기록은 저장하지 않습니다."; changed()
    }
}
