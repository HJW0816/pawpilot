package com.estareng.pawpilot.navigation

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

class PleosSearchTransport(context: Context) : SearchTransport {
    private val helper = NaviHelper(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var listener: NaviHelperEventListener? = null
    private var closed = false

    override fun connect(onConnected: () -> Unit, onFailed: (Throwable) -> Unit, onError: (String) -> Unit) {
        listener = object : NaviHelperEventListener {
            override fun onError(errorCode: NaviErrorCode) {
                Log.w(TAG, "Navigation event: $errorCode")
                main.post { if (!closed) onError("내비게이션에서 오류를 알려왔어요. 내비 화면을 확인해 주세요.") }
            }
        }.also(helper::addListener)
        helper.initialize(
            onConnected = {
                main.post {
                    if (!closed) {
                        Log.i(TAG, "NaviHelper connected")
                        onConnected()
                    }
                }
            },
            onFailed = { error ->
                Log.w(TAG, "NaviHelper connection failed", error)
                main.post { if (!closed) onFailed(error) }
            },
        )
    }

    override fun search(keyword: String) {
        check(!closed)
        helper.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword))
        // The SDK can queue a request; this log is not a screen-transition assertion.
        Log.i(TAG, "SearchPlace requested")
    }

    override fun close() {
        if (closed) return
        closed = true
        main.removeCallbacksAndMessages(null)
        listener?.let { runCatching { helper.removeListener(it) }.onFailure { Log.w(TAG, "Listener cleanup failed", it) } }
        listener = null
        runCatching { helper.release() }.onFailure { Log.w(TAG, "NaviHelper release failed", it) }
    }

    private companion object { const val TAG = "PawPilotNavi" }
}
