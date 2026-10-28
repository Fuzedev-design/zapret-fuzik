package com.fuzik.zapret

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Те же адреса, что в проверке Windows-версии. */
// Видеосерверы YouTube (googlevideo) проверяются отдельно: сайт может открываться,
// а видео — нет, если провайдер режет именно их.
val CHECK_TARGETS = linkedMapOf(
    "Discord" to listOf("discord.com", "gateway.discord.gg", "cdn.discordapp.com", "updates.discord.com"),
    "YouTube" to listOf("www.youtube.com", "i.ytimg.com", "youtubei.googleapis.com", "yt3.ggpht.com"),
    "Видео YouTube" to listOf(
        "redirector.googlevideo.com", "manifest.googlevideo.com",
        "rr1---sn-4axm-n8vs.googlevideo.com", "rr4---sn-q4flrnsl.googlevideo.com", "rr2---sn-q4fl6ndl.googlevideo.com",
    ),
    // Настоящая загрузка: провайдеры часто пропускают первые 16–20 КБ и «замораживают»
    // соединение. Короткий запрос это не ловит, а видео из-за этого не играет.
    "Загрузка с YouTube" to listOf(
        "https://www.youtube.com/",
        "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
        "https://i.ytimg.com/vi/9bZkp7q19f0/maxresdefault.jpg",
    ),
    "Telegram" to listOf("telegram.org", "web.telegram.org", "core.telegram.org", "t.me"),
    // Серверы самого приложения Telegram (5 дата-центров) — по пути через WebSocket.
    // От стратегии byedpi не зависит, поэтому в подборе не участвует.
    "Приложение Telegram" to listOf("tg:2", "tg:4", "tg:1", "tg:3", "tg:5"),
    "Google и Cloudflare" to listOf("www.google.com", "www.gstatic.com", "www.cloudflare.com", "cdnjs.cloudflare.com"),
)
val STRATEGY_SERVICES = listOf("Discord", "YouTube", "Видео YouTube", "Загрузка с YouTube", "Telegram", "Google и Cloudflare")

/** Сколько данных нужно получить, чтобы считать загрузку рабочей (с запасом над 16–20 КБ). */
private const val DOWNLOAD_BYTES = 64 * 1024

enum class Probe(val text: String) {
    OK("доступен"), TIMEOUT("нет ответа"), RESET("соединение сброшено"),
    DNS("адрес не найден (DNS)"), CERT("подмена сертификата"), ERROR("ошибка"),
    STALL("загрузка замирает на 16–20 КБ")
}

data class ProbeResult(val host: String, val status: Probe, val ms: Long?)
data class StrategyScore(val strategy: Strategy, val ok: Int, val total: Int, val ms: Long?, val downloadsOk: Int = 0)

object Checker {

    /**
     * HTTPS-проверка напрямую или через byedpi на указанном порту.
     * Цель «хост» — короткий запрос; цель «https://…» — настоящая загрузка от 64 КБ.
     */
    fun probe(target: String, socksPort: Int?, timeoutMs: Int = 5000): ProbeResult {
        if (target.startsWith("https://")) return download(target, socksPort, timeoutMs)
        if (target.startsWith("tg:")) {
            val dc = target.removePrefix("tg:").toInt()
            return try {
                ProbeResult("DC$dc", Probe.OK, Telegram.probe(dc))
            } catch (e: Exception) {
                ProbeResult("DC$dc", Probe.TIMEOUT, null)
            }
        }
        val host = target
        val t0 = System.nanoTime()
        return try {
            val raw = if (socksPort != null) Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            else Socket()
            raw.use { s ->
                val addr = if (socksPort != null) InetSocketAddress.createUnresolved(host, 443) else InetSocketAddress(host, 443)
                s.connect(addr, timeoutMs)
                s.soTimeout = timeoutMs
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(s, host, 443, true) as SSLSocket
                tls.use {
                    it.startHandshake()
                    it.outputStream.write("HEAD / HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n".toByteArray())
                    val buf = ByteArray(5)
                    val n = it.inputStream.read(buf)
                    val ok = n == 5 && String(buf) == "HTTP/"
                    ProbeResult(host, if (ok) Probe.OK else Probe.ERROR, (System.nanoTime() - t0) / 1_000_000)
                }
            }
        } catch (e: UnknownHostException) {
            ProbeResult(host, Probe.DNS, null)
        } catch (e: SSLHandshakeException) {
            val cert = generateSequence(e as Throwable) { it.cause }.any { it is CertificateException }
            ProbeResult(host, if (cert) Probe.CERT else Probe.RESET, null)
        } catch (e: SocketTimeoutException) {
            ProbeResult(host, Probe.TIMEOUT, null)
        } catch (e: java.net.SocketException) {
            ProbeResult(host, Probe.RESET, null)
        } catch (e: Exception) {
            ProbeResult(host, Probe.ERROR, null)
        }
    }

