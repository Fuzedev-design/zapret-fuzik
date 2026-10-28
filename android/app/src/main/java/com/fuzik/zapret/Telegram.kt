package com.fuzik.zapret

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Приложение Telegram без root.
 *
 * Telegram подключается к своим серверам напрямую по IP, а провайдеры в РФ блокируют эти
 * адреса целиком — byedpi тут не поможет, до сервера просто не доходит ни один пакет.
 * Поэтому такие соединения мы перехватываем и отправляем тем же путём, что и веб-версия
 * Telegram: через WebSocket (wss://kwsN.web.telegram.org/apiws). Для дата-центров, до которых
 * так не достать, — через запасные адреса за Cloudflare. Переписка при этом остаётся
 * зашифрованной ключом самого Telegram: посредники видят только шифр.
 *
 * Приём и адреса взяты из TG WS Proxy (Flowseal/tg-ws-proxy, лицензия MIT) — его же
 * использует Windows-версия. Отличие: там прокси надо прописывать в Telegram, а у нас
 * соединения перехватывает VPN, и в Telegram ничего настраивать не нужно.
 */
object Telegram {

    /** Адрес, где отвечают kws2 и kws4 (остальные дата-центры там отвечают 302). */
    private const val WS_IP = "149.154.167.220"
    private const val CF_LIST_URL = "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"

    // Запасные адреса за Cloudflare в том же виде, что у TG WS Proxy (они там нарочно
    // записаны сдвигом букв, чтобы их не находили поиском). Свежий список берём с GitHub.
    private val CF_ENCODED = listOf(
        "virkgj.com", "vmmzovy.com", "mkuosckvso.com", "zaewayzmplad.com", "twdmbzcm.com",
        "awzwsldi.com", "clngqrflngqin.com", "tjacxbqtj.com", "bxaxtxmrw.com", "dmohrsgmohcrwb.com",
        "vwbmtmoi.com", "khgrre.com", "ulihssf.com", "tmhqsdqmfpmk.com", "xwuwoqbm.com",
        "orgcnunpj.com", "zhkuldz.com", "zypoljnslxa.com", "efabnxaowuzs.com", "zaftuzsftqdq.com",
    )

    @Volatile private var cfDomains: List<String> = CF_ENCODED.map(::decodeDomain)
    private val goodCf = ConcurrentHashMap<Int, String>()
    private val logged: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val rnd = SecureRandom()

    private fun decodeDomain(s: String): String {
        if (!s.endsWith(".com")) return s
        val p = s.removeSuffix(".com")
        val n = p.count { it.isLetter() }
        return p.map { c ->
            when (c) {
                in 'a'..'z' -> 'a' + Math.floorMod(c - 'a' - n, 26)
                in 'A'..'Z' -> 'A' + Math.floorMod(c - 'A' - n, 26)
                else -> c
            }
        }.joinToString("") + ".co.uk"
    }

    /** Обновляет список запасных адресов с GitHub; при ошибке остаётся встроенный. */
    fun refreshDomains() {
        try {
            val conn = URL(CF_LIST_URL).openConnection() as HttpsURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "zapret-fuzik")
            val list = conn.inputStream.bufferedReader().use { it.readLines() }
                .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                .map(::decodeDomain).filter { Regex("^[a-z0-9.-]+\\.[a-z]{2,}$").matches(it) }
            if (list.size >= 3) {
                cfDomains = list
                goodCf.clear()
            }
        } catch (e: Exception) {
            logOnce("cf-list", "Telegram: список запасных адресов не обновился (${e.javaClass.simpleName}), беру встроенный")
        }
    }

    fun logOnce(key: String, msg: String) {
        if (logged.add(key)) AppLog.add(msg)
    }

    fun resetLog() = logged.clear()

    // --- Адреса Telegram -------------------------------------------------------

    private class Net(val addr: ByteArray, val bits: Int)

    private val NETS = listOf(
        "149.154.160.0/20", "91.108.4.0/22", "91.108.8.0/22", "91.108.12.0/22", "91.108.16.0/22",
        "91.108.20.0/22", "91.108.56.0/22", "91.105.192.0/23", "95.161.64.0/20", "185.76.151.0/24",
        "2001:b28:f23c::/48", "2001:b28:f23d::/48", "2001:b28:f23f::/48", "2001:67c:4e8::/48", "2a0a:f280::/32",
    ).map {
        val (a, b) = it.split('/')
        Net(InetAddress.getByName(a).address, b.toInt())
    }

    fun isTelegram(ip: ByteArray): Boolean = NETS.any { n ->
        n.addr.size == ip.size && (0 until n.bits).all { i ->
            (ip[i / 8].toInt() shr (7 - i % 8) and 1) == (n.addr[i / 8].toInt() shr (7 - i % 8) and 1)
        }
    }

    /**
     * Номер дата-центра по адресу. Telegram для Android пишет номер в начало соединения
     * только при работе через прокси, поэтому узнаём его по адресу (как в tgnet и в конфиге
     * Telegram). 0 — адрес Telegram, но дата-центр неизвестен.
     */
    fun dcByAddress(ip: ByteArray): Int {
        if (ip.size == 16) {
            val g4 = ((ip[6].toInt() and 0xff) shl 8) or (ip[7].toInt() and 0xff)
            return if (g4 and 0xfff0 == 0xf000 && g4 and 0xf in 1..5) g4 and 0xf else 0
        }
        val a = ip.map { it.toInt() and 0xff }
        return when {
            a[0] == 149 && a[1] == 154 -> when (a[2]) {
                175 -> if (a[3] in 100..119) 3 else 1
                167 -> if (a[3] in 90..99) 4 else 2
                164, 165, 166 -> 4
                171 -> 5
                else -> 0
            }
            a[0] == 91 && a[1] == 108 && a[2] in 56..59 -> 5
            a[0] == 95 && a[1] == 161 -> 2
            a[0] == 91 && a[1] == 105 && a[2] in 192..193 -> 203
            else -> 0
        }
    }

    // --- Шифрование начала соединения (obfuscated2) ----------------------------------

    private val ZERO_64 = ByteArray(64)

    internal fun ctr(key: ByteArray, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }

    /** Ключи одной стороны: чем шифровать отправку и чем расшифровывать приём. */
    internal class Keys(val send: Cipher, val recv: Cipher)

    /** Ключи из 64 байт начала соединения (без секрета прокси — как при прямом подключении). */
    private fun keysOf(init: ByteArray): Keys {
        val r = init.copyOfRange(8, 56).reversedArray()
        return Keys(
            ctr(init.copyOfRange(8, 40), init.copyOfRange(40, 56)),
            ctr(r.copyOfRange(0, 32), r.copyOfRange(32, 48)),
        )
    }

    /** Разбор начала соединения от Telegram: тип протокола или null, если это не MTProto. */
    internal class ClientInit(val tag: Int, val fromClient: Cipher, val toClient: Cipher)

    internal fun parseClientInit(init: ByteArray): ClientInit? {
        val k = keysOf(init)
        val plain = k.send.update(init)  // заодно шифр «от клиента» проходит первые 64 байта
        val t = plain[56].toInt() and 0xff
        if (t !in intArrayOf(0xef, 0xee, 0xdd) || plain[57] != plain[56] || plain[58] != plain[56] || plain[59] != plain[56]) return null
        return ClientInit(t, k.send, k.recv)
    }

    /** Наше начало соединения к серверу: с правильным номером дата-центра. Ключи — для сервера. */
    internal fun relayInit(tag: Int, dcIdx: Int): Pair<ByteArray, Keys> {
        val b = ByteArray(64)
        while (true) {
            rnd.nextBytes(b)
            val first = (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) or
                ((b[2].toInt() and 0xff) shl 16) or ((b[3].toInt() and 0xff) shl 24)
            if (b[0].toInt() and 0xff == 0xef) continue
            if (first in intArrayOf(0x44414548, 0x54534f50, 0x20544547, 0x4954504f, 0xeeeeeeee.toInt(), 0xdddddddd.toInt(), 0x02010316)) continue
            if (b[4].toInt() == 0 && b[5].toInt() == 0 && b[6].toInt() == 0 && b[7].toInt() == 0) continue
            break
        }
        for (i in 56..59) b[i] = tag.toByte()
        b[60] = dcIdx.toByte()
        b[61] = (dcIdx shr 8).toByte()
        val k = keysOf(b)
        val enc = k.send.update(b)  // шифр отправки сдвинут на 64 байта, как и нужно
        System.arraycopy(enc, 56, b, 56, 8)
        return b to k
    }

    // --- Пути к серверу ----------------------------------------------------------------

    /** Открывает WebSocket к дата-центру: сначала прямой адрес, затем запасные за Cloudflare. */
    fun open(dc: Int): TgWs? {
        val n = if (dc == 203) 2 else dc
        if (n == 2 || n == 4) {
            try {
                return TgWs.connect(WS_IP, "kws$n.web.telegram.org").also {
                    logOnce("ok-$n-direct", "Telegram: DC$n → WebSocket kws$n.web.telegram.org")
                }
            } catch (e: Exception) {
                logOnce("fail-$n-direct", "Telegram: DC$n напрямую не открылся (${reason(e)}), пробую запасные адреса")
            }
        }
        val good = goodCf[n]
        val order = listOfNotNull(good) + cfDomains.filter { it != good }.shuffled()
        for (d in order.take(6)) {
            try {
                val ws = TgWs.connect("kws$n.$d", "kws$n.$d")
                goodCf[n] = d
                logOnce("ok-$n-$d", "Telegram: DC$n → запасной адрес ${d.take(3)}***")
                return ws
            } catch (e: Exception) {
                goodCf.remove(n, d)
            }
        }
        logOnce("fail-$n", "Telegram: DC$n — ни один путь не открылся")
        return null
    }

    private fun reason(e: Exception) = e.message?.take(60) ?: e.javaClass.simpleName

    /**
     * Проверка для экрана «Проверка»: открываем путь к дата-центру и просим у сервера
     * первый ответ протокола (req_pq). Возвращает время в мс.
     */
    fun probe(dc: Int): Long {
        val t0 = System.nanoTime()
        val ws = open(dc) ?: throw IOException("нет пути")
        ws.use {
            val (init, k) = relayInit(0xee, dc)
            ws.send(init)
            val nonce = ByteArray(16).also { rnd.nextBytes(it) }
            val req = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(40).putLong(0).putLong((System.currentTimeMillis() / 1000L) shl 32)
                .putInt(20).putInt(0xbe7e8ef1.toInt()).put(nonce).array()
            ws.send(k.send.update(req))
            val resp = k.recv.update(ws.recv() ?: throw EOFException("сервер закрыл соединение"))
            if (resp.size < 28 || java.nio.ByteBuffer.wrap(resp).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(24) != 0x05162463) {
                throw IOException("неожиданный ответ")
            }
        }
        return (System.nanoTime() - t0) / 1_000_000
    }

    // --- Мост между Telegram и WebSocket --------------------------------------------------

    /**
     * Ведёт соединение Telegram через WebSocket. [init] — первые 64 байта от Telegram.
     * Возвращает false, если путь не открылся (тогда соединение можно отдать byedpi).
     */
    internal fun bridge(client: Socket, cin: InputStream, cout: OutputStream, ci: ClientInit, dc: Int): Boolean {
        val ws = open(dc) ?: return false
        val (init, tg) = relayInit(ci.tag, dc)
        try {
            ws.send(init)
        } catch (e: IOException) {
            ws.close()
            return false
        }
        val up = spawn("tg-up") {
            val splitter = Splitter(ci.tag)
            val buf = ByteArray(64 * 1024)
            try {
                while (true) {
                    val n = cin.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    val plain = ci.fromClient.update(buf, 0, n) ?: continue
                    // сервер ждёт каждый пакет MTProto в отдельном кадре WebSocket
                    for (p in splitter.feed(plain)) ws.send(tg.send.update(p))
                }
                splitter.rest()?.let { ws.send(tg.send.update(it)) }
            } catch (e: IOException) {
                // соединение закрыто — это нормально
            } finally {
                ws.close()
            }
        }
        try {
            while (true) {
                val data = ws.recv() ?: break
                if (data.isEmpty()) continue
                cout.write(ci.toClient.update(tg.recv.update(data)))
            }
        } catch (e: IOException) {
            // закрыто одной из сторон
        } finally {
            ws.close()
            closeQuietly(client)
            up.join(2000)
        }
        return true
    }

    /** Режет поток на пакеты MTProto (abridged — 0xef, intermediate — 0xee и 0xdd). */
    private class Splitter(private val tag: Int) {
        private var buf = ByteArray(0)
        private var len = 0
        private var disabled = false

        fun feed(data: ByteArray): List<ByteArray> {
            if (disabled) return listOf(data)
            if (len + data.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + data.size))
            System.arraycopy(data, 0, buf, len, data.size)
            len += data.size
            val out = ArrayList<ByteArray>(2)
            var off = 0
            while (off < len) {
                val p = packetLen(off, len - off) ?: break
                if (p <= 0 || p > 16 * 1024 * 1024) {
                    // непонятная длина — дальше шлём как есть, без нарезки
                    out += buf.copyOfRange(off, len)
                    off = len
                    disabled = true
                    break
                }
                out += buf.copyOfRange(off, off + p)
                off += p
            }
            if (off > 0) {
                System.arraycopy(buf, off, buf, 0, len - off)
                len -= off
            }
            return out
        }

        fun rest(): ByteArray? = if (len > 0) buf.copyOf(len).also { len = 0 } else null

        private fun u(i: Int) = buf[i].toInt() and 0xff

        /** Длина пакета вместе с заголовком; null — пакет пришёл ещё не целиком, 0 — ошибка. */
        private fun packetLen(off: Int, avail: Int): Int? {
            val head: Int
            val body: Int
            if (tag == 0xef) {
                val first = u(off)
                if (first == 0x7f || first == 0xff) {
                    if (avail < 4) return null
                    head = 4
                    body = (u(off + 1) or (u(off + 2) shl 8) or (u(off + 3) shl 16)) * 4
                } else {
                    head = 1
                    body = (first and 0x7f) * 4  // старший бит — просьба о быстром подтверждении
                }
            } else {
                if (avail < 4) return null
                head = 4
                body = (u(off) or (u(off + 1) shl 8) or (u(off + 2) shl 16) or (u(off + 3) shl 24)) and 0x7fffffff
            }
            if (body == 0) return 0
            return if (avail >= head + body) head + body else null
        }
    }
}

