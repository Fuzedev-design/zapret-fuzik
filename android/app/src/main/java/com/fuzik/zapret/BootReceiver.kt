package com.fuzik.zapret

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/** Включает обход после перезагрузки или обновления, если включён автозапуск. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val prefs = Prefs(ctx)
        // prepare() == null — разрешение на VPN уже выдано, можно запускать без окна
        if (prefs.autostart && VpnService.prepare(ctx) == null) {
            ZapretVpnService.start(ctx)
        }
    }
}
