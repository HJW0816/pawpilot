package com.estareng.pawpilot.voice

import android.content.Context
import android.content.Intent
import org.json.JSONObject

object GleoActions {
    const val FIND = "FIND_REST_STOP"
    const val STATUS = "GET_REST_STATUS"
    const val CANCEL = "CANCEL_REST_SEARCH"
    val actions = setOf(FIND, STATUS, CANCEL)
    fun reply(context: Context, action: String, status: String, message: String) {
        val response = JSONObject().put("functionName", action).put("status", status).put("message", message)
        context.sendBroadcast(Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE")
            .putExtra("packageName", context.packageName).putExtra("response", response.toString()))
    }
}