/** Простой клиент WebSocket поверх TLS — только то, что нужно для Telegram. */
class TgWs private constructor(private val sock: Socket, private val inp: DataInputStream, private val out: OutputStream) : Closeable {
    @Volatile private var closed = false
    private val maskRnd = java.util.Random()

    @Synchronized
    private fun writeFrame(op: Int, data: ByteArray) {
        val n = data.size
        val head = when {
            n < 126 -> 2
            n < 65536 -> 4
            else -> 10
        }
        val f = ByteArray(head + 4 + n)
        f[0] = (0x80 or op).toByte()
        when (head) {
            2 -> f[1] = (0x80 or n).toByte()
            4 -> { f[1] = (0x80 or 126).toByte(); f[2] = (n shr 8).toByte(); f[3] = n.toByte() }
            else -> { f[1] = (0x80 or 127).toByte(); for (i in 0..7) f[2 + i] = (n.toLong() shr (56 - 8 * i)).toByte() }
        }
        val m = ByteArray(4).also { maskRnd.nextBytes(it) }
        System.arraycopy(m, 0, f, head, 4)
        val base = head + 4
        for (i in 0 until n) f[base + i] = (data[i].toInt() xor m[i and 3].toInt()).toByte()
        out.write(f)
        out.flush()
    }

