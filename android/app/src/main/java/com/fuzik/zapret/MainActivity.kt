package com.fuzik.zapret

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) ZapretVpnService.start(this)
        else VpnState.error.value = "Без разрешения на VPN обход работать не может"
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startVpnFlow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        setContent { ZapretApp(onStart = ::requestStart, onStop = { ZapretVpnService.stop(this) }) }
    }

    /** Запуск обхода: сначала уведомления (Android 13+), затем разрешение на VPN. */
    private fun requestStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startVpnFlow()
        }
    }

    private fun startVpnFlow() {
        val ask = VpnService.prepare(this)
        if (ask != null) vpnPermission.launch(ask) else ZapretVpnService.start(this)
    }
}
