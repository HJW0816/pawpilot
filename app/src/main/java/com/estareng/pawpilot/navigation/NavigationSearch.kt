package com.estareng.pawpilot.navigation

/** Platform boundary: callbacks must be delivered on the UI thread. */
interface SearchTransport {
    fun connect(onConnected: () -> Unit, onFailed: (Throwable) -> Unit, onError: (String) -> Unit)
    fun search(keyword: String)
    fun close()
}

fun interface CancelTask { fun cancel() }

/** One user request at a time. Leaving the screen invalidates late SDK callbacks. */
class NavigationSearch(
    private val createTransport: () -> SearchTransport,
    private val scheduleTimeout: (() -> Unit) -> CancelTask,
    private val show: (message: String, connecting: Boolean) -> Unit,
) {
    private var active = false
    private var generation = 0
    private var transport: SearchTransport? = null
    private var timeout: CancelTask? = null
    var connecting = false
        private set

    fun start() { active = true }

    fun search(rawKeyword: String) {
        val keyword = rawKeyword.trim()
        if (!active || connecting || keyword.isEmpty()) return
        clear()
        val request = generation
        connecting = true
        show("현대 내비게이션에 연결하고 있어요…", true)
        timeout = scheduleTimeout {
            if (active && request == generation && connecting) {
                fail("내비게이션 연결이 지연되고 있어요. 잠시 후 다시 눌러 주세요.")
            }
        }
        try {
            val current = createTransport().also { transport = it }
            current.connect(
                onConnected = {
                    if (active && request == generation && connecting) {
                        timeout?.cancel()
                        timeout = null
                        connecting = false
                        try {
                            current.search(keyword)
                            // SearchPlace has no screen-open acknowledgement. Do not claim success.
                            // The SDK may report an error synchronously from search().
                            // Preserve that error instead of overwriting it with a request message.
                            if (active && request == generation) {
                                show("내비에 ‘$keyword’ 검색을 요청했어요. 내비 화면에서 결과를 확인해 주세요.", false)
                            }
                        } catch (error: Exception) { fail(messageFor(error)) }
                    }
                },
                onFailed = { error ->
                    if (active && request == generation) fail(messageFor(error))
                },
                onError = { message ->
                    if (active && request == generation) fail(message)
                },
            )
        } catch (error: Exception) { fail(messageFor(error)) }
    }

    fun stop() { active = false; clear() }

    private fun fail(message: String) { clear(); if (active) show(message, false) }

    private fun clear() {
        generation++
        connecting = false
        timeout?.cancel()
        timeout = null
        val old = transport
        transport = null
        // A disconnected SDK must not prevent failure feedback or a later retry.
        try { old?.close() } catch (_: Exception) { /* State is already invalidated. */ }
    }

    private fun messageFor(error: Throwable) = if (error is SecurityException) {
        "내비게이션 연결 권한을 확인할 수 없어요. 앱의 내비 권한 설정을 확인해 주세요."
    } else {
        "현대 내비게이션에 연결하지 못했어요. 내비 앱을 연 뒤 다시 눌러 주세요."
    }
}