    fun send(data: ByteArray) {
        if (closed) throw IOException("WebSocket закрыт")
        writeFrame(0x2, data)
    }

    /** Следующее сообщение от сервера или null, если сервер закрыл соединение. */
    fun recv(): ByteArray? {
        var frag: java.io.ByteArrayOutputStream? = null
        while (!closed) {
            val b0 = inp.readUnsignedByte()
            val b1 = inp.readUnsignedByte()
            var n = (b1 and 0x7f).toLong()
            if (n == 126L) n = inp.readUnsignedShort().toLong() else if (n == 127L) n = inp.readLong()
            if (n < 0 || n > 16 * 1024 * 1024) throw IOException("слишком большой кадр")
            val mask = if (b1 and 0x80 != 0) ByteArray(4).also { inp.readFully(it) } else null
            val p = ByteArray(n.toInt())
            inp.readFully(p)
            if (mask != null) for (i in p.indices) p[i] = (p[i].toInt() xor mask[i and 3].toInt()).toByte()
            val fin = b0 and 0x80 != 0
            when (b0 and 0x0f) {
                0x8 -> {
                    try { writeFrame(0x8, p.copyOf(minOf(2, p.size))) } catch (e: IOException) { }
                    closed = true
                    return null
                }
                0x9 -> writeFrame(0xA, p)
                0xA -> {}
                0x0, 0x1, 0x2 -> {
                    if (fin && frag == null) return p
                    if (frag == null) frag = java.io.ByteArrayOutputStream()
                    frag.write(p)
                    if (fin) return frag.toByteArray()
                }
            }
        }
        return null
    }

