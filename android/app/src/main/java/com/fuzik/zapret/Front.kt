package com.fuzik.zapret

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Распределитель между туннелем и byedpi (локальный SOCKS5-прокси).
 * Соединения с серверами Telegram ведёт через WebSocket (см. [Telegram]),
 * всё остальное без изменений передаёт byedpi на [dpiPort].
 */
class Front private constructor(private val server: ServerSocket, private val dpiPort: Int) {

    @Volatile private var closed = false
    private val conns: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())

    private fun acceptLoop() {
        while (!closed) {
            val s = try {
                server.accept()
            } catch (e: IOException) {
                break
            }
            conns += s
            spawn("front") {
                try {
                    handle(s)
                } catch (e: IOException) {
                    // клиент или сервер закрыли соединение
                } finally {
                    closeQuietly(s)
                    conns -= s
                }
            }
        }
    }

    fun stop() {
        closed = true
        closeQuietly(server)
        conns.toList().forEach { closeQuietly(it) }
    }

    private fun handle(s: Socket) {
        s.tcpNoDelay = true
        s.soTimeout = 15000
        val inp = DataInputStream(s.getInputStream())
        val out = s.getOutputStream()

        // приветствие SOCKS5: отвечаем «без пароля»
        if (inp.readUnsignedByte() != 5) return
        inp.readFully(ByteArray(inp.readUnsignedByte()))
        out.write(byteArrayOf(5, 0))

        // запрос: VER CMD RSV ATYP адрес порт — сохраняем целиком, чтобы повторить для byedpi
        val head = ByteArray(4)
        inp.readFully(head)
        val addr = when (head[3].toInt()) {
            1 -> ByteArray(4)
            4 -> ByteArray(16)
            3 -> ByteArray(inp.readUnsignedByte())
            else -> return
        }
        inp.readFully(addr)
        val port = ByteArray(2)
        inp.readFully(port)
        val request = head + (if (head[3].toInt() == 3) byteArrayOf(addr.size.toByte()) else ByteArray(0)) + addr + port

        if (head[1].toInt() == 1 && head[3].toInt() != 3 && Telegram.isTelegram(addr)) {
            telegram(s, inp, out, request, addr)
            return
        }
        val up = openDpi(request, replyTo = out) ?: return
        s.soTimeout = 0
        pipe(s, inp, up)
    }

    /** Соединение с byedpi с тем же запросом. Ответ byedpi уходит клиенту, если [replyTo] задан. */
    private fun openDpi(request: ByteArray, replyTo: OutputStream?): Socket? {
        val up = Socket()
        try {
            up.connect(InetSocketAddress("127.0.0.1", dpiPort), 3000)
            up.tcpNoDelay = true
            up.soTimeout = 15000
            val o = up.getOutputStream()
            val i = DataInputStream(up.getInputStream())
            o.write(byteArrayOf(5, 1, 0))
            i.readFully(ByteArray(2))
            o.write(request)
            if (replyTo == null) {
                // клиенту мы уже ответили сами — ответ byedpi читаем и проверяем
                val r = ByteArray(4)
                i.readFully(r)
                i.readFully(ByteArray(when (r[3].toInt()) { 1 -> 4; 4 -> 16; else -> i.readUnsignedByte() } + 2))
                if (r[1].toInt() != 0) throw IOException("byedpi: код ${r[1]}")
            }
            up.soTimeout = 0
            conns += up
            return up
        } catch (e: IOException) {
            closeQuietly(up)
            return null
        }
    }

    private fun telegram(s: Socket, inp: InputStream, out: OutputStream, request: ByteArray, addr: ByteArray) {
        // отвечаем «подключено» сразу: Telegram пришлёт начало соединения, по нему всё и решим
        out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        val init = ByteArray(64)
        var got = 0
        try {
            while (got < 64) {
                val n = inp.read(init, got, 64 - got)
                if (n < 0) break
                got += n
            }
        } catch (e: SocketTimeoutException) {
            // мало данных — значит, это не MTProto
        }
        val dc = Telegram.dcByAddress(addr)
        val ci = if (got == 64) Telegram.parseClientInit(init) else null
        if (ci != null && dc != 0) {
            s.soTimeout = 0
            if (Telegram.bridge(s, inp, out, ci, dc)) return
        } else if (ci != null) {
            Telegram.logOnce("dc-${addr.joinToString(".") { (it.toInt() and 0xff).toString() }}",
                "Telegram: неизвестный дата-центр для адреса ${java.net.InetAddress.getByAddress(addr).hostAddress}, отдаю byedpi")
        }
        // не вышло — пусть byedpi попробует напрямую, с уже полученными байтами
        val up = openDpi(request, replyTo = null) ?: return
        up.getOutputStream().write(init, 0, got)
        s.soTimeout = 0
        pipe(s, inp, up)
    }

    /** Гонит данные в обе стороны, пока одна из сторон не закроет соединение. */
    private fun pipe(a: Socket, ain: InputStream, b: Socket) {
        val t = spawn("pipe") { copy(b.getInputStream(), a, b) }
        copy(ain, b, a)
        t.join()
        closeQuietly(b)
        conns -= b
    }

    /** Копирует до конца данных и передаёт «конец» дальше; при ошибке закрывает обе стороны. */
    private fun copy(from: InputStream, to: Socket, other: Socket) {
        val buf = ByteArray(32 * 1024)
        try {
            val o = to.getOutputStream()
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
            }
            to.shutdownOutput()
        } catch (e: IOException) {
            closeQuietly(to)
            closeQuietly(other)
        }
    }

    companion object {
        fun start(port: Int, dpiPort: Int): Front {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("127.0.0.1", port), 256)
            return Front(ss, dpiPort).also { f -> spawn("front-accept") { f.acceptLoop() } }
        }
    }
}
