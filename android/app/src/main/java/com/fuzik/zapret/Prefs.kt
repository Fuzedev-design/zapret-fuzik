package com.fuzik.zapret

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("zapret", Context.MODE_PRIVATE)

    var strategy: String
        get() = sp.getString("strategy", STRATEGIES.first().name)!!
        set(v) = sp.edit().putString("strategy", v).apply()

    var customArgs: String
        get() = sp.getString("custom_args", STRATEGIES.first().args)!!
        set(v) = sp.edit().putString("custom_args", v).apply()

    var dns: String
        get() = sp.getString("dns", "8.8.8.8")!!
        set(v) = sp.edit().putString("dns", v).apply()

    var autostart: Boolean
        get() = sp.getBoolean("autostart", false)
        set(v) = sp.edit().putBoolean("autostart", v).apply()

    var checkUpdates: Boolean
        get() = sp.getBoolean("check_updates", true)
        set(v) = sp.edit().putBoolean("check_updates", v).apply()

    /** Параметры byedpi для выбранной стратегии. */
    fun currentArgs(): String =
        if (strategy == CUSTOM_STRATEGY) customArgs
        else STRATEGIES.firstOrNull { it.name == strategy }?.args ?: STRATEGIES.first().args
}