    override fun close() {
        if (!closed) {
            closed = true
            try { writeFrame(0x8, ByteArray(0)) } catch (e: Exception) { }
        }
        closeQuietly(sock)
    }

    companion object {
        fun connect(host: String, domain: String, timeoutMs: Int = 6000): TgWs {
            val raw = Socket()
            try {
                raw.connect(InetSocketAddress(host, 443), timeoutMs)
                raw.tcpNoDelay = true
                raw.soTimeout = timeoutMs
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, domain, 443, true) as SSLSocket
                // проверка имени в сертификате: средствами TLS, а на Android ещё и системной проверкой
                // (в обычной Java системная проверка по умолчанию отклоняет всё)
                ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                ssl.startHandshake()
                val android = System.getProperty("java.vm.vendor").orEmpty().contains("Android", ignoreCase = true)
                if (android && !HttpsURLConnection.getDefaultHostnameVerifier().verify(domain, ssl.session)) {
                    throw SSLPeerUnverifiedException("сертификат не для $domain")
                }
                val out = ssl.outputStream
                // случайные 16 байт в base64: 21 любой знак, 22-й — с нулевыми младшими битами
                val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
                val r = java.util.Random()
                val key = (1..21).map { abc[r.nextInt(64)] }.joinToString("") + "AQgw"[r.nextInt(4)] + "=="
                out.write(("GET /apiws HTTP/1.1\r\nHost: $domain\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: binary\r\n\r\n").toByteArray())
                out.flush()
                val inp = DataInputStream(BufferedInputStream(ssl.inputStream, 64 * 1024))
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) {
                    val c = inp.read()
                    if (c < 0) throw EOFException("сервер закрыл соединение")
                    head.append(c.toChar())
                    if (head.length > 8192) throw IOException("слишком длинный ответ")
                }
                val status = head.toString().substringBefore("\r\n")
                if (status.split(' ').getOrNull(1) != "101") throw IOException(status)
                ssl.soTimeout = 0
                return TgWs(ssl, inp, out)
            } catch (e: Exception) {
                closeQuietly(raw)
                throw e
            }
        }
    }
}

internal fun closeQuietly(c: Closeable?) {
    try { c?.close() } catch (e: Exception) { }
}

/** Фоновый поток, в котором никакая ошибка не закроет приложение. */
internal fun spawn(name: String, body: () -> Unit): Thread =
    Thread(null, {
        try { body() } catch (e: Throwable) { android.util.Log.w("Zapret", "$name: $e") }
    }, name, 256 * 1024).apply { isDaemon = true; start() }
