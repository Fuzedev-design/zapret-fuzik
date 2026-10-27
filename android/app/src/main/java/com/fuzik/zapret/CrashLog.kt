package com.fuzik.zapret

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Сохраняет текст ошибки, из-за которой приложение закрылось,
 * чтобы показать его при следующем запуске и его можно было прислать разработчику.
 */
object CrashLog {
    private var installed = false

    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file(app).writeText(
                    "Запрет от Фузика ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "Поток: ${thread.name}\n\n$sw"
                )
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Текст последнего падения (и удаляет его), либо null. */
    fun take(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        return f.readText().also { f.delete() }
    }
}
