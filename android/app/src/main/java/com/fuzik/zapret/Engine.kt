package com.fuzik.zapret

import android.content.Context
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/** Готовые стратегии byedpi. Аналог general (ALT…).bat из Windows-версии. */
data class Strategy(val name: String, val args: String)

val STRATEGIES = listOf(
    Strategy("Стандартная", "--disorder 1 --auto=torst --tlsrec 1+s"),
    Strategy("Фейк TTL 8", "--fake -1 --ttl 8"),
    Strategy("Фейк + перестановка", "--fake -1 --ttl 6 --auto=torst --disorder 1"),
    Strategy("Разрез по SNI", "--split 1+s --disorder 3+s --auto=torst --tlsrec 1+s"),
    Strategy("OOB", "--oob 1 --auto=torst --tlsrec 1+s"),
    Strategy("Disoob", "--disoob 1 --auto=torst --tlsrec 3+s"),
    Strategy("TLS-записи", "--tlsrec 1+s"),
    Strategy("Простая перестановка", "--disorder 1"),
)

const val CUSTOM_STRATEGY = "Своя"
const val MAIN_PORT = 1080
const val TEST_PORT = 1081

/** Разбивает строку параметров на аргументы с учётом кавычек. */
fun splitArgs(s: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quote: Char? = null
    for (c in s) {
        when {
            quote != null && c == quote -> quote = null
            quote == null && (c == '"' || c == '\'') -> quote = c
            quote == null && c.isWhitespace() -> if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
            else -> cur.append(c)
        }
    }
    if (cur.isNotEmpty()) out += cur.toString()
    return out
}

/** Процесс byedpi (ciadpi) — локальный SOCKS5-прокси, который «портит» пакеты для обхода DPI. */
class ByeDpi private constructor(private val process: Process, val port: Int) {

    val alive: Boolean get() = process.isAlive

    fun stop() {
        process.destroy()  // SIGTERM — byedpi сам закрывает соединения
        thread(isDaemon = true) {
            Thread.sleep(1500)
            if (process.isAlive) process.destroyForcibly()
        }
    }

    companion object {
        private const val TAG = "ByeDpi"

        fun exe(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libciadpi.so")

        /** Запускает byedpi и ждёт, пока он начнёт принимать подключения. */
        fun start(ctx: Context, port: Int, args: String): ByeDpi {
            val cmd = listOf(exe(ctx).absolutePath, "-i", "127.0.0.1", "-p", port.toString()) + splitArgs(args)
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            thread(isDaemon = true, name = "byedpi-log-$port") {
                process.inputStream.bufferedReader().forEachLine { Log.d(TAG, it) }
            }
            val dpi = ByeDpi(process, port)
            repeat(40) {
                if (!process.isAlive) throw IllegalStateException("byedpi завершился: проверьте параметры стратегии")
                if (isListening(port)) return dpi
                Thread.sleep(50)
            }
            dpi.stop()
            throw IllegalStateException("byedpi не запустился")
        }

        private fun isListening(port: Int) = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }
            true
        } catch (e: Exception) {
            false
        }
    }
}
