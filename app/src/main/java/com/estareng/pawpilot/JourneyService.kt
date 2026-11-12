package com.estareng.pawpilot

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat

/** Visible, user-started demo monitoring; no automatic restart after process death or reboot. */
class JourneyService : Service() {
    private lateinit var session: JourneySession
    private lateinit var reminder: RestReminder
    private var shownState = ""
    private val tick: () -> Unit = {
        if (session.active) {
            val remaining = session.trip.remainingMinutes(session.interval)
            val state = "$remaining:${session.motionKnown}:${session.parked}:${session.vehicleMode}"
            if (state != shownState) {
                shownState = state
                if (NotificationManagerCompat.from(this).areNotificationsEnabled())
                    getSystemService(NotificationManager::class.java).notify(ONGOING, ongoing(remaining))
            }
            reminder.consume(session.trip, session.interval)?.let { key ->
                getSharedPreferences("pawpilot", MODE_PRIVATE).edit().putString("last_rest_alert", key).apply()
                showRestAlert(false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        session = JourneySession.get(this)
        reminder = RestReminder(getSharedPreferences("pawpilot", MODE_PRIVATE).getString("last_rest_alert", null))
        channels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) { stopSelf(); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(this, ONGOING, ongoing(session.trip.remainingMinutes(session.interval)),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            session.observe(tick)
            session.start()
        } catch (error: RuntimeException) {
            Log.w("PawPilotRest", "Journey service could not start", error)
            session.pause(); stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun openApp(action: String = "") = PendingIntent.getActivity(this, if (action.isEmpty()) 0 else 1,
        Intent(this, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun ongoing(remaining: Int) = NotificationCompat.Builder(this, "journey")
        .setSmallIcon(R.drawable.ic_rest_notification).setContentTitle(if (session.vehicleMode) "PawPilot · 차량 신호 감시 중" else "PawPilot · 데모 여정 진행 중")
        .setContentText(if (session.vehicleMode && !session.motionKnown) "차량 속도 신호 미확인 · 시간 누적 중지" else if (remaining == 0) "휴식 시간이에요. 일반 휴게소도 이용할 수 있어요." else "다음 휴식까지 ${remaining}분 · 내비 화면에서도 알림 유지")
        .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openApp())
        .addAction(0, "감시 중지", PendingIntent.getService(this, 2, Intent(this, JourneyService::class.java).setAction(PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()

    private fun channels() = createChannels(this)
    private fun showRestAlert(preview: Boolean) = postRestAlert(this, preview)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { session.remove(tick); session.pause(); super.onDestroy() }

    companion object {
        const val PAUSE = "com.estareng.pawpilot.PAUSE"
        const val FIND_REST = "com.estareng.pawpilot.FIND_REST"
        private const val ONGOING = 41
        fun createChannels(context: android.content.Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("journey", "여정 진행", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel("rest_due", "휴식 시간 알림", NotificationManager.IMPORTANCE_HIGH))
        }
        fun postRestAlert(context: android.content.Context, preview: Boolean) {
            createChannels(context)
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            val open = PendingIntent.getActivity(context, 1,
                Intent(context, MainActivity::class.java).setAction(FIND_REST).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, "rest_due")
                .setSmallIcon(R.drawable.ic_rest_notification)
                .setContentTitle(if (preview) "[미리보기] 잠시 쉬어 갈 시간이에요" else "잠시 쉬어 갈 시간이에요")
                .setContentText("반려동물 시설이 멀다면 일반 휴게소에서 쉬어 가세요.")
                .setCategory(NotificationCompat.CATEGORY_REMINDER).setAutoCancel(true)
                .setContentIntent(open).addAction(0, "휴게소 찾기", open).build()
            try { manager.notify(if (preview) 43 else 42, notification); Log.i("PawPilotRest", "Rest alert posted; preview=$preview") }
            catch (error: SecurityException) { Log.w("PawPilotRest", "Notifications unavailable", error) }
        }
    }
}
