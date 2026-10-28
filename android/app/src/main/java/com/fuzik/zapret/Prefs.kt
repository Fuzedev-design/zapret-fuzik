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

    /**
     * IPv6 через обход. Выключен по умолчанию: у большинства операторов IPv6 нет, а если
     * VPN его объявляет, приложения (YouTube, Telegram) упираются в нерабочий IPv6.
     * Без IPv6-маршрута Android сам блокирует IPv6, и приложения сразу идут по IPv4.
     */
    var ipv6: Boolean
        get() = sp.getBoolean("ipv6", false)
        set(v) = sp.edit().putBoolean("ipv6", v).apply()

    /**
     * Не пропускать QUIC (UDP на порт 443). Приложение YouTube тогда сразу переходит
     * на обычное TCP-соединение, где обход работает надёжнее.
     */
    var blockQuic: Boolean
        get() = sp.getBoolean("block_quic", true)
        set(v) = sp.edit().putBoolean("block_quic", v).apply()

    /**
     * Приложение Telegram через WebSocket (как TG WS Proxy). Провайдеры блокируют адреса
     * серверов Telegram целиком, и byedpi тут бессилен — нужен обходной путь.
     */
    var tgWs: Boolean
        get() = sp.getBoolean("tg_ws", true)
        set(v) = sp.edit().putBoolean("tg_ws", v).apply()

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
