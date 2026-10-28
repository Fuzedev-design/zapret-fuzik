package com.fuzik.zapret

import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Проверка распределителя с настоящими серверами Telegram (нужен интернет).
 * Клиент ведёт себя как Telegram для Android при прямом подключении:
 * начало соединения без номера дата-центра, затем запрос req_pq.
 */
class TelegramTest {

    companion object {
        private const val PORT = 19080
        private const val DPI = 19079
        private lateinit var front: Front
        private lateinit var fakeDpi: ServerSocket

        @BeforeClass @JvmStatic
        fun setUp() {
            // поддельный byedpi: принимает SOCKS5 и возвращает данные обратно (эхо)
            fakeDpi = ServerSocket(DPI)
            thread(isDaemon = true) {
                while (true) {
                    val s = try { fakeDpi.accept() } catch (e: Exception) { break }
                    thread(isDaemon = true) {
                        s.use {
                            val i = DataInputStream(it.getInputStream())
                            val o = it.getOutputStream()
                            i.readFully(ByteArray(2 + 1))
                            o.write(byteArrayOf(5, 0))
                            val h = ByteArray(4); i.readFully(h)
                            val n = when (h[3].toInt()) { 1 -> 4; 4 -> 16; else -> i.readUnsignedByte() }
                            i.readFully(ByteArray(n + 2))
                            o.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 1))
                            val buf = ByteArray(4096)
                            while (true) { val k = i.read(buf); if (k < 0) break; o.write(buf, 0, k) }
                        }
                    }
                }
            }
            front = Front.start(PORT, DPI)
        }

        @AfterClass @JvmStatic
        fun tearDown() {
            front.stop()
            fakeDpi.close()
        }
    }

    private val rnd = SecureRandom()

    private fun socksConnect(ip: String?, host: String? = null, port: Int = 443): Socket {
        val s = Socket("127.0.0.1", PORT)
        s.soTimeout = 30000
        val o = s.getOutputStream()
        val i = DataInputStream(s.getInputStream())
        o.write(byteArrayOf(5, 1, 0))
        i.readFully(ByteArray(2))
        val addr = if (ip != null) byteArrayOf(1) + InetAddress.getByName(ip).address
        else byteArrayOf(3, host!!.length.toByte()) + host.toByteArray()
        o.write(byteArrayOf(5, 1, 0) + addr + byteArrayOf((port shr 8).toByte(), port.toByte()))
        val r = ByteArray(10)
        i.readFully(r)
        assertEquals(0, r[1].toInt())
        return s
    }

    /** Как Telegram для Android: случайные байты, тип протокола, номер ДЦ НЕ пишется. */
    private fun telegramInit(tag: Int): Triple<ByteArray, javax.crypto.Cipher, javax.crypto.Cipher> {
        val b = ByteArray(64)
        do rnd.nextBytes(b) while (b[0].toInt() and 0xff == 0xef || (b[4].toInt() or b[5].toInt() or b[6].toInt() or b[7].toInt()) == 0)
        for (i in 56..59) b[i] = tag.toByte()
        val r = b.copyOfRange(8, 56).reversedArray()
        val enc = Telegram.ctr(b.copyOfRange(8, 40), b.copyOfRange(40, 56))
        val dec = Telegram.ctr(r.copyOfRange(0, 32), r.copyOfRange(32, 48))
        val e = enc.update(b)
        System.arraycopy(e, 56, b, 56, 8)
        return Triple(b, enc, dec)
    }

    private fun reqPq(abridged: Boolean): ByteArray {
        val body = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0).putLong((System.currentTimeMillis() / 1000L) shl 32).putInt(20)
            .putInt(0xbe7e8ef1.toInt()).put(ByteArray(16).also { rnd.nextBytes(it) }).array()
        return if (abridged) byteArrayOf((40 / 4).toByte()) + body
        else ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(40).array() + body
    }

    private fun askDc(ip: String, abridged: Boolean = false) {
        val t0 = System.currentTimeMillis()
        socksConnect(ip).use { s ->
            val (init, enc, dec) = telegramInit(if (abridged) 0xef else 0xee)
            // два запроса подряд одним куском — распределитель должен разрезать их на кадры
            s.getOutputStream().write(init + enc.update(reqPq(abridged) + reqPq(abridged)))
            val i = DataInputStream(s.getInputStream())
            val head = ByteArray(if (abridged) 1 else 4)
            i.readFully(head)
            val plainHead = dec.update(head)
            val len = if (abridged) (plainHead[0].toInt() and 0x7f) * 4
            else ByteBuffer.wrap(plainHead).order(ByteOrder.LITTLE_ENDIAN).int
            val body = ByteArray(len)
            i.readFully(body)
            val plain = dec.update(body)
            val ctor = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN).getInt(20)
            println("$ip ${if (abridged) "abridged" else "intermediate"}: ответ ${"%08x".format(ctor)} за ${System.currentTimeMillis() - t0} мс")
            assertEquals(0x05162463, ctor)
        }
    }

    @Test fun dc2() = askDc("149.154.167.51")
    @Test fun dc4() = askDc("149.154.167.91")
    @Test fun dc1() = askDc("149.154.175.50")
    @Test fun dc3() = askDc("149.154.175.100")
    @Test fun dc5() = askDc("149.154.171.5")
    @Test fun dc2abridged() = askDc("149.154.167.51", abridged = true)

    @Test fun dcByAddress() {
        fun dc(ip: String) = Telegram.dcByAddress(InetAddress.getByName(ip).address)
        assertEquals(1, dc("149.154.175.50"))
        assertEquals(2, dc("149.154.167.51"))
        assertEquals(2, dc("95.161.76.100"))
        assertEquals(3, dc("149.154.175.100"))
        assertEquals(4, dc("149.154.167.91"))
        assertEquals(4, dc("149.154.164.250"))
        assertEquals(5, dc("149.154.171.5"))
        assertEquals(5, dc("91.108.56.130"))
        assertEquals(1, dc("2001:b28:f23d:f001::a"))
        assertEquals(4, dc("2001:67c:4e8:f004::a"))
        assertEquals(5, dc("2001:b28:f23f:f005::a"))
        assertTrue(Telegram.isTelegram(InetAddress.getByName("91.108.56.130").address))
        assertTrue(!Telegram.isTelegram(InetAddress.getByName("142.250.74.14").address))
    }

    /** Всё, что не Telegram, уходит в byedpi без изменений. */
    @Test fun passthrough() {
        for (target in listOf<Pair<String?, String?>>("142.250.74.14" to null, null to "www.youtube.com")) {
            socksConnect(target.first, target.second).use { s ->
                val msg = ByteArray(3000).also { rnd.nextBytes(it) }
                s.getOutputStream().write(msg)
                val back = ByteArray(msg.size)
                DataInputStream(s.getInputStream()).readFully(back)
                assertArrayEquals(msg, back)
            }
        }
    }

    /** Не-MTProto к адресу Telegram (например, браузер на web.telegram.org) тоже идёт в byedpi. */
    @Test fun telegramNonMtproto() {
        socksConnect("149.154.167.99").use { s ->
            val msg = ByteArray(300) { 0x16 }
            s.getOutputStream().write(msg)
            val back = ByteArray(msg.size)
            DataInputStream(s.getInputStream()).readFully(back)
            assertArrayEquals(msg, back)
        }
    }

    @Test fun probe() {
        for (dc in 1..5) assertNotNull(Telegram.probe(dc).also { println("probe DC$dc: $it мс") })
    }
}
