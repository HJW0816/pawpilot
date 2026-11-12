package com.estareng.pawpilot

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import ai.pleos.playground.navi.constants.RouteOption
import com.estareng.pawpilot.navigation.NaviPlace
import com.estareng.pawpilot.navigation.NativeRoutePlan
import com.estareng.pawpilot.location.PleosLocationMonitor
import com.estareng.pawpilot.navigation.CancelTask
import com.estareng.pawpilot.navigation.NavigationSearch
import com.estareng.pawpilot.navigation.PleosSearchTransport
import com.estareng.pawpilot.navigation.PetRestStop
import com.estareng.pawpilot.navigation.PetRestStopCatalog
import com.estareng.pawpilot.navigation.PleosRouteMonitor
import com.estareng.pawpilot.navigation.RouteContext
import com.estareng.pawpilot.navigation.routeStopCandidates
import com.estareng.pawpilot.navigation.recommendationRequestKey
import com.estareng.pawpilot.vehicle.VehicleUxMonitor
import com.estareng.pawpilot.vehicle.VehicleUx
import com.estareng.pawpilot.reviews.VisitReview
import com.estareng.pawpilot.reviews.VisitReviewStore
import com.estareng.pawpilot.recommendation.RecommendationClient
import com.estareng.pawpilot.recommendation.RecommendationResult
import com.estareng.pawpilot.voice.GleoActions
import androidx.activity.OnBackPressedCallback
import com.estareng.pawpilot.ui.PetBackdrop

