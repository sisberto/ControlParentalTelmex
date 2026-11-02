package com.telmex.controlparental

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("telmex_prefs", Context.MODE_PRIVATE)

    var routerIp: String
        get() = prefs.getString("router_ip", "192.168.1.254") ?: "192.168.1.254"
        set(value) = prefs.edit().putString("router_ip", value).apply()

    var routerUser: String
        get() = prefs.getString("router_user", "TELMEX") ?: "TELMEX"
        set(value) = prefs.edit().putString("router_user", value).apply()

    var routerPass: String
        get() = prefs.getString("router_pass", "") ?: ""
        set(value) = prefs.edit().putString("router_pass", value).apply()

    var isFilterActive: Boolean
        get() = prefs.getBoolean("is_filter_active", false)
        set(value) = prefs.edit().putBoolean("is_filter_active", value).apply()

    var timerEndTime: Long
        get() = prefs.getLong("timer_end_time", 0L)
        set(value) = prefs.edit().putLong("timer_end_time", value).apply()
}
