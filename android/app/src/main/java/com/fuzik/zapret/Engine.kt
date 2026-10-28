package com.fuzik.zapret

import android.content.Context
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/** Готовые стратегии byedpi. Аналог general (ALT…).bat из Windows-версии. */
data class Strategy(val name: String, val args: String)

// Поддельный SNI для стратегий с -n (как в ByeByeDPI)
private const val FAKE_SNI = "www.iana.org"

// -a1 (--udp-fake 1) в каждой стратегии: поддельный UDP-пакет перед настоящим.
// Без него провайдер режет QUIC (видео YouTube) и UDP-звонки Telegram и Discord.
// «Набор 1–14» — из списка автоподбора ByeByeDPI (romanvht/ByeByeDPI, proxytest_strategies.list).
val STRATEGIES = listOf(
    Strategy("Набор 1", "-f-200 -Qr -s3:5+sm -a1 -As -d1 -s4+sm -s8+s -f-300 -d6+s -a1 -At,r,s -o2 -f-30 -As -r5 -Mh -r6+s -f-250 -s2:7+s -s3:6+sm -a1 -At,r,s -s3:5+sm -s6+s -s7:9+s -q30+sm -a1"),
    Strategy("Набор 2", "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -S -a1 -As -d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -S -a1"),
    Strategy("Набор 3", "-q2 -s2 -s3+s -r3 -s4 -r4 -s5+s -r5+s -s6 -s7+s -r8 -s9+s -Qr -Mh,d,r -a1 -At,r -s2+s -r2 -d2 -s3 -r3 -r4 -s4 -d5+s -r5 -d6 -s7+s -d7 -a1"),
    Strategy("Набор 4", "-o1 -d1 -a1 -At,r,s -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s -S -a1 -As -s1 -d1 -s5+s -s10+s -s15+s -s20+s -S -a1"),
    Strategy("Набор 5", "-n $FAKE_SNI -Qr -f-204 -s1:5+sm -a1 -As -d1 -s3+s -s5+s -q7 -a1 -As -o2 -f-43 -a1 -As -r5 -Mh -s1:5+s -s3:7+sm -a1"),
    Strategy("Набор 6", "-n $FAKE_SNI -Qr -f-205 -a1 -As -s1:3+sm -a1 -As -s5:8+sm -a1 -As -d3 -q7 -o2 -f-43 -f-85 -f-165 -r5 -Mh -a1"),
    Strategy("Набор 7", "-d1+s -s50+s -a1 -As -f20 -r2+s -a1 -At -d2 -s1+s -s5+s -s10+s -s15+s -s25+s -s35+s -s50+s -s60+s -a1"),
    Strategy("Набор 8", "-o1 -a1 -At,r,s -f-1 -a1 -At,r,s -d1:11+sm -S -a1 -At,r,s -n $FAKE_SNI -Qr -f1 -d1:11+sm -s1:11+sm -S -a1"),
    Strategy("Набор 9", "-d1 -s1 -q1 -a1 -Ar -s5 -o1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1"),
    Strategy("Набор 10", "-f1+nm -t6 -a1 -As -n $FAKE_SNI -Qr -s1:6+sm -a1 -As -s5:12+sm -a1 -As -d3 -q7 -r6 -Mh -a1"),
    Strategy("Набор 11", "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1"),
    Strategy("Набор 12", "-o1 -a1 -At,r,s -f-1 -a1 -Ar,s -o1 -a1 -At -r1+s -f-1 -t6 -a1"),
    Strategy("Набор 13", "-f-1 -Qr -s1+sm -d3+s -s5+sm -o2 -a1 -As -r1+s -d8+s -a1"),
    Strategy("Набор 14", "-o1 -r-5+se -a1 -At,r,s -d1 -n $FAKE_SNI -Qr -f-1 -a1"),
    // Простые стратегии прошлых версий — тоже с -a1
    Strategy("Стандартная", "--disorder 1 --auto=torst --tlsrec 1+s -a1"),
    Strategy("Фейк TTL 8", "--fake -1 --ttl 8 --split 1+s --disorder 3+s -a1"),
    Strategy("Фейк + перестановка", "--fake -1 --ttl 6 --auto=torst --disorder 1 -a1"),
    Strategy("OOB", "--oob 1 --auto=torst --tlsrec 1+s -a1"),
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

    // Process.isAlive/destroyForcibly/waitFor(timeout) есть только с Android 8, а minSdk — 7
    val alive: Boolean
        get() = try {
            process.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }

    /** Останавливает byedpi и ждёт, пока он освободит порт (до ~2 секунд). */
    fun stop() {
        process.destroy()  // SIGTERM — byedpi сам закрывает соединения
        repeat(40) {
            if (!alive && !isListening(port)) return
            Thread.sleep(50)
        }
    }

    companion object {
        private const val TAG = "ByeDpi"

        fun exe(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libciadpi.so")

        /** Запускает byedpi и ждёт, пока он начнёт принимать подключения. */
        fun start(ctx: Context, port: Int, args: String, dropQuic: Boolean = false): ByeDpi {
            if (isListening(port)) throw IllegalStateException("Порт $port занят")
            val cmd = listOf(exe(ctx).absolutePath, "-i", "127.0.0.1", "-p", port.toString()) + splitArgs(args)
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            // наша правка byedpi (см. app/build.gradle.kts): не пересылать UDP на порт 443
            if (dropQuic) pb.environment()["ZAPRET_DROP_QUIC"] = "1"
            val process = pb.start()
            thread(isDaemon = true, name = "byedpi-log-$port") {
                // после остановки процесса чтение падает с IOException — это нормально,
                // но необработанная ошибка в потоке закрыла бы всё приложение
                try {
                    process.inputStream.bufferedReader().forEachLine { AppLog.add("byedpi:$port: $it") }
                } catch (e: Exception) {
                    Log.d(TAG, "log closed: ${e.message}")
                }
            }
            val dpi = ByeDpi(process, port)
            repeat(60) {
                if (!dpi.alive) throw IllegalStateException("byedpi завершился (код ${process.exitValue()}): проверьте параметры стратегии")
                if (isListening(port)) return dpi
                Thread.sleep(50)
            }
            dpi.stop()
            throw IllegalStateException("byedpi не запустился")
        }

        fun isListening(port: Int) = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }
            true
        } catch (e: Exception) {
            false
        }
    }
}
