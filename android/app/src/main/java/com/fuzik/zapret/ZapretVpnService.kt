package com.fuzik.zapret

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors

/** Состояние обхода для интерфейса. */
object VpnState {
    val running = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val strategy = MutableStateFlow<String?>(null)
    val isRunning: StateFlow<Boolean> get() = running
}

/**
 * Локальный VPN: весь трафик телефона идёт в hev-socks5-tunnel, оттуда — в byedpi,
 * который обходит DPI и отправляет данные дальше. Сторонних серверов нет.
 */
class ZapretVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var dpi: ByeDpi? = null

    // Запуск и остановка идут в отдельном потоке: на главном Android запрещает сетевые
    // подключения, а мы проверяем, что byedpi начал слушать порт. Один поток — по очереди.
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            worker.execute { stopVpn() }
            return START_NOT_STICKY
        }
        val prefs = Prefs(this)
        try {
            // уведомление — сразу: после startForegroundService у службы всего несколько секунд
            startInForeground(prefs.strategy)
        } catch (e: Throwable) {
            worker.execute { fail("Не удалось показать уведомление службы", e) }
            return START_NOT_STICKY
        }
        worker.execute { startVpn(prefs) }
        return START_STICKY
    }

    override fun onRevoke() {
        worker.execute { stopVpn() }  // пользователь отключил VPN в настройках или включил другой VPN
    }

    override fun onDestroy() {
        worker.execute { stopVpn(keepService = true) }
        worker.shutdown()
        super.onDestroy()
    }

    private fun startVpn(prefs: Prefs) {
        AppLog.add("Запуск: стратегия «${prefs.strategy}», Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        try {
            stopEngines()
            val args = prefs.currentArgs()
            AppLog.add("byedpi: $args")
            dpi = ByeDpi.start(this, MAIN_PORT, args)
            AppLog.add("byedpi слушает порт $MAIN_PORT")

            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(MTU)
                .addAddress("10.10.10.10", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(prefs.dns)
            if (prefs.ipv6) builder.addAddress("fd00::1", 128).addRoute("::", 0)
            builder
                // byedpi работает внутри приложения — его трафик идёт мимо VPN, иначе была бы петля
                .addDisallowedApplication(packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
            val fd = builder.establish() ?: throw IllegalStateException("Нет разрешения на VPN — нажмите «Запустить» ещё раз")
            tun = fd
            AppLog.add("VPN-интерфейс создан (fd ${fd.fd}), DNS ${prefs.dns}, IPv6 ${if (prefs.ipv6) "вкл" else "выкл"}")

            val config = File(filesDir, "hev.yml")
            config.writeText(
                """
                tunnel:
                  mtu: $MTU
                  ipv4: 10.10.10.10
                  ${if (prefs.ipv6) "ipv6: 'fd00::1'" else ""}
                socks5:
                  port: $MAIN_PORT
                  address: 127.0.0.1
                  udp: 'udp'
                misc:
                  task-stack-size: 81920
                  log-level: warn
                """.trimIndent()
            )
            if (!TProxyService.TProxyStartService(config.absolutePath, fd.fd)) {
                throw IllegalStateException("Туннель не запустился")
            }
            Thread.sleep(700)  // туннель стартует в своём потоке — убеждаемся, что он не упал сразу
            if (!TProxyService.TProxyIsRunning()) throw IllegalStateException("Туннель остановился сразу после запуска")
            if (dpi?.alive != true) throw IllegalStateException("byedpi остановился сразу после запуска")
            AppLog.add("Туннель запущен, обход работает")
            VpnState.error.value = null
            VpnState.strategy.value = prefs.strategy
            VpnState.running.value = true
        } catch (e: Throwable) {
            // Throwable, а не Exception: ошибки загрузки библиотек (UnsatisfiedLinkError) — это Error
            fail(e.message ?: "Не удалось запустить обход", e)
        }
    }

    private fun fail(message: String, e: Throwable) {
        Log.e(TAG, message, e)
        AppLog.add("ОШИБКА: $message (${e.javaClass.simpleName})")
        VpnState.error.value = message
        stopVpn()
    }

    private fun stopEngines() {
        if (tun != null && TProxyService.TProxyIsRunning()) TProxyService.TProxyStopService()
        tun?.close()
        tun = null
        dpi?.stop()
        dpi = null
    }

    private fun stopVpn(keepService: Boolean = false) {
        try {
            stopEngines()
        } catch (e: Throwable) {
            Log.w(TAG, "stop", e)
        }
        VpnState.running.value = false
        VpnState.strategy.value = null
        if (!keepService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startInForeground(strategy: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Обход блокировок", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ZapretVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = builder
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Обход включён")
            .setContentText("Стратегия «$strategy»")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val TAG = "ZapretVpn"
        private const val CHANNEL = "zapret"
        private const val NOTIFICATION_ID = 1
        private const val MTU = 8500
        const val ACTION_STOP = "com.fuzik.zapret.STOP"

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, ZapretVpnService::class.java))

        fun stop(ctx: Context) =
            ctx.startService(Intent(ctx, ZapretVpnService::class.java).setAction(ACTION_STOP))
    }
}