    private fun download(url: String, socksPort: Int?, timeoutMs: Int): ProbeResult {
        val host = url.removePrefix("https://").substringBefore('/')
        val path = "/" + url.removePrefix("https://").substringAfter('/', "")
        val t0 = System.nanoTime()
        var received = 0
        return try {
            val raw = if (socksPort != null) Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            else Socket()
            raw.use { s ->
                val addr = if (socksPort != null) InetSocketAddress.createUnresolved(host, 443) else InetSocketAddress(host, 443)
                s.connect(addr, timeoutMs)
                s.soTimeout = timeoutMs
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(s, host, 443, true) as SSLSocket
                tls.use {
                    it.startHandshake()
                    it.outputStream.write(("GET $path HTTP/1.1\r\nHost: $host\r\n" +
                        "User-Agent: Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36\r\n" +
                        "Accept-Encoding: identity\r\nConnection: close\r\n\r\n").toByteArray())
                    val input = it.inputStream
                    val buf = ByteArray(16 * 1024)
                    val head = StringBuilder()
                    var need = DOWNLOAD_BYTES
                    var bodyStart = -1
                    val deadline = System.nanoTime() + 10_000_000_000L
                    while (System.nanoTime() < deadline) {
                        val n = input.read(buf)
                        if (n < 0) break
                        received += n
                        if (bodyStart < 0) {
                            head.append(String(buf, 0, n, Charsets.ISO_8859_1))
                            val end = head.indexOf("\r\n\r\n")
                            if (end >= 0) {
                                bodyStart = end + 4
                                val len = Regex("(?i)content-length:\\s*(\\d+)").find(head.substring(0, end))
                                    ?.groupValues?.get(1)?.toIntOrNull()
                                // файл меньше 64 КБ — достаточно получить его целиком
                                if (len != null) need = minOf(need, len)
                            }
                        }
                        if (bodyStart >= 0 && received - bodyStart >= need) {
                            return@use ProbeResult(url, Probe.OK, (System.nanoTime() - t0) / 1_000_000)
                        }
                    }
                    ProbeResult(url, if (received > 0) Probe.STALL else Probe.TIMEOUT, null)
                }
            }
        } catch (e: SocketTimeoutException) {
            ProbeResult(url, if (received > 0) Probe.STALL else Probe.TIMEOUT, null)
        } catch (e: UnknownHostException) {
            ProbeResult(url, Probe.DNS, null)
        } catch (e: SSLHandshakeException) {
            val cert = generateSequence(e as Throwable) { it.cause }.any { it is CertificateException }
            ProbeResult(url, if (cert) Probe.CERT else Probe.RESET, null)
        } catch (e: java.net.SocketException) {
            ProbeResult(url, if (received > 0) Probe.STALL else Probe.RESET, null)
        } catch (e: Exception) {
            ProbeResult(url, Probe.ERROR, null)
        }
    }

    suspend fun checkServices(
        services: List<String> = CHECK_TARGETS.keys.toList(),
        socksPort: Int?,
        timeoutMs: Int = 5000,
    ): Map<String, List<ProbeResult>> = withContext(Dispatchers.IO) {
        coroutineScope {
            services.associateWith { s ->
                CHECK_TARGETS.getValue(s).map { h -> async { probe(h, socksPort, timeoutMs) } }
            }.mapValues { (_, jobs) -> jobs.awaitAll() }
        }
    }

    fun summarize(results: Map<String, List<ProbeResult>>): Triple<Int, Int, Long?> {
        val all = results.values.flatten()
        val ok = all.filter { it.status == Probe.OK }.mapNotNull { it.ms }
        return Triple(ok.size, all.size, if (ok.isEmpty()) null else ok.sum() / ok.size)
    }

    private suspend fun testStrategy(ctx: Context, st: Strategy, port: Int): StrategyScore {
        val dpi = try {
            ByeDpi.start(ctx, port, st.args)
        } catch (e: Exception) {
            AppLog.add("Подбор: «${st.name}» не запустилась — ${e.message}")
            return StrategyScore(st, 0, 0, null)
        }
        return try {
            val results = checkServices(STRATEGY_SERVICES, port, 4000)
            val (ok, total, ms) = summarize(results)
            val downloads = results["Загрузка с YouTube"].orEmpty().count { it.status == Probe.OK }
            StrategyScore(st, ok, total, ms, downloads)
        } catch (e: Exception) {
            StrategyScore(st, 0, 0, null)
        } finally {
            dpi.stop()
        }
    }

    /**
     * Перебирает стратегии на отдельном порту — работающий обход при этом не прерывается.
     * Лучшие идут первыми.
     */
    suspend fun findBest(
        ctx: Context,
        onProgress: (index: Int, total: Int, name: String) -> Unit,
        isCancelled: () -> Boolean,
    ): List<StrategyScore> = withContext(Dispatchers.IO) {
        val scores = mutableListOf<StrategyScore>()
        // по 4 стратегии одновременно, у каждой свой порт — так подбор в разы быстрее
        val batch = 4
        for (start in STRATEGIES.indices step batch) {
            if (isCancelled()) break
            val group = STRATEGIES.subList(start, minOf(start + batch, STRATEGIES.size))
            // состояние интерфейса меняем только из главного потока
            withContext(Dispatchers.Main) {
                onProgress(start, STRATEGIES.size, group.joinToString(", ") { it.name })
            }
            scores += coroutineScope {
                group.mapIndexed { j, st -> async { testStrategy(ctx, st, TEST_PORT + start + j) } }.awaitAll()
            }
        }
        scores.sortedWith(compareBy({ -it.downloadsOk }, { -it.ok }, { it.ms ?: Long.MAX_VALUE }))
    }
}