/** Preview: vehicle state is simulated. Rest intervals are chosen by the guardian. */
class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("pawpilot", MODE_PRIVATE) }
    private val session by lazy { JourneySession.get(this) }
    private val sessionChanged: () -> Unit = {
        if (!setupAllowed) {
            dialog?.dismiss()
            if (screen in listOf(Screen.SETTINGS, Screen.REVIEW, Screen.CHECKLIST)) { screen = Screen.HOME; draw() }
        }
        render(); updateRestStatus(); updateLocationStatus()
    }
    private var routeRestStatus: TextView? = null
    private var permissionAction: (() -> Unit)? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = permissionAction; permissionAction = null
        if (granted) action?.invoke() else notifyUser("알림이 꺼져 있어요. 설정에서 알림을 허용하면 내비 화면에서도 휴식 알림을 받을 수 있습니다.")
    }
    private val speedPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && setupAllowed && !session.active) { session.selectVehicleMode(true); draw() }
        else notifyUser("차량 속도 권한 또는 정차 상태를 확인해 주세요. 기존 모드를 유지합니다.")
    }
    private val locationMonitor by lazy { PleosLocationMonitor(this) { updateLocationStatus() } }
    private var locationStatusView: TextView? = null
    private var locationLiveRequested = false
    private var navControlsExpanded = false
    private var locationExpanded = false
    private val stagedStops = mutableListOf<NaviPlace>()
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && mobileForeground && setupAllowed) locationMonitor.start(locationLiveRequested)
        else notifyUser("위치 권한 또는 정차 상태를 확인해 주세요.")
    }
    private lateinit var trip: TripState
    private lateinit var music: RestMusicPlayer
    private lateinit var title: TextView
    private lateinit var timer: TextView
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var restHint: TextView
    private lateinit var feedback: TextView
    private lateinit var progress: ProgressBar
    private lateinit var restButton: Button
    private lateinit var demoButton: Button
    private lateinit var musicButton: Button
    private lateinit var navButton: Button
    private lateinit var navigation: NavigationSearch
    private val mainHandler = Handler(Looper.getMainLooper())
    private val restCatalog by lazy { runCatching { PetRestStopCatalog.load(this) } }
    private enum class Screen { HOME, SETTINGS, ROUTE, REVIEW, CHECKLIST }
    private val reviewStore by lazy { VisitReviewStore(this) }
    private val mobileLink by lazy { com.estareng.pawpilot.companion.MobileLinkClient(this) }
    private var mobileReading = false
    private var mobileForeground = false
    private var mobileApplying = false
    private var lastDelivery = ""
    private val mobilePoll = object : Runnable {
        override fun run() {
            if (!mobileForeground) return
            val link = mobileLink.session
            if (link != null && System.currentTimeMillis() >= link.optLong("expiresAt")) {
                mobileLink.end(); notifyUser("휴대폰 연결 시간이 끝났어요.")
            } else if (link != null && !mobileReading && !mobileApplying) {
                mobileReading = true
                mobileLink.read { value ->
                    mobileReading = false
                    val delivery = value?.optJSONObject("inbox")
                    if (delivery != null && mobileForeground && setupAllowed && dialog?.isShowing != true && delivery.optString("id") != lastDelivery) {
                        showMobileDelivery(delivery)
                    }
                }
            }
            mainHandler.postDelayed(this, 4000)
        }
    }
    private val companion by lazy { com.estareng.pawpilot.companion.CompanionClient(this) }
    private val recommendation by lazy { RecommendationClient(this) }
    private var recommendationKey = ""
    private var recommendationResult: RecommendationResult? = null
    private var reviewPlace = ""
    private var reviewStars = 0
    private var reviewKind = 0
    private var reviewComment = ""
    private val reviewTags = mutableSetOf<String>()
    private var screen = Screen.HOME
    private var settingsTab = 0
    private var reviewReturn = Screen.ROUTE
    private val parkedControls = mutableListOf<Button>()
    private var checklistButton: Button? = null
    private val setupAllowed: Boolean get() = parked && vehicleUx.known && vehicleUx.setupAllowed
    private val stackedActions: Boolean get() = resources.configuration.screenWidthDp < 720 || resources.configuration.fontScale > 1.3f
    private var routeContext = RouteContext()
    private lateinit var routeMonitor: PleosRouteMonitor
    private lateinit var uxMonitor: VehicleUxMonitor
    private var vehicleUx = VehicleUx()
    private var rebuilding = false
    private var routeBody: LinearLayout? = null
    private var feedbackMessage = "프로필을 설정하고 함께하는 여정을 시작해 보세요."
    private val parked: Boolean get() = session.parked
    private var interval = 60
    private var selected = 0
    private var appearance = 0
    private var profile = PetProfile()
    private var dialog: AlertDialog? = null
    private var bg = 0
    private var panel = 0
    private var soft = 0
    private var accent = 0
    private var ink = 0
    private var onAccent = 0
    private val themes = listOf(
        Palette("피치 코랄", "#F6F4EF", "#FFFFFF", "#ECEAE4", "#BA3F29", "#20221F"),
        Palette("스카이 블루", "#F3F5F7", "#FFFFFF", "#E7ECF0", "#235D86", "#20272D"),
        Palette("라일락", "#F5F3F7", "#FFFFFF", "#EBE7EF", "#694790", "#29232F"),
        Palette("레몬 민트", "#F3F5EF", "#FFFFFF", "#E8ECDF", "#45622F", "#252C21")
    )
    private val photoStore by lazy { com.estareng.pawpilot.profile.PetPhotoStore(this) }
    private val photoWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var petPhoto: Bitmap? = null
    private var photoBusy = false
    private val pickPetPhoto = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) notifyUser("사진 선택을 취소했어요. 기존 사진은 유지됩니다.")
        else if (requireParked()) importPetPhoto(uri)
    }
    private val appearances = listOf("차량 설정 따름", "밝게", "어둡게")
    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && parked) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            prefs.edit().putString("audio", uri.toString()).apply()
            notifyUser("음악을 선택했어요. 휴식 음악 버튼으로 재생할 수 있습니다.")
        } else if (uri == null) {
            notifyUser("음악 선택을 취소했어요. 기존 선택은 유지됩니다.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = prefs.getInt("theme", 0).coerceIn(themes.indices)
        interval = prefs.getInt("interval", 60).takeIf { it in listOf(30, 60, 90, 120) } ?: 60
        appearance = prefs.getInt("appearance", 0).coerceIn(appearances.indices)
        delegate.localNightMode = nightMode(appearance)
        val name = prefs.getString("pet_name", null)?.takeIf { PetProfile.nameError(it) == null } ?: "우리 아이"
        profile = PetProfile(name, prefs.getInt("pet_kind", 0).coerceIn(PetProfile.kinds.indices),
            prefs.getString("pet_breed", "").orEmpty().take(40),
            prefs.getString("pet_size", "선택 안 함").takeIf { it in PetProfile.sizes } ?: "선택 안 함",
            prefs.getBoolean("pet_quiet", false))
        trip = session.trip
        if (trip.elapsedMs > 0) feedbackMessage = "지난 여정의 기록을 불러왔어요. 여정 감시를 시작하면 이어집니다."
        music = RestMusicPlayer(this) { message -> runOnUiThread {
            render()
            if (message != null) notifyUser(message)
        } }
        navigation = NavigationSearch(
            createTransport = { PleosSearchTransport(this) },
            scheduleTimeout = { action ->
                val task = Runnable(action)
                mainHandler.postDelayed(task, 20_000)
                CancelTask { mainHandler.removeCallbacks(task) }
            },
            show = { message, _ -> notifyUser(message); render() },
        )
        routeMonitor = PleosRouteMonitor(this, { value ->
            routeContext = value
            if (screen == Screen.ROUTE && !rebuilding) updateRoutePage()
        }, { setupAllowed && mobileForeground })
        uxMonitor = VehicleUxMonitor(this) { value ->
            val changed = vehicleUx != value
            vehicleUx = value
            if (changed && ::trip.isInitialized) {
                if (!value.setupAllowed) {
                    dialog?.dismiss()
                    if (screen == Screen.SETTINGS || screen == Screen.REVIEW || screen == Screen.CHECKLIST) screen = Screen.HOME
                }
                if (value.contentLimited) dialog?.dismiss()
                draw()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screen != Screen.HOME) goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        draw()
        photoWorker.execute {
            val saved = photoStore.load()
            runOnUiThread {
                if (!isDestroyed && !isFinishing) { petPhoto = saved; draw() }
            }
        }
    }

    private fun nightMode(index: Int) = when (index) {
        1 -> AppCompatDelegate.MODE_NIGHT_NO
        2 -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    private fun notifyUser(message: String) {
        feedbackMessage = message
        if (::feedback.isInitialized) feedback.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private val scale: Float get() = (resources.configuration.screenWidthDp / 1100f).coerceIn(1f, 1.45f)
    private fun dp(value: Int) = (value * resources.displayMetrics.density * scale).toInt()
    private fun shape(color: Int, radius: Int = 20) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun label(text: String, size: Float = 20f, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size * scale
        setTextColor(ink)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = false
        if (bold) isAccessibilityHeading = true
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun card() = column().apply { background = shape(panel); setPadding(dp(24), dp(20), dp(24), dp(20)) }
    private fun gap(parent: LinearLayout, height: Int = 12) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun button(text: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 20f * scale
        isAllCaps = false
        minHeight = dp(72)
        minimumHeight = dp(72)
        setPadding(dp(20), dp(12), dp(20), dp(12))
        setTextColor(if (primary) onAccent else ink)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        stateListAnimator = null
        elevation = 0f
        backgroundTintList = null
        val surface = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(if (primary) accent else soft, 14).apply {
                setStroke(dp(3), ink)
            })
            addState(intArrayOf(), shape(if (primary) accent else soft, 14))
        }
        background = RippleDrawable(
            ColorStateList.valueOf(((if (primary) onAccent else accent) and 0x00FFFFFF) or 0x33000000),
            surface, shape(Color.WHITE, 14))
        setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); action() }
    }

    private fun parkedButton(text: String, action: () -> Unit): Button = button(text) {
        if (requireParked()) action()
    }.also { control ->
        parkedControls.add(control)
        control.isEnabled = setupAllowed
        control.alpha = if (setupAllowed) 1f else 0.5f
        control.contentDescription = if (setupAllowed) text else "$text · 정차 후 조작 허용 시 사용"
    }

    private fun goBack() {
        screen = if (screen == Screen.REVIEW) reviewReturn else Screen.HOME
        draw()
    }

    private fun actionGroup(parent: LinearLayout, controls: List<Button>) {
        val group = LinearLayout(this).apply { orientation = if (stackedActions) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        controls.forEachIndexed { index, control ->
            group.addView(control, if (stackedActions) LinearLayout.LayoutParams(-1, -2).apply {
                if (index > 0) topMargin = dp(12)
            } else LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(12) })
        }
        parent.addView(group)
    }

    private fun draw() {
        parkedControls.clear()
        checklistButton = null
        val customAccent = prefs.getString("mobile_accent", null)
        val customSurface = prefs.getString("mobile_surface", "sand")
        val t = if (customAccent != null && validMobileAccent(customAccent)) themes[selected].copy(
            accent = customAccent, bg = when (customSurface) { "white" -> "#FFFFFF"; "mint" -> "#F0F5E9"; else -> "#F6F4EF" }
        ) else themes[selected]
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        bg = Color.parseColor(if (night) "#1D211E" else t.bg)
        panel = Color.parseColor(if (night) "#292F2A" else t.panel)
        soft = Color.parseColor(if (night) "#353D36" else t.soft)
        accent = Color.parseColor(if (night) "#D9ED9B" else t.accent)
        ink = Color.parseColor(if (night) "#F3F5EE" else t.ink)
        onAccent = Color.parseColor(if (night) "#1D211E" else "#FFFFFF")
        routeBody = null
        routeRestStatus = null
        if (screen == Screen.SETTINGS) { drawSettingsPage(); return }
        if (screen == Screen.ROUTE) { drawRoutePage(); return }
        if (screen == Screen.REVIEW) { drawReviewPage(); return }
        if (screen == Screen.CHECKLIST) { drawChecklistPage(); return }
        val wide = resources.configuration.screenWidthDp >= 900 && resources.configuration.fontScale <= 1.3f
        val shell = column().apply { background = PetBackdrop(profile.kind, bg, accent, night) }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = column().apply { setPadding(dp(24), dp(20), dp(24), dp(20)) }
        scroll.addView(root)
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(shell)

        val header = row()
        val brand = column()
        brand.addView(label("pawpilot", 28f, true))
        if (resources.configuration.screenWidthDp >= 600) brand.addView(label("함께 가는 모든 순간", 16f))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(parkedButton("설정") { settings() })
        root.addView(header)
        gap(root, 16)
        title = label("", 28f, true)
        root.addView(title)
        status = label("", 18f).apply { setTextColor(accent) }
        root.addView(status)
        gap(root, 16)

        val content = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val restCard = card()
        restCard.addView(label("다음 휴식까지", 18f))
        timer = label("", 76f, true).apply { setTextColor(ink); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        restCard.addView(timer)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(soft)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        restCard.addView(progress, LinearLayout.LayoutParams(-1, dp(6)))
        gap(restCard, 12)
        restHint = label("", 18f)
        restCard.addView(restHint)
        gap(restCard, 12)
        navButton = button("경로에서 휴게소 찾기", primary = true) { openRoutePage() }
        restCard.addView(navButton, LinearLayout.LayoutParams(-1, -2))
        gap(restCard, 12)
        restButton = button("휴식 완료") { completeRest() }
        restCard.addView(restButton, LinearLayout.LayoutParams(-1, -2))
        content.addView(restCard, if (wide) LinearLayout.LayoutParams(0, -2, 1.2f) else LinearLayout.LayoutParams(-1, -2))
        content.addView(View(this), if (wide) LinearLayout.LayoutParams(dp(16), 1) else LinearLayout.LayoutParams(1, dp(16)))

        val petSurface = Color.parseColor(when (profile.kind) {
            0 -> if (night) "#322B25" else "#F4E9DF"
            1 -> if (night) "#2D2938" else "#EDE8F5"
            else -> if (night) "#29312A" else "#E9EEE3"
        })
        val petColor = Color.parseColor(when (profile.kind) {
            0 -> if (night) "#E7BD97" else "#9B4F2A"
            1 -> if (night) "#C9B8EE" else "#70539B"
            else -> if (night) "#D9ED9B" else "#45622F"
        })
        val companion = column().apply {
            background = shape(petSurface)
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        val petHeader = LinearLayout(this).apply {
            orientation = if (stackedActions) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val petName = column().apply {
            addView(label("함께하는 친구", 18f))
            addView(label(profile.name, 30f, true))
            addView(label(PetProfile.kinds[profile.kind], 18f))
        }
        petHeader.addView(petName, if (stackedActions) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f))
        petHeader.addView(petPortrait(petSurface, petColor, night), LinearLayout.LayoutParams(dp(120), dp(120)).apply {
            if (stackedActions) topMargin = dp(12) else marginStart = dp(12)
        })
        companion.addView(petHeader)
        val profileDetails = listOf(profile.breed, profile.size.takeUnless { it == "선택 안 함" }.orEmpty(),
            if (profile.quietPreferred) "조용한 곳 선호" else "").filter { it.isNotBlank() }.joinToString(" · ")
        if (profileDetails.isNotEmpty()) companion.addView(label(profileDetails, 18f))
        checklistButton = parkedButton("출발 전 준비 확인") { screen = Screen.CHECKLIST; draw() }
        companion.addView(checklistButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        gap(companion, 16)
        companion.addView(label("이번 여정", 18f, true))
        summary = label("", 20f)
        companion.addView(summary)
        content.addView(companion, if (wide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        root.addView(content)
        gap(root, 16)

        musicButton = button("휴식 음악") { toggleMusic() }
        actionGroup(root, listOf(button("일반 휴게소 찾기") { findAnyRestStop() }, musicButton))
        feedback = label(feedbackMessage, 18f).apply {
            setPadding(0, dp(12), 0, dp(12))
            setTextColor(accent)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(feedback)
        gap(root, 4)
        // Keep the start/stop control reachable while the home content scrolls.
        // Large text and narrow windows stack the footer rather than squeezing labels.
        val footer = column().apply { setBackgroundColor(bg); setPadding(dp(24), 0, dp(24), dp(12)) }
        footer.addView(View(this).apply { setBackgroundColor(soft) }, LinearLayout.LayoutParams(-1, dp(1)))
        gap(footer, 12)
        val demo = if (!stackedActions) row() else column()
        val source = label(if (session.vehicleMode) "차량 신호로 기록" else "데모 시뮬레이션", 18f, true)
        demo.addView(source, if (!stackedActions) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        demoButton = button("") { toggleDriving() }
        demo.addView(demoButton, LinearLayout.LayoutParams(if (!stackedActions) -2 else -1, -2).apply {
            if (stackedActions) topMargin = dp(8) else marginStart = dp(16)
        })
        footer.addView(demo)
        shell.addView(footer, LinearLayout.LayoutParams(-1, -2))
        render()
    }

    private fun syncTrip() = session.sync()
    private fun saveTrip() = session.save()

    private fun withNotifications(action: () -> Unit) {
        JourneyService.createChannels(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!requireParked()) return
            permissionAction = action
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else if (!androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            notifyUser("시스템 설정에서 PawPilot 알림을 허용해 주세요.")
        } else action()
    }

    private fun toggleDriving() {
        if (!session.active) withNotifications {
            try {
                androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, JourneyService::class.java))
                notifyUser(if (session.vehicleMode) "차량 신호 감시를 시작해요. 신호를 확인한 구간만 누적합니다." else "데모 여정을 시작해요. 내비 화면에서도 휴식 시간이 이어집니다.")
            } catch (_: RuntimeException) { notifyUser("여정 알림을 시작하지 못했어요. 차량의 앱 실행 상태를 확인해 주세요.") }
        } else {
            stopService(Intent(this, JourneyService::class.java))
            notifyUser("여정 감시를 멈췄어요. 쉬었다면 정차 후 휴식을 기록해 주세요.")
        }
        dialog?.dismiss()
    }

    private fun updateRestStatus() {
        routeRestStatus?.text = if (trip.remainingMinutes(interval) == 0)
            "휴식 시간이에요. 반려동물 시설이 멀다면 일반 휴게소에서 쉬어 가세요."
        else "다음 휴식까지 ${trip.remainingMinutes(interval)}분 · 경유지를 선택해도 휴식 알림은 유지돼요."
    }

    private fun findAnyRestStop() { navigation.search("휴게소") }

    private fun TextView.showText(value: String) {
        if (text.toString() != value) text = value
    }

    private fun render() {
        if (!::timer.isInitialized || !::music.isInitialized) return
        parkedControls.forEach { control ->
            control.isEnabled = setupAllowed
            control.alpha = if (setupAllowed) 1f else 0.5f
            control.contentDescription = if (setupAllowed) control.text else "${control.text} · 정차 후 조작 허용 시 사용"
        }
        checklistButton?.showText("출발 준비 ${checkedPreparation().size}/${preparationItems().size}")
        navButton.isEnabled = !navigation.connecting
        navButton.text = if (navigation.connecting) "내비 연결 중…" else "경로에서 휴게소 찾기"
        val remaining = trip.remainingMinutes(interval)
        timer.showText(if (remaining == 0) "휴식 시간" else "${remaining}분")
        title.showText(when {
            remaining == 0 -> "잠시 쉬어 갈 시간이에요"
            !session.motionKnown -> "차량 신호를 기다리고 있어요"
            !parked -> "함께 달리는 중이에요"
            trip.elapsedMs > 0 -> "잠시 멈춰 여유를 가져요"
            else -> "함께 떠날 준비를 해요"
        })
        status.showText(when {
            session.vehicleMode -> if (!session.active) "차량 신호 감시 중지" else if (!session.motionKnown) "속도 신호 미확인 · 시간 누적 중지" else if (parked) "차량 정지 · 시간 누적 중지" else "차량 이동 감지 · 시간 누적 중"
            !parked -> "데모 주행 중 · 설정은 정차 후 이용"
            !vehicleUx.known -> "데모 정차 · 차량 조작 상태 확인 중"
            !vehicleUx.setupAllowed -> "데모 정차 · 차량에서 설정 조작 제한 중"
            else -> "데모 정차 · 휴식 타이머 일시정지"
        })
        progress.progress = trip.progress(interval)
        restHint.showText(if (remaining == 0) "일반 휴게소에서 쉬어도 좋아요. 정차 후 휴식을 기록해 주세요."
            else "${interval}분 간격 · 주행한 시간만 계산해요")
        val driven = if (trip.elapsedMs in 1..59_999) "1분 미만" else "${trip.elapsedMs / 60_000}분"
        summary.showText("주행 $driven  ·  휴식 ${trip.restCount}회")
        restButton.isEnabled = setupAllowed && trip.elapsedMs > trip.restAtMs
        restButton.alpha = if (restButton.isEnabled) 1f else 0.55f
        restButton.showText(when {
            !setupAllowed -> "정차·조작 허용 후 휴식 기록"
            trip.elapsedMs <= trip.restAtMs -> if (trip.restCount > 0) "휴식 기록 완료" else "주행 후 휴식 기록"
            else -> "휴식 완료"
        })
        demoButton.showText(if (session.vehicleMode) { if (session.active) "차량 감시 중지" else "차량 감시 시작" } else if (session.active) "데모 정차" else "데모 주행 시작")
        musicButton.showText(when {
            music.isPreparing -> "음악 준비 취소"
            music.isPlaying -> "음악 정지"
            else -> "휴식 음악"
        })
    }

    private fun requireParked(): Boolean {
        val allowed = parked && vehicleUx.known && vehicleUx.setupAllowed
        if (!allowed) notifyUser(if (!vehicleUx.known) "차량 상태를 확인하고 있어요. 잠시 후 다시 눌러 주세요." else "차량의 조작 제한이 해제된 후 변경할 수 있어요.")
        return allowed
    }

    private fun completeRest() {
        if (!requireParked()) return
        syncTrip()
        if (trip.completeRest()) {
            saveTrip()
            render()
            notifyUser("휴식 ${trip.restCount}회를 기록했어요. 다음 알림은 주행 ${interval}분 후입니다.")
        }
    }

    private fun prepareDialog(current: AlertDialog) {
        dialog?.dismiss()
        dialog = current
        current.show()
        current.window?.setBackgroundDrawable(shape(panel))
        for (which in listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE)) {
            current.getButton(which)?.apply { textSize = 20f * scale; minimumHeight = dp(64); setTextColor(accent) }
        }
    }

    private fun dialogHeading(text: String) = label(text, 24f, true).apply { setPadding(dp(24), dp(20), dp(24), dp(12)) }

    private fun settings() {
        if (!requireParked()) return
        screen = Screen.SETTINGS
        draw()
    }

    private fun page(title: String, subtitle: String): LinearLayout {
        val root = column().apply { setBackgroundColor(bg); setPadding(dp(24), dp(16), dp(24), dp(16)) }
        val header = row()
        header.addView(button("‹  뒤로") { goBack() })
        header.addView(label(title, 30f, true).apply { setPadding(dp(24), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        root.addView(label(subtitle, 18f).apply { setPadding(0, dp(12), 0, dp(12)) })
        setContentView(root)
        return root
    }

    private fun choiceGrid(parent: LinearLayout, options: List<String>, chosen: Int, columns: Int = 2, action: (Int) -> Unit) {
        val count = if (resources.configuration.screenWidthDp < 600 || resources.configuration.fontScale > 1.3f) 1 else columns
        options.chunked(count).forEachIndexed { group, values ->
            val line = row()
            values.forEachIndexed { column, value ->
                val index = group * count + column
                line.addView(button((if (index == chosen) "✓  " else "") + value, index == chosen) {
                    if (requireParked()) action(index)
                }.apply { isSelected = index == chosen }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (column > 0) marginStart = dp(12)
                })
            }
            parent.addView(line)
            gap(parent, 12)
        }
    }

    private fun preparationItems(): List<String> = listOf(
        "이동장·이동용 고정 장비 확인",
        "물·급수 용품 준비",
        if (profile.kind == 1) "이동장 잠금·배변 용품 확인" else "리드줄·배변 용품 준비",
        "목적지·휴식 간격 확인"
    )

    private fun checkedPreparation(): Set<String> = prefs.getStringSet("preparation_${profile.kind}", emptySet())
        .orEmpty().intersect(preparationItems().toSet())

    private fun drawChecklistPage() {
        val root = page("출발 전 준비", "${profile.name}와 떠나기 전, 보호자가 직접 확인해 주세요.")
        val body = card()
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val checked = checkedPreparation().toMutableSet()
        val items = preparationItems()
        val count = label("${checked.size} / ${items.size}개 확인", 32f, true).apply {
            setTextColor(accent); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.addView(count)
        body.addView(label("체크는 이 기기에 저장돼요. 새 여정을 시작하면 다시 확인합니다.", 20f))
        gap(body, 16)
        items.forEach { item ->
            val check = CheckBox(this).apply {
                text = item; textSize = 22f * scale; setTextColor(ink)
                buttonTintList = ColorStateList.valueOf(accent)
                minimumHeight = dp(72); setPadding(dp(16), dp(12), dp(16), dp(12))
                val checkSurface = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), shape(soft, 14).apply { setStroke(dp(3), ink) })
                    addState(intArrayOf(), shape(soft, 14))
                }
                background = RippleDrawable(ColorStateList.valueOf((accent and 0x00FFFFFF) or 0x22000000), checkSurface, shape(Color.WHITE, 14))
                isChecked = item in checked
                setOnClickListener {
                    if (!requireParked()) { isChecked = item in checked; return@setOnClickListener }
                    if (isChecked) checked.add(item) else checked.remove(item)
                    prefs.edit().putStringSet("preparation_${profile.kind}", checked.toSet()).apply()
                    count.text = "${checked.size} / ${items.size}개 확인"
                }
            }
            body.addView(check, LinearLayout.LayoutParams(-1, -2)); gap(body, 12)
        }
        body.addView(label("준비 확인용 목록입니다. 실제 장비 장착·차량 상태를 자동으로 감지하지 않아요.", 18f))
        feedback = label("선택 즉시 저장 · 체크를 다시 누르면 해제", 18f)
        root.addView(feedback)
    }

    private fun drawSettingsPage() {
        val root = page("여정 설정", "선택하면 바로 저장돼요. 차량의 조작 허용 상태를 따릅니다.")
        actionGroup(root, listOf("휴식", "화면", "프로필·음악").mapIndexed { index, text ->
            button(text, settingsTab == index) { settingsTab = index; draw() }.apply { isSelected = settingsTab == index }
        })
        gap(root, 16)
        val body = column()
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(parkedButton("휴대폰 연결 · 사진·테마·장소 받기") { showMobileLink() }, LinearLayout.LayoutParams(-1, -2))
        gap(body, 16)
        val section = card()
        body.addView(section)
        when (settingsTab) {
            0 -> {
                section.addView(label("주행 시간 출처", 26f, true))
                section.addView(label("출처 변경은 감시를 멈춘 뒤 가능해요. 차량 모드는 속도 읽기 권한이 필요합니다.", 18f))
                choiceGrid(section, listOf("데모 시뮬레이션", "차량 속도 신호"), if (session.vehicleMode) 1 else 0) { index ->
                    if (session.active) notifyUser("홈에서 여정 감시를 먼저 멈춰 주세요.")
                    else if (index == 1 && checkSelfPermission("android.car.permission.CAR_SPEED") != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        speedPermission.launch("android.car.permission.CAR_SPEED")
                    else { session.selectVehicleMode(index == 1); draw() }
                }
                section.addView(label("휴식 알림 간격", 30f, true))
                section.addView(label("주행한 시간을 기준으로 알려드려요.", 22f))
                gap(section, 20)
                val values = listOf(30, 60, 90, 120)
                choiceGrid(section, values.map { "${it}분" }, values.indexOf(interval), 4) { index ->
                    interval = values[index]; prefs.edit().putInt("interval", interval).apply()
                    feedbackMessage = "${interval}분으로 저장했어요. 지금까지의 주행 시간은 유지됩니다."
                    draw()
                }
                section.addView(label("시설 추천과 별개로 알려드려요. 필요하면 일반 휴게소에서 쉬어 가세요.", 20f))
                gap(section, 12)
                section.addView(button("휴식 알림 미리보기") {
                    withNotifications {
                        JourneyService.postRestAlert(this, true)
                        notifyUser("미리보기 알림을 보냈어요. 여정 기록은 바뀌지 않습니다.")
                    }
                }, LinearLayout.LayoutParams(-1, -2))
                gap(body, 24)
                body.addView(button("새 여정 시작") { confirmNewTrip() }, LinearLayout.LayoutParams(-1, -2))
                body.addView(label("현재 여정 기록을 초기화하기 전에 한 번 더 확인해요.", 18f))
            }
            1 -> {
                section.addView(label("화면 밝기 모드", 30f, true))
                section.addView(label("차량 설정 따름을 선택하면 차량의 주·야간 설정에 맞춰요.", 20f))
                gap(section, 16)
                choiceGrid(section, appearances, appearance, 3) { index ->
                    appearance = index; prefs.edit().putInt("appearance", index).apply(); saveTrip()
                    feedbackMessage = "화면 모드를 ${appearances[index]}(으)로 저장했어요."
                    delegate.localNightMode = nightMode(index); draw()
                }
                gap(section, 16)
                section.addView(label("밝은 화면 색상", 26f, true))
                gap(section, 12)
                choiceGrid(section, themes.map { it.name }, selected) { index ->
                    selected = index; prefs.edit().putInt("theme", index).remove("mobile_accent").remove("mobile_surface").apply()
                    feedbackMessage = "${themes[index].name} 색상을 저장했어요."; draw()
                }
            }
            else -> {
                section.addView(label(profile.name, 30f, true))
                section.addView(label("${PetProfile.kinds[profile.kind]} · ${profile.breed.ifBlank { "품종 미입력" }} · ${profile.size}", 22f))
                gap(section, 12)
                section.addView(button("프로필 수정") { editProfile() }, LinearLayout.LayoutParams(-1, -2))
                gap(section, 20)
                section.addView(label("우리 아이 사진", 26f, true))
                section.addView(petPortrait(panel, accent, resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES),
                    LinearLayout.LayoutParams(dp(120), dp(120)))
                section.addView(label("이 기기에만 저장해요. 홈에서는 사진 중앙을 정사각형으로 보여줘요.", 18f))
                section.addView(button(if (photoBusy) "사진 저장 중…" else if (petPhoto == null) "사진 선택" else "사진 바꾸기") { choosePetPhoto() }.apply {
                    isEnabled = !photoBusy
                }, LinearLayout.LayoutParams(-1, -2))
                if (petPhoto != null) {
                    gap(section, 12)
                    section.addView(button("사진 삭제 · 기본 캐릭터로") { confirmRemovePetPhoto() }.apply {
                        isEnabled = !photoBusy
                    }, LinearLayout.LayoutParams(-1, -2))
                }
                gap(section, 24)
                section.addView(label("휴식 음악", 26f, true))
                section.addView(label(if (prefs.getString("audio", null) == null) "선택한 음악이 없어요." else "이 기기에 선택한 음악이 있어요.", 20f))
                section.addView(button("음악 파일 선택") { chooseAudio() }, LinearLayout.LayoutParams(-1, -2))
            }
        }
        feedback = label(feedbackMessage, 18f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(feedback)
    }

    private fun openRoutePage() {
        screen = Screen.ROUTE
        draw()
        routeMonitor.start()
    }

    private fun drawRoutePage() {
        val root = page("이번 경로의 쉼터", "현대 내비에 설정한 목적지를 유지하며 쉬어 갈 곳을 확인해요.")
        routeRestStatus = label("", 22f, true).also(root::addView)
        updateRestStatus()
        val body = column()
        routeBody = body
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        // Compact windows scroll secondary actions with content, keeping the main search reachable.
        val search = button("일반 휴게소 찾기", primary = true) { findAnyRestStop() }
        root.addView(search, LinearLayout.LayoutParams(-1, -2))
        updateRoutePage()
    }

    private fun requestLocation(live: Boolean) {
        if (!requireParked()) return
        if (!locationMonitor.supported()) { notifyUser("이 환경에서는 Pleos 위치 서비스를 사용할 수 없어요."); return }
        locationLiveRequested = live
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, "pleos.car.permission.FUSED_LOCATION") == android.content.pm.PackageManager.PERMISSION_GRANTED)
            locationMonitor.start(live)
        else locationPermission.launch("pleos.car.permission.FUSED_LOCATION")
    }
    private fun updateLocationStatus() {
        val loc = locationMonitor
        val fix = loc.fix
        val point = loc.watching
        val distance = if (fix != null && fix.fresh(System.currentTimeMillis()) && point != null)
            "\n${point.name}까지 직선거리 ${"%.1f".format(fix.distanceMeters(point.latitude, point.longitude) / 1000)} km · 도로 이동거리 아님"
            else if (fix != null && !fix.fresh(System.currentTimeMillis())) "\n위치가 오래되어 거리를 표시하지 않아요." else ""
        locationStatusView?.text = loc.status + distance + if (loc.proximity.isNotBlank()) "\n${loc.proximity}" else ""
    }
    private fun confirmNavigation(title: String, detail: String, action: () -> Unit) {
        if (!requireParked()) return
        prepareDialog(AlertDialog.Builder(this).setTitle(title).setMessage(detail)
            .setNegativeButton("취소", null).setPositiveButton("확인") { _, _ -> if (requireParked()) action() }.create())
    }
    private fun showNativePlace(place: NaviPlace) {
        if (!requireParked()) return
        val key = routeContext.key
        val content = column()
        content.addView(label(place.name, 26f, true)); content.addView(label(place.address, 20f))
        content.addView(label("반려동물 동반 조건은 장소에 확인해 주세요.", 18f))
        if (!place.usable) content.addView(button("내비 검색으로 확인") { dialog?.dismiss(); navigation.search(place.name) })
        else {
            content.addView(button("여기로 가기", primary = true) {
                val stops = stagedStops.toList()
                val plan = NativeRoutePlan(place, stops)
                if (!plan.valid) { notifyUser("목적지와 경유지가 중복되었거나 장소 정보가 부족해요."); return@button }
                confirmNavigation("새 경로를 요청할까요?", "목적지: ${place.name}\n경유: ${stops.joinToString { it.name }.ifBlank { "없음" }}\n현재 내비 목적지와 경유지를 이 구성으로 변경합니다.") {
                    routeMonitor.setPlan(plan, key)
                }
            })
            if (routeContext.guiding && routeContext.waypointInfoReceived && routeContext.waypoints.size < 2)
                content.addView(button("현재 경로에 경유지 추가") {
                    confirmNavigation("가는 길에 들를까요?", "${place.name}을 추가합니다. 기존 목적지 ${routeContext.destination?.name}를 유지합니다.") {
                        routeMonitor.appendVerifiedStop(place, key)
                    }
                })
            content.addView(button("새 여행의 경유지로 담기 (${stagedStops.size}/2)") {
                if (!requireParked()) return@button
                if (stagedStops.size >= 2 || stagedStops.any { it.samePlace(place) }) notifyUser("경유지는 중복 없이 두 곳까지 담을 수 있어요.")
                else { stagedStops.add(place); dialog?.dismiss(); updateRoutePage() }
            })
            content.addView(button("이 장소 접근 알림 켜기") {
                if (!requireParked()) return@button
                if (!locationMonitor.active) { notifyUser("먼저 차량 위치 사용을 켜 주세요."); return@button }
                locationMonitor.watch(place); dialog?.dismiss()
            })
        }
        prepareDialog(AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(content) }).setNegativeButton("닫기", null).create())
    }
    private fun addNavigationTools(body: LinearLayout) {
        val tools = card()
        tools.addView(label("내비에 저장된 장소", 24f, true))
        actionGroup(tools, listOf(button("즐겨찾기") { if (requireParked()) routeMonitor.loadPlaces(false) },
            button("최근 목적지") { if (requireParked()) routeMonitor.loadPlaces(true) }))
        if (routeMonitor.libraryMessage.isNotBlank()) tools.addView(label(routeMonitor.libraryTitle + " · " + routeMonitor.libraryMessage, 18f))
        routeMonitor.savedPlaces.take(5).forEach { place -> tools.addView(button(place.name) { showNativePlace(place) }) }
        if (routeMonitor.savedPlaces.size > 5) tools.addView(button("저장된 장소 모두 보기") {
            if (!requireParked()) return@button
            val list = column()
            routeMonitor.savedPlaces.forEach { place -> list.addView(button(place.name) { showNativePlace(place) }) }
            prepareDialog(AlertDialog.Builder(this).setTitle(routeMonitor.libraryTitle).setView(ScrollView(this).apply { addView(list) }).setNegativeButton("닫기", null).create())
        })
        if (stagedStops.isNotEmpty()) {
            tools.addView(label("새 여행 경유 순서: " + stagedStops.joinToString(" → ") { it.name }, 20f))
            tools.addView(button("담은 경유지 비우기") { if (requireParked()) { stagedStops.clear(); updateRoutePage() } })
        }
        val route = routeContext; val key = route.key
        tools.addView(label(routeMonitor.controlMessage, 20f))
        if (route.guiding) tools.addView(button(if (navControlsExpanded) "경로 관리 접기" else "경로 관리 · 옵션과 경유지") { if (requireParked()) { navControlsExpanded = !navControlsExpanded; updateRoutePage() } })
        if (navControlsExpanded && route.guiding && !routeMonitor.controlBusy) {
            tools.addView(button("경로 옵션 선택") {
                if (!requireParked()) return@button
                val options = listOf("추천" to RouteOption.RECOMMENDED, "최단 시간" to RouteOption.SHORT_TIME,
                    "최단 거리" to RouteOption.SHORT_DISTANCE, "무료 도로 우선" to RouteOption.FREE,
                    "고속도로 우선" to RouteOption.HIGHWAY, "편안한 경로" to RouteOption.COMFORTABLE,
                    "스쿨존 고려" to RouteOption.SCHOOL_ZONE)
                prepareDialog(AlertDialog.Builder(this).setTitle("경로 옵션")
                    .setItems(options.map { it.first }.toTypedArray()) { _, index ->
                        confirmNavigation("경로 옵션을 변경할까요?", options[index].first + " 옵션으로 요청합니다. 반려동물 멀미 예방 효과를 뜻하지 않습니다.") { routeMonitor.changeOption(options[index].second, key) }
                    }.setNegativeButton("닫기", null).create())
            })
            tools.addView(button("현재 경로 재탐색") { confirmNavigation("경로를 다시 찾을까요?", "현재 목적지를 기준으로 재탐색을 요청합니다.") { routeMonitor.reroute(key) } })
            if (route.waypointInfoReceived) route.waypoints.forEachIndexed { index, place ->
                tools.addView(button("경유지 제외 · ${place.name}") { confirmNavigation("이 경유지를 제외할까요?", "${place.name}을 제외하고 목적지는 유지합니다.") { routeMonitor.removeStop(index, key) } })
            }
            if (route.waypoints.size == 2 && route.destination != null) tools.addView(button("두 경유지 순서 바꾸기") {
                val plan = NativeRoutePlan(route.destination, route.waypoints.reversed())
                confirmNavigation("경유 순서를 바꿀까요?", plan.stops.joinToString(" → ") { it.name }) { routeMonitor.setPlan(plan, key) }
            })
            tools.addView(button("내비 경로 종료") { confirmNavigation("내비 안내를 종료할까요?", "휴식 타이머와 여정 기록은 별도로 유지됩니다.") { routeMonitor.cancelRoute(key) } })
        }
        route.arrivalName?.let { name ->
            tools.addView(label("내비 도착 알림 · $name", 22f, true))
            tools.addView(button("방문 후기 초안 쓰기") { if (requireParked()) openReview(name) })
            tools.addView(button("실제 휴식을 마쳤어요") { completeRest() })
            tools.addView(button("도착 알림 닫기") { if (requireParked()) routeMonitor.dismissArrival() })
        }
        gap(tools, 16)
        tools.addView(button(if (locationExpanded) "위치 도구 접기" else "차량 위치 · 접근 알림") { if (requireParked()) { locationExpanded = !locationExpanded; updateRoutePage() } })
        locationStatusView = null
        if (locationExpanded) {
        tools.addView(label("차량 위치와 접근 알림", 24f, true))
        locationStatusView = label("", 18f).also(tools::addView); updateLocationStatus()
        tools.addView(label("이 앱이 화면에 보이는 동안만 수신합니다. 다른 앱으로 전환하면 접근 알림도 해제됩니다.", 18f))
        actionGroup(tools, listOf(button("위치 사용 켜기") { requestLocation(true) }, button("마지막 위치 조회") { requestLocation(false) }))
        actionGroup(tools, listOf(button("위치 수신 끄기") { locationMonitor.stop() }, button("접근 알림 해제") { locationMonitor.clearWatch() }))
        tools.addView(button("위치 접근 제한 안내") { if (requireParked()) locationMonitor.showBlocked() })
        tools.addView(button("내비 위치·충전 사업자 조회") { if (requireParked()) routeMonitor.readLocationAndChargers() })
        if (routeMonitor.locationText.isNotBlank()) tools.addView(label(routeMonitor.locationText, 18f))
        if (routeMonitor.chargerText.isNotBlank()) tools.addView(label(routeMonitor.chargerText, 18f))
        }
        body.addView(tools)
    }

    private fun updateRoutePage() {
        val body = routeBody ?: return
        val scroll = body.parent as? ScrollView
        val previousScroll = scroll?.scrollY ?: 0
        rebuilding = true
        body.removeAllViews()
        val route = routeContext
        val info = card()
        info.addView(label(route.destination?.name ?: if (route.loading) "경로 확인 중…" else "안내 중인 경로 확인", 30f, true))
        if (route.destination != null) {
            val distance = route.remainingMeters?.let { "%.1f km".format(it / 1000.0) } ?: "거리 확인 중"
            val time = route.remainingSeconds?.let { "${(it + 59) / 60}분" } ?: "시간 확인 중"
            info.addView(label("$distance  ·  $time", 26f))
            info.addView(label(if (route.waypointInfoReceived) "경유지 ${route.waypoints.size}/2개" else "기존 경유지 확인 중", 20f))
            route.waypoints.forEach { info.addView(label("경유 · ${it.name}", 20f)) }
            if (route.destination.name.contains("휴게소") && vehicleUx.setupAllowed && parked)
                info.addView(button("이 휴게소 동반 후기") { openReview(route.destination.name) }, LinearLayout.LayoutParams(-1, -2))
        }
        info.addView(label(route.message, 20f))
        if (route.destination == null && !route.loading) {
            info.addView(label("현대 내비에서 목적지를 정한 뒤 다시 확인해 주세요. 휴식 알림은 경로 없이도 유지돼요.", 20f))
        }
        gap(info, 12)
        actionGroup(info, listOf(
            button("현대 내비 열기") { routeMonitor.openNativeRoute() },
            button(if (route.loading) "경로 확인 중…" else "경로 새로고침") { routeMonitor.start() }.apply {
                isEnabled = !route.loading; alpha = if (isEnabled) 1f else 0.5f
            }
        ))
        body.addView(info)
        if (setupAllowed && !vehicleUx.contentLimited) addNavigationTools(body)
        gap(body, 16)
        val catalog = restCatalog.getOrNull()
        val matches = catalog?.let { routeStopCandidates(it.stops, route.instructions) }.orEmpty()
        if (route.guiding && route.destination != null) {
            if (route.instructionInfoReceived && route.waypointInfoReceived) {
                // The timer updates every minute and TBT distances change while driving.
                // Refresh only when the route's named rest areas, profile, or due state changes.
                val profileKey = listOf(profile.kind, profile.breed, profile.size, profile.quietPreferred).toString()
                val key = recommendationRequestKey(route, matches, profileKey, trip.remainingMinutes(interval) == 0)
                if (recommendationKey != key) {
                    recommendationKey = key; recommendationResult = null
                    recommendation.request(route, matches, profile, trip.remainingMinutes(interval)) { result ->
                        if (recommendationKey == key) { recommendationResult = result; if (screen == Screen.ROUTE) updateRoutePage() }
                    }
                }
            }
            val result = recommendationResult
            if (result?.status == "ready" && result.ranked.isNotEmpty()) {
                body.addView(label("근거가 있는 추천", 26f, true))
                result.ranked.take(if (vehicleUx.contentLimited) 1 else 3).forEach { suggestion ->
                    val item = card()
                    item.addView(label(suggestion.name, 24f, true))
                    suggestion.reasons.take(if (vehicleUx.contentLimited) 1 else 2).forEach { reason ->
                        item.addView(label(reason.text, 20f))
                        if (!vehicleUx.contentLimited) reason.sources.take(2).forEach { evidence ->
                            item.addView(label("${evidence.type} · ${evidence.publishedAt} · ${evidence.url}", 16f))
                        }
                    }
                    item.addView(label(if (suggestion.reviewCount > 0) "같은 반려동물 후기 ${suggestion.reviewCount}건" else "같은 반려동물 후기 없음", 18f))
                    suggestion.cautions.take(2).forEach { item.addView(label(it, 18f)) }
                    body.addView(item); gap(body, 12)
                }
            } else if (!vehicleUx.contentLimited) {
                body.addView(label(when (result?.status) {
                    "not_configured" -> "AI 추천은 후기 데이터와 서버 연결을 준비 중이에요. 아래 내비 안내 후보를 먼저 확인해 주세요."
                    "insufficient_evidence" -> "확인된 후기가 부족해 AI 추천 순위를 만들지 않았어요."
                    "unavailable" -> "AI 추천을 불러오지 못했어요. 내비 안내와 일반 휴게소 검색은 사용할 수 있어요."
                    else -> "시설과 후기 근거를 확인하고 있어요."
                }, 18f))
            }
            body.addView(label("경로 안내에 나온 놀이터 휴게소 후보", 26f, true))
            if (matches.isNotEmpty()) {
                matches.take(3).forEach { candidate ->
                    val stop = candidate.stop
                    val caption = if (candidate.directionConfirmed) "안내 방향 일치" else "진입 방향 확인 필요"
                    body.addView(button("${stop.name} · $caption") { if (requireParked()) showPetRestStop(stop, requireNotNull(catalog)) }, LinearLayout.LayoutParams(-1, -2))
                    gap(body, 12)
                }
                body.addView(label("내비 안내에 나온 이름을 시설 자료와 비교했어요. 방향 확인이 필요한 곳은 내비에서 진입 가능 여부를 확인해 주세요.", 18f))
            } else {
                body.addView(label(if (!route.instructionInfoReceived) "내비의 경로 안내 정보를 기다리고 있어요." else
                    "현재 받은 경로 안내에서는 일치하는 시설을 확인하지 못했어요.", 24f))
                body.addView(label("수신된 안내에 없는 시설은 표시되지 않을 수 있어요.", 18f))
            }
        }
        if (!vehicleUx.contentLimited && vehicleUx.setupAllowed && parked) {
            gap(body, 20)
            body.addView(button("동승자 휴대폰 연결") { showCompanion() }, LinearLayout.LayoutParams(-1, -2))
            body.addView(button("전체 시설 참고 목록") { showPetRestStops() }, LinearLayout.LayoutParams(-1, -2))
        }
        feedback = label(feedbackMessage, 18f)
        body.addView(feedback)
        rebuilding = false
        scroll?.post { if (body === routeBody) scroll.scrollTo(0, previousScroll) }
    }

    private fun petPortrait(surface: Int, color: Int, night: Boolean): View = ImageView(this).apply {
        val photo = petPhoto
        if (photo != null) {
            background = shape(surface, 20)
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(photo)
            contentDescription = "${profile.name}의 사진"
        } else {
            setImageDrawable(PetBackdrop(profile.kind, surface, color, night, portrait = true))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        isFocusable = false
    }

    private fun choosePetPhoto() {
        if (!requireParked() || photoBusy) return
        val request = ActivityResultContracts.OpenDocument().createIntent(this, arrayOf("image/*"))
        val picker = packageManager.resolveActivity(request, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        if (picker == null || picker.activityInfo.packageName == "com.android.car.frameworkpackagestubs") {
            notifyUser("이 차량 환경에는 사진 선택 앱이 없어요. 사진 파일을 선택할 수 있는 환경에서 이용해 주세요.")
            return
        }
        try { pickPetPhoto.launch(arrayOf("image/*")) }
        catch (_: android.content.ActivityNotFoundException) { notifyUser("사진 선택 앱을 열 수 없어요. 기존 사진은 유지됩니다.") }
        catch (_: SecurityException) { notifyUser("사진 선택 권한을 확인할 수 없어요. 기존 사진은 유지됩니다.") }
    }

    private fun importPetPhoto(uri: Uri) {
        if (photoBusy) return
        photoBusy = true
        draw()
        photoWorker.execute {
            val result = runCatching { photoStore.import(uri) }
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    photoBusy = false
                    result.getOrNull()?.let { petPhoto = it }
                    feedbackMessage = if (result.isSuccess) "우리 아이 사진을 저장했어요. 홈에도 반영됩니다."
                        else "사진을 읽지 못했어요. 20MB 이하의 다른 사진을 선택해 주세요. 기존 사진은 유지됩니다."
                    draw()
                    notifyUser(feedbackMessage)
                }
            }
        }
    }

    private fun confirmRemovePetPhoto() {
        if (!requireParked() || photoBusy) return
        prepareDialog(AlertDialog.Builder(this).setTitle("기본 캐릭터로 바꿀까요?")
            .setMessage("앱에 저장한 사진만 삭제합니다. 원본 사진은 그대로 유지돼요.")
            .setNegativeButton("취소", null).setPositiveButton("사진 삭제") { _, _ ->
                if (requireParked() && !photoBusy) {
                    photoBusy = true
                    draw()
                    photoWorker.execute {
                        val result = runCatching { photoStore.remove() }
                        runOnUiThread {
                            if (!isDestroyed && !isFinishing) {
                                if (result.isSuccess) petPhoto = null
                                photoBusy = false
                                feedbackMessage = if (result.isSuccess) "사진을 삭제하고 기본 캐릭터로 바꿨어요."
                                    else "사진을 삭제하지 못했어요. 잠시 후 다시 시도해 주세요."
                                draw()
                            }
                        }
                    }
                }
            }.create())
    }

    private fun editProfile() {
        if (!requireParked() || !vehicleUx.keyboardAllowed) { notifyUser("차량에서 입력을 허용할 때 프로필을 바꿀 수 있어요."); return }
        val form = column().apply { setPadding(dp(24), 0, dp(24), 0) }
        val name = EditText(this).apply {
            id = View.generateViewId()
            setText(profile.name)
            textSize = 22f * scale
            setTextColor(ink)
            setHintTextColor(ink)
            hint = "예: 보리"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setSingleLine(true)
            minimumHeight = dp(64)
            backgroundTintList = ColorStateList.valueOf(accent)
            selectAll()
        }
        form.addView(label("이름 · 20자 이내", 18f).apply { labelFor = name.id })
        form.addView(name, LinearLayout.LayoutParams(-1, -2))
        val nameError = label("", 18f).apply {
            visibility = View.GONE
            setTextColor(accent)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        }
        form.addView(nameError)
        gap(form)
        form.addView(label("반려동물 종류", 18f))
        val species = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        PetProfile.kinds.forEachIndexed { index, kind ->
            species.addView(RadioButton(this).apply {
                id = View.generateViewId(); tag = index
                text = kind; textSize = 20f * scale; setTextColor(ink)
                buttonTintList = ColorStateList.valueOf(accent)
                minimumHeight = dp(56)
                isChecked = index == profile.kind
            })
        }
        form.addView(species)
        form.addView(label("품종 또는 특징 · 선택", 18f))
        val breed = EditText(this).apply {
            setText(profile.breed); hint = "예: 푸들, 코리안 숏헤어"
            textSize = 20f * scale; setTextColor(ink); setHintTextColor(ink)
            filters = arrayOf(android.text.InputFilter.LengthFilter(40)); setSingleLine(true); minimumHeight = dp(64)
        }
        form.addView(breed, LinearLayout.LayoutParams(-1, -2))
        form.addView(label("크기", 18f))
        val sizes = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        PetProfile.sizes.forEach { size ->
            sizes.addView(RadioButton(this).apply {
                id = View.generateViewId(); text = size; tag = size
                textSize = 20f * scale; setTextColor(ink); minimumHeight = dp(56)
                isChecked = size == profile.size
            })
        }
        form.addView(sizes)
        val quiet = CheckBox(this).apply {
            text = "조용한 곳 선호"; textSize = 20f * scale; setTextColor(ink)
            minimumHeight = dp(64); isChecked = profile.quietPreferred
        }
        form.addView(quiet)
        form.addView(label("이 기기에 저장됩니다. 건강 판단에는 사용하지 않아요.", 16f))
        val content = ScrollView(this).apply { addView(form) }
        val current = AlertDialog.Builder(this).setCustomTitle(dialogHeading("함께하는 친구"))
            .setView(content).setNegativeButton("취소", null).setPositiveButton("저장", null).create()
        prepareDialog(current)
        current.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!requireParked()) return@setOnClickListener
            val value = name.text.toString()
            val error = PetProfile.nameError(value)
            if (error != null) {
                nameError.text = error
                nameError.visibility = View.VISIBLE
                name.error = error
                name.requestFocus()
                return@setOnClickListener
            }
            val kind = species.findViewById<RadioButton>(species.checkedRadioButtonId)?.tag as? Int ?: profile.kind
            val breedValue = breed.text.toString().trim()
            PetProfile.breedError(breedValue)?.let { breed.error = it; breed.requestFocus(); notifyUser(it); return@setOnClickListener }
            val size = sizes.findViewById<RadioButton>(sizes.checkedRadioButtonId)?.tag as? String ?: profile.size
            profile = PetProfile(value.trim(), kind, breedValue, size, quiet.isChecked)
            prefs.edit().putString("pet_name", profile.name).putInt("pet_kind", kind)
                .putString("pet_breed", breedValue).putString("pet_size", size).putBoolean("pet_quiet", quiet.isChecked).apply()
            current.dismiss()
            feedbackMessage = "${profile.name}의 프로필을 저장했어요. 홈 배경도 함께 바뀌어요."
            draw()
        }
    }

    private fun confirmNewTrip() {
        val message = label("이번 여정의 주행 시간·휴식 횟수·출발 준비 체크를 초기화합니다. 프로필과 설정은 유지돼요.", 20f)
            .apply { setPadding(dp(24), dp(8), dp(24), dp(16)) }
        val current = AlertDialog.Builder(this).setCustomTitle(dialogHeading("새 여정을 시작할까요?"))
            .setView(message).setNegativeButton("계속 이어가기", null).setPositiveButton("새 여정 시작") { _, _ ->
                if (requireParked()) {
                    session.reset()
                    val edit = prefs.edit()
                    PetProfile.kinds.indices.forEach { edit.remove("preparation_$it") }
                    edit.apply()
                    saveTrip()
                    render()
                    notifyUser("새 여정을 준비했어요. 홈에서 여정 감시를 시작해 주세요.")
                }
            }.create()
        prepareDialog(current)
    }

    private fun chooseAudio() {
        if (!requireParked()) return
        val request = ActivityResultContracts.OpenDocument().createIntent(this, arrayOf("audio/*"))
        val picker = packageManager.resolveActivity(request, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        if (picker == null || picker.activityInfo.packageName == "com.android.car.frameworkpackagestubs") {
            notifyUser("이 차량 환경은 음악 파일 선택을 지원하지 않습니다. 파일 선택 앱 연결이 필요합니다.")
            return
        }
        try { pickAudio.launch(arrayOf("audio/*")) }
        catch (_: android.content.ActivityNotFoundException) { notifyUser("음악 파일을 선택할 수 있는 앱이 없습니다.") }
        catch (_: SecurityException) { notifyUser("음악 파일 선택 권한을 확인해 주세요.") }
    }

    private fun toggleMusic() {
        if (music.isPlaying || music.isPreparing) { music.stop(); return }
        val stored = prefs.getString("audio", null)
        if (stored == null) { chooseAudio(); return }
        music.play(Uri.parse(stored))
    }

    private fun validMobileAccent(hex: String): Boolean = runCatching {
        if (!hex.matches(Regex("#[0-9A-Fa-f]{6}"))) return false
        val c = Color.parseColor(hex)
        val channels = listOf(Color.red(c), Color.green(c), Color.blue(c)).map { channel ->
            val v = channel / 255.0
            if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        val luminance = channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722
        1.05 / (luminance + 0.05) >= 4.5
    }.getOrDefault(false)

    private fun showMobileLink() {
        if (!requireParked()) return
        if (!mobileLink.configured) {
            notifyUser("휴대폰 연결 서버가 아직 설정되지 않았어요. 같은 HTTPS 서버를 연결한 뒤 이용할 수 있어요.")
            return
        }
        val current = mobileLink.session
        if (current == null) {
            prepareDialog(AlertDialog.Builder(this).setTitle("휴대폰 연결")
                .setMessage("사진·테마·장소를 휴대폰에서 받아요. 최대 15분 동안 연결하며 받은 항목은 차량에서 확인한 뒤 적용합니다.")
                .setNegativeButton("취소", null).setPositiveButton("연결 코드 만들기") { _, _ ->
                    if (requireParked()) { mobileReading = false; mobileLink.create { result ->
                        if (result == null) notifyUser("연결 서버에 접속하지 못했어요.")
                        else if (mobileForeground && setupAllowed) showMobileLink()
                        else mobileLink.end()
                    } }
                }.create())
            return
        }
        val code = current.optString("code").chunked(4).joinToString("-")
        prepareDialog(AlertDialog.Builder(this).setTitle("휴대폰에서 연결해 주세요")
            .setMessage("${mobileLink.mobileUrl}\n연결 코드: $code\n최대 15분 · 한 기기 연결\n창을 닫으면 받은 항목을 정차 상태에서 자동으로 보여 줍니다.")
            .setNegativeButton("연결 종료") { _, _ -> mobileLink.end(); lastDelivery = ""; notifyUser("휴대폰 연결을 종료했어요.") }
            .setPositiveButton("닫고 기다리기", null).create())
    }

    private fun showMobileDelivery(delivery: org.json.JSONObject) {
        if (!setupAllowed || !mobileForeground) return
        val id = delivery.optString("id")
        if (!id.matches(Regex("[A-Za-z0-9_-]{24}"))) return
        val linkId = mobileLink.session?.optString("id") ?: return
        fun stillLinked() = mobileLink.session?.optString("id") == linkId &&
            System.currentTimeMillis() < (mobileLink.session?.optLong("expiresAt") ?: 0)
        val type = delivery.optString("type")
        if (type == "place") {
            val place = delivery.optJSONObject("place") ?: return
            val query = place.optString("query")
            if (query.isBlank() || query.length > 240 || query.any { it.isISOControl() }) { mobileLink.ack(id, "failed"); return }
            prepareDialog(AlertDialog.Builder(this).setTitle("휴대폰에서 보낸 장소")
                .setMessage("${place.optString("name").take(70)}\n${place.optString("address").take(160)}\n내비에서 장소와 방향을 확인한 뒤 경로를 선택해 주세요.")
                .setNegativeButton("닫기") { _, _ -> if (stillLinked()) { lastDelivery = id; mobileLink.ack(id, "dismissed") } }
                .setPositiveButton("확인하고 내비 검색") { _, _ ->
                    if (requireParked() && stillLinked()) {
                        lastDelivery = id
                        if (navigation.connecting) { mobileLink.ack(id, "failed"); notifyUser("진행 중인 내비 검색이 있어요. 다시 보내 주세요.") }
                        else { navigation.search(query); mobileLink.ack(id, "search_requested") }
                    }
                }.create())
        } else if (type == "profile") {
            val incoming = delivery.optJSONObject("profile") ?: return
            val name = incoming.optString("name")
            val kind = listOf("dog", "cat", "other").indexOf(incoming.optString("kind"))
            val theme = incoming.optJSONObject("theme") ?: return
            val color = theme.optString("accent")
            val surface = theme.optString("surface")
            val photo = if (incoming.isNull("photo")) null else incoming.optString("photo")
            if (PetProfile.nameError(name) != null || kind < 0 || !validMobileAccent(color) || surface !in listOf("sand", "white", "mint") ||
                (photo != null && (photo.length > 700000 || !photo.startsWith("data:image/jpeg;base64,")))) {
                mobileLink.ack(id, "failed"); return
            }
            prepareDialog(AlertDialog.Builder(this).setTitle("우리 아이 프로필을 받았어요")
                .setMessage("$name · ${PetProfile.kinds[kind]}\n사진과 밝은 화면 테마를 바꿀까요? 사진이 없으면 기본 캐릭터로 돌아갑니다. 야간 화면과 조작 위치는 유지됩니다.")
                .setNegativeButton("유지하기") { _, _ -> if (stillLinked()) { lastDelivery = id; mobileLink.ack(id, "dismissed") } }
                .setPositiveButton("적용하기") { _, _ ->
                    if (requireParked() && stillLinked() && !photoBusy) {
                        mobileApplying = true; photoBusy = true; lastDelivery = id
                        photoWorker.execute {
                            // Decode first; only persist after foreground/parked/link conditions are checked again.
                            val decoded = runCatching {
                                if (photo == null) null else {
                                    val bytes = android.util.Base64.decode(photo.substringAfter(','), android.util.Base64.NO_WRAP)
                                    require(bytes.size <= 525000)
                                    android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                                        require(info.size.width in 1..768 && info.size.height in 1..768)
                                        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                                        decoder.setOnPartialImageListener { false }
                                    }
                                }
                            }
                            runOnUiThread {
                                photoBusy = false; mobileApplying = false
                                if (!isDestroyed && !isFinishing && mobileForeground && setupAllowed && stillLinked() && decoded.isSuccess) {
                                    val saved = runCatching { if (photo == null) photoStore.remove() else photoStore.save(decoded.getOrThrow()!!) }
                                    if (saved.isSuccess) {
                                        petPhoto = decoded.getOrNull()
                                        profile = if (profile.kind == kind) profile.copy(name = name) else PetProfile(name, kind)
                                        prefs.edit().putString("pet_name", profile.name).putInt("pet_kind", kind)
                                            .putString("pet_breed", profile.breed).putString("pet_size", profile.size).putBoolean("pet_quiet", profile.quietPreferred)
                                            .putString("mobile_accent", color).putString("mobile_surface", surface).apply()
                                        mobileLink.ack(id, "applied"); draw(); notifyUser("휴대폰의 사진과 테마를 적용했어요.")
                                    } else { mobileLink.ack(id, "failed"); notifyUser("사진을 저장하지 못했어요. 다시 보내 주세요.") }
                                } else if (!isDestroyed) { mobileLink.ack(id, "failed"); notifyUser("적용 조건이 바뀌었거나 사진을 읽지 못했어요. 정차 후 다시 보내 주세요.") }
                            }
                        }
                    }
                }.create())
        }
    }

    private fun showCompanion() {
        if (!requireParked()) return
        if (!companion.configured) {
            prepareDialog(AlertDialog.Builder(this).setTitle("동승자 연결 준비")
                .setMessage("연결 서버 주소가 아직 설정되지 않았어요. 서버 연결 후 동승자는 휴대폰에서 시설을 비교하고 후보·후기 초안을 보낼 수 있습니다. 경로 변경은 차량에서 직접 확인합니다.")
                .setPositiveButton("확인", null).create())
            return
        }
        if (companion.session != null && companion.routeKey != routeContext.key) companion.end()
        val current = companion.session
        if (current == null) {
            val names = restCatalog.getOrNull()?.let { routeStopCandidates(it.stops, routeContext.instructions).map { candidate -> candidate.stop.name } }.orEmpty()
            if (routeContext.loading || !routeContext.guiding || names.isEmpty()) {
                notifyUser("내비 경로에 시설 후보가 확인되면 연결할 수 있어요. 먼저 경로를 새로고침해 주세요."); return
            }
            val expectedRoute = routeContext.key
            prepareDialog(AlertDialog.Builder(this).setTitle("동승자에게 시설 후보 공유")
                .setMessage("이번 경로의 시설 후보만 공유합니다. 목적지와 반려동물 프로필은 전송하지 않아요. 연결은 15분 후 만료되며, 메모는 공개 후기가 아닙니다.")
                .setNegativeButton("취소", null).setPositiveButton("동의하고 연결") { _, _ ->
                    if (requireParked() && expectedRoute == routeContext.key) {
                        notifyUser("동승자 연결을 준비하고 있어요…")
                        companion.create(names, expectedRoute) { result ->
                            if (result == null) notifyUser("연결 서버에 접속하지 못했어요. 잠시 후 다시 시도해 주세요.")
                            else if (expectedRoute == routeContext.key && setupAllowed) showCompanion()
                            else { companion.end(); notifyUser("경로 또는 조작 상태가 바뀌어 연결을 취소했어요.") }
                        }
                    }
                }.create())
            return
        }
        val code = current.optString("code").chunked(4).joinToString("-")
        if (System.currentTimeMillis() >= current.optLong("expiresAt")) { companion.end(); notifyUser("연결 시간이 끝났어요. 새 연결을 만들어 주세요."); return }
        prepareDialog(AlertDialog.Builder(this).setTitle("동승자 휴대폰 연결")
            .setMessage("휴대폰에서 ${companion.mobileUrl} 접속\n연결 코드: $code\n최대 15분 · 한 대 연결 · 후보 전송 후 아래에서 확인하세요.")
            .setNegativeButton("닫기", null).setNeutralButton("연결 종료") { _, _ -> companion.end(); notifyUser("동승자 연결을 종료했어요.") }
            .setPositiveButton("보낸 후보 확인") { _, _ ->
                if (requireParked()) companion.read { result ->
                    if (!setupAllowed) return@read
                    if (result == null) { notifyUser("연결이 만료되었거나 서버에 접속할 수 없어요."); return@read }
                    val proposal = result.optJSONObject("suggestion")
                    if (proposal == null) { notifyUser("아직 보낸 후보가 없어요."); return@read }
                    val expectedRoute = companion.routeKey
                    val stop = restCatalog.getOrNull()?.stops?.firstOrNull { it.name == proposal.optString("name") }
                    if (stop == null || routeContext.loading || !routeContext.guiding || expectedRoute != routeContext.key ||
                        routeStopCandidates(listOf(stop), routeContext.instructions).isEmpty()) {
                        companion.end(); notifyUser("경로가 바뀌었거나 후보를 확인할 수 없어요. 경로를 새로고침해 주세요."); return@read
                    }
                    prepareDialog(AlertDialog.Builder(this).setTitle("동승자 제안 · ${stop.name}")
                        .setMessage("메모·후기 초안: ${proposal.optString("comment").take(80)}\n별점 초안: ${proposal.optInt("stars")} (0은 미입력)\n공개 후기나 AI 추천이 아닙니다. 내비에서 방향을 확인해 주세요.")
                        .setNegativeButton("닫기", null).setPositiveButton("확인하고 내비 검색") { _, _ ->
                            if (requireParked() && expectedRoute == routeContext.key && !routeContext.loading && routeContext.guiding) navigation.search(stop.searchKeyword)
                            else notifyUser("현재 경로를 다시 확인해 주세요.")
                        }.create())
                }
            }.create())
    }

    private fun showPetRestStops() {
        if (!requireParked() || vehicleUx.contentLimited) { notifyUser("전체 시설 목록은 차량의 조작 제한이 해제된 후 확인해 주세요."); return }
        val catalog = restCatalog.getOrElse { notifyUser("시설 정보를 불러오지 못했어요. 휴게소 찾기를 이용해 주세요."); return }
        val body = column().apply { setPadding(dp(24), dp(8), dp(24), dp(16)) }
        body.addView(label("${catalog.source} · ${catalog.asOf} 기준", 17f))
        body.addView(label("전국 시설 목록 · 가나다순\n진행 방향과 현재 운영 여부는 방문 전 확인해 주세요.", 18f))
        catalog.stops.forEach { stop ->
            gap(body, 8)
            body.addView(button(stop.name) { showPetRestStop(stop, catalog) }, LinearLayout.LayoutParams(-1, -2))
        }
        val current = AlertDialog.Builder(this).setCustomTitle(dialogHeading("반려견 놀이터 휴게소 ${catalog.stops.size}곳"))
            .setView(ScrollView(this).apply { addView(body) }).setNegativeButton("닫기", null).create()
        prepareDialog(current)
    }

    private fun showPetRestStop(stop: PetRestStop, catalog: PetRestStopCatalog) {
        if (!requireParked()) return
        val body = column().apply { setPadding(dp(24), dp(8), dp(24), dp(16)) }
        stop.facilities.forEachIndexed { index, facility ->
            body.addView(label("${facility.kind}${if (stop.facilities.size > 1) " ${index + 1}" else ""}", 22f, true))
            body.addView(label("위치 · ${facility.location}\n운영 · ${facility.hours}\n휴장 · ${facility.closed}", 20f))
            val observationKey = "facility:${stop.name}:${facility.kind}:${facility.location}"
            val observedAt = prefs.getLong("${observationKey}:at", 0)
            val observation = prefs.getString("${observationKey}:state", null)
            val date = if (observedAt > 0) java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA).format(java.util.Date(observedAt)) else ""
            body.addView(label(if (observation in listOf("이용 가능", "휴장·이용 불가")) "내 방문 제보 · $date · $observation\n운영기관 확인과 별개인 기기 내 기록입니다." else "최근 방문 제보 없음 · 현재 운영 여부 미확인", 18f))
            body.addView(button("오늘 방문 상태 기록") {
                if (!requireParked()) return@button
                dialog?.dismiss()
                val choices = arrayOf("오늘 이용 가능 확인", "오늘 휴장·이용 불가 확인", "내 기록 삭제")
                prepareDialog(AlertDialog.Builder(this).setTitle("${facility.kind} · 내 방문 제보")
                    .setItems(choices) { _, selected ->
                        if (requireParked()) {
                            val editor = prefs.edit()
                            if (selected == 2) editor.remove("${observationKey}:at").remove("${observationKey}:state")
                            else editor.putLong("${observationKey}:at", System.currentTimeMillis())
                                .putString("${observationKey}:state", if (selected == 0) "이용 가능" else "휴장·이용 불가")
                            editor.apply(); showPetRestStop(stop, catalog)
                        }
                    }.setNegativeButton("취소", null).create())
            })
            gap(body, 12)
        }
        body.addView(label("${catalog.source} · ${catalog.asOf} 기준\n기준일은 데이터 작성일이며 최근 영업 확인일이 아닙니다.\n현재 운영 여부와 이용 조건은 현장에서 확인해 주세요.\n내비 검색 결과에서 휴게소와 방향을 확인한 뒤 목적지 또는 경유지로 선택하세요.", 17f))
        body.addView(button("동반 후기 · 내 별점") { openReview(stop.name) }, LinearLayout.LayoutParams(-1, -2))
        gap(body, 12)
        body.addView(button("현대 내비에서 검색", primary = true) {
            if (!requireParked()) return@button
            dialog?.dismiss()
            navigation.search(stop.searchKeyword)
        }, LinearLayout.LayoutParams(-1, -2))
        val current = AlertDialog.Builder(this).setCustomTitle(dialogHeading(stop.name))
            .setView(ScrollView(this).apply { addView(body) }).setNegativeButton("닫기", null).create()
        prepareDialog(current)
    }

    private fun openReview(place: String) {
        if (!requireParked() || !vehicleUx.keyboardAllowed) return
        dialog?.dismiss()
        reviewPlace = place
        val old = reviewStore.read(place)
        reviewStars = old?.stars ?: 0; reviewKind = old?.petKind ?: profile.kind
        reviewComment = old?.comment.orEmpty()
        reviewTags.clear(); reviewTags.addAll(old?.tags.orEmpty())
        reviewReturn = screen
        screen = Screen.REVIEW; draw()
    }

    private fun drawReviewPage() {
        val root = page("$reviewPlace · 동반 후기", "내 기기에 저장하는 후기입니다. 다른 가족에게 공유하는 기능은 준비 중이에요.")
        val body = card()
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(label("우리 가족의 별점", 26f, true))
        choiceGrid(body, (1..5).map { "★ $it" }, reviewStars - 1, 5) { reviewStars = it + 1; draw() }
        body.addView(label("함께 방문한 친구", 24f, true))
        choiceGrid(body, PetProfile.kinds, reviewKind, 3) { reviewKind = it; draw() }
        body.addView(label("직접 경험한 시설·분위기", 24f, true))
        VisitReview.availableTags.chunked(2).forEach { pair ->
            val line = row()
            pair.forEach { tag -> line.addView(button((if (tag in reviewTags) "✓ " else "") + tag, tag in reviewTags) {
                if (!requireParked()) return@button
                if (!reviewTags.add(tag)) reviewTags.remove(tag)
                draw()
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) }) }
            body.addView(line); gap(body, 12)
        }
        body.addView(label("짧은 후기 · 선택 · 200자 이내", 22f))
        val comment = EditText(this).apply {
            setText(reviewComment); textSize = 22f * scale; setTextColor(ink); setHintTextColor(ink)
            hint = "산책 공간이나 쉬기 좋았던 점을 알려 주세요."
            minLines = 2; maxLines = 4
            filters = arrayOf(android.text.InputFilter.LengthFilter(200))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { reviewComment = s.toString() }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        body.addView(comment, LinearLayout.LayoutParams(-1, -2))
        gap(body, 16)
        reviewStore.read(reviewPlace)?.let { old ->
            val date = java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.KOREA).format(java.util.Date(old.updatedAt))
            body.addView(label("저장된 내 후기 · ★ ${old.stars}/5 · $date\n${old.comment}", 20f))
            body.addView(button("내 후기 삭제") {
                if (requireParked()) {
                    reviewStore.remove(reviewPlace)
                    reviewStars = 0; reviewComment = ""; reviewTags.clear()
                    feedbackMessage = "이 기기에 저장된 후기를 삭제했어요."; draw()
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(button("내 기기에 후기 저장", primary = true) {
            if (!requireParked() || !vehicleUx.keyboardAllowed) return@button
            val review = VisitReview(reviewPlace, reviewStars, reviewKind, reviewTags.toList(), reviewComment.trim(), System.currentTimeMillis())
            val error = review.error()
            if (error != null) { notifyUser(error); return@button }
            reviewStore.save(review)
            feedbackMessage = "★ ${review.stars}점 후기를 이 기기에 저장했어요. 외부에 공유되지 않습니다."
            draw()
        }, LinearLayout.LayoutParams(-1, -2))
        feedback = label(feedbackMessage, 18f)
        root.addView(feedback)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        dialog?.dismiss()
        if (::trip.isInitialized && ::music.isInitialized) draw()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVoiceAction()
    }

    private fun handleVoiceAction() {
        val action = intent.action?.takeIf { it in GleoActions.actions } ?: return
        intent.action = null // Consume once, including subsequent resumes and activity recreation.
        try {
            when (action) {
                GleoActions.FIND -> {
                    if (navigation.connecting) {
                        GleoActions.reply(this, action, "fail", "A navigation search is already pending.")
                        return
                    }
                    openRoutePage(); findAnyRestStop()
                    GleoActions.reply(this, action, "success", "The rest stop screen is open and a navigation search has been requested. Connection and search results are not yet confirmed. The route has not been changed.")
                }
                GleoActions.STATUS -> {
                    syncTrip()
                    val mode = if (session.vehicleMode) "vehicle observations" else "demo time"
                    val signal = if (session.motionKnown) "" else " Vehicle speed is unavailable and accumulation is paused."
                    GleoActions.reply(this, action, "success", "The remaining guardian-selected rest interval is ${trip.remainingMinutes(interval)} minutes using $mode.$signal")
                }
                GleoActions.CANCEL -> {
                    navigation.stop(); navigation.start(); routeMonitor.stop()
                    screen = Screen.HOME; draw()
                    GleoActions.reply(this, action, "success", "Pending PawPilot search work has been cancelled. An already opened native navigation screen is unaffected. The route has not been changed.")
                }
            }
        } catch (_: Exception) { GleoActions.reply(this, action, "fail", "The requested action could not be completed.") }
    }

    override fun onResume() {
        super.onResume()
        handleVoiceAction()
        if (intent.action == JourneyService.FIND_REST) {
            intent.action = null
            openRoutePage()
            findAnyRestStop()
        }
    }

    override fun onStart() {
        super.onStart()
        navigation.start(); uxMonitor.start()
        mobileForeground = true; mainHandler.removeCallbacks(mobilePoll); mainHandler.post(mobilePoll)

        if (screen == Screen.ROUTE) routeMonitor.start()
        session.observe(sessionChanged)
        render()
    }
    override fun onStop() {
        mobileForeground = false; mainHandler.removeCallbacks(mobilePoll)
        locationMonitor.stop(); locationStatusView = null
        recommendation.cancel()
        recommendationKey = ""
        recommendationResult = null
        uxMonitor.stop()
        routeMonitor.stop()
        val wasConnecting = navigation.connecting
        navigation.stop()
        if (wasConnecting) feedbackMessage = "내비 연결 대기를 취소했어요. 다시 찾으려면 휴게소 찾기를 눌러 주세요."
        session.remove(sessionChanged)
        syncTrip()
        saveTrip()
        music.stop()
        dialog?.dismiss()
        if (::feedback.isInitialized) feedback.text = feedbackMessage
        render()
        super.onStop()
    }
    override fun onDestroy() { mobileLink.close(); photoWorker.shutdown(); companion.close(); recommendation.close(); uxMonitor.stop(); routeMonitor.stop(); navigation.stop(); mainHandler.removeCallbacksAndMessages(null); music.stop(); super.onDestroy() }
    data class Palette(val name: String, val bg: String, val panel: String, val soft: String, val accent: String, val ink: String)
}
