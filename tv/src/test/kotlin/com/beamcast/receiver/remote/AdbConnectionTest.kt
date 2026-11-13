package com.beamcast.receiver.remote

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher
import kotlin.concurrent.thread

class AdbConnectionTest {
    private val pair = AdbKeys.generate()

    @Test fun publicKeyUsesAndroidLayout() {
        val pub = pair.public as RSAPublicKey
        val raw = Base64.getDecoder().decode(AdbKeys.encodePublic(pub))
        assertEquals(524, raw.size)
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, buf.int)
        val n0inv = buf.int
        val n0 = buf.getInt(8)
        assertEquals(-1, n0 * n0inv) // n * n0inv == -1 (mod 2^32)
        val n = BigInteger(1, raw.copyOfRange(8, 264).reversedArray())
        assertEquals(pub.modulus, n)
        val rr = BigInteger(1, raw.copyOfRange(264, 520).reversedArray())
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), rr)
        assertEquals(65537, buf.getInt(520))
    }

    @Test fun privateKeyRoundTrips() {
        val back = AdbKeys.decodePrivate(AdbKeys.encodePrivate(pair))
        assertEquals(pair.public, back.public)
    }

    /** Fake adbd: demands a signature, then the public key, then serves one shell command. */
    @Test fun authenticatesAndRunsShell() {
        val token = ByteArray(20) { it.toByte() }
        val server = ServerSocket(0)
        var command = ""
        var signatureOk = false
        var keySent = ""
        val t = thread {
            server.accept().use { s ->
                val i = DataInputStream(s.getInputStream())
                val o = s.getOutputStream()
                read(i)
                write(o, AdbConnection.AUTH, AdbConnection.AUTH_TOKEN, 0, token)
                val sig = read(i)
                // adbd checks the token as a ready-made SHA-1 digest: undo the signature and compare its tail.
                val plain = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                    init(Cipher.DECRYPT_MODE, pair.public)
                    doFinal(sig.data)
                }
                signatureOk = plain.size == 35 && plain.takeLast(20) == token.toList()
                write(o, AdbConnection.AUTH, AdbConnection.AUTH_TOKEN, 0, token)
                keySent = String(read(i).data)
                write(o, AdbConnection.CNXN, 0x01000000, 4096, "device::\u0000".toByteArray())
                val open = read(i)
                command = String(open.data)
                write(o, AdbConnection.OKAY, 7, open.arg0, ByteArray(0))
                write(o, AdbConnection.WRTE, 7, open.arg0, "hello ".toByteArray())
                read(i)
                write(o, AdbConnection.WRTE, 7, open.arg0, "tv".toByteArray())
                read(i)
                write(o, AdbConnection.CLSE, 7, open.arg0, ByteArray(0))
                read(i)
            }
        }
        val conn = AdbConnection(Socket("127.0.0.1", server.localPort).apply { soTimeout = 5000 }, pair)
        conn.connect(5000)
        assertEquals("hello tv", conn.shell("input keyevent 19"))
        conn.close()
        t.join(5000)
        server.close()
        assertEquals("shell:input keyevent 19\u0000", command)
        assertEquals(true, signatureOk)
        assertEquals(AdbKeys.encodePublic(pair.public as RSAPublicKey) + " beamcast@tv\u0000", keySent)
    }

    private class Packet(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun read(i: DataInputStream): Packet {
        val head = ByteArray(24).also { i.readFully(it) }
        val h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = h.int; val a0 = h.int; val a1 = h.int; val len = h.int
        val crc = h.int
        assertEquals(cmd.inv(), h.int)
        val data = ByteArray(len).also { i.readFully(it) }
        assertEquals(crc, data.sumOf { it.toInt() and 0xff })
        return Packet(cmd, a0, a1, data)
    }

    private fun write(o: OutputStream, cmd: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val b = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.size).putInt(data.sumOf { it.toInt() and 0xff }).putInt(cmd.inv()).put(data)
        o.write(b.array()); o.flush()
    }
}
