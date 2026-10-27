package com.fuzik.zapret

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Журнал запуска обхода — его видно в «Настройках» и можно скопировать для разработчика. */
object AppLog {
    private const val MAX = 200
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    val lines = MutableStateFlow<List<String>>(emptyList())

    @Synchronized
    fun add(msg: String) {
        Log.i("Zapret", msg)
        lines.value = (lines.value + "[${fmt.format(Date())}] $msg").takeLast(MAX)
    }

    fun text(): String = lines.value.joinToString("\n")
}
