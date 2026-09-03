package com.example.pikminhelper

import android.content.Context

enum class RunMode {
    ECO, WATCH, RACE
}

class HelperPrefs(context: Context) {
    private val p = context.getSharedPreferences("helper", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = p.getBoolean("enabled", false)
        set(v) = p.edit().putBoolean("enabled", v).apply()

    var mode: RunMode
        get() = RunMode.valueOf(p.getString("mode", RunMode.ECO.name)!!)
        set(v) = p.edit().putString("mode", v.name).apply()

    var pauseLowBattery: Boolean
        get() = p.getBoolean("pauseLowBattery", true)
        set(v) = p.edit().putBoolean("pauseLowBattery", v).apply()

    var pauseWhenUserActive: Boolean
        get() = p.getBoolean("pauseWhenUserActive", true)
        set(v) = p.edit().putBoolean("pauseWhenUserActive", v).apply()

    var blockPaidTicket: Boolean
        get() = p.getBoolean("blockPaidTicket", true)
        set(v) = p.edit().putBoolean("blockPaidTicket", v).apply()
}
