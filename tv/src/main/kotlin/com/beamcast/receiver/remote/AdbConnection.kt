package com.beamcast.receiver.remote

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

/** RSA key handling in the formats adbd expects (no Android dependencies). */
object AdbKeys {
    private const val WORDS = 64 // 2048-bit key as 32-bit words
    private val SHA1_DIGEST_INFO = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

    fun generate(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(WORDS * 32) }.generateKeyPair()

    fun encodePrivate(pair: KeyPair): ByteArray = pair.private.encoded

    fun decodePrivate(pkcs8: ByteArray): KeyPair {
        val factory = KeyFactory.getInstance("RSA")
        val priv = factory.generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as RSAPrivateCrtKey
        return KeyPair(factory.generatePublic(RSAPublicKeySpec(priv.modulus, priv.publicExponent)), priv)
    }

    /** PKCS#1 v1.5 signature of the 20-byte token, which adbd treats as an already computed SHA-1 digest. */
    fun sign(key: PrivateKey, token: ByteArray): ByteArray {
        val size = WORDS * 4
        val tail = SHA1_DIGEST_INFO + token
        val block = ByteArray(size) { 0xff.toByte() }
        block[0] = 0
        block[1] = 1
        block[size - tail.size - 1] = 0
        tail.copyInto(block, size - tail.size)
        return Cipher.getInstance("RSA/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key)
            doFinal(block)
        }
    }

    /** Android's RSAPublicKey struct (len, n0inv, n, rr, exponent; little-endian), base64-encoded. */
    fun encodePublic(key: RSAPublicKey): String {
        val n = key.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32)
        val rr = BigInteger.ONE.shiftLeft(WORDS * 32).modPow(BigInteger.valueOf(2), n)
        val buf = ByteBuffer.allocate(12 + WORDS * 8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(WORDS).putInt(n0inv.toInt())
        for (i in 0 until WORDS) buf.putInt(n.shiftRight(32 * i).toInt())
        for (i in 0 until WORDS) buf.putInt(rr.shiftRight(32 * i).toInt())
        buf.putInt(key.publicExponent.toInt())
        return base64(buf.array())
    }

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun base64(b: ByteArray): String {
        val sb = StringBuilder((b.size + 2) / 3 * 4)
        var i = 0
        while (i < b.size) {
            val b0 = b[i].toInt() and 0xff
            val b1 = if (i + 1 < b.size) b[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < b.size) b[i + 2].toInt() and 0xff else 0
            sb.append(B64[b0 shr 2]).append(B64[((b0 and 3) shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < b.size) B64[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < b.size) B64[b2 and 63] else '=')
            i += 3
        }
        return sb.toString()
    }
}

/**
 * Minimal ADB client over TCP: authenticates with an RSA key and runs shell commands. Used to reach
 * the TV's own adbd on TVs whose Settings app has no Accessibility screen. Not thread-safe.
 */
class AdbConnection(private val socket: Socket, private val key: KeyPair) : Closeable {
    private val input = DataInputStream(socket.getInputStream())
    private val output = socket.getOutputStream()
    private var nextId = 1

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    /**
     * Handshake. The first time, the TV shows "Allow USB debugging?" and this call waits up to
     * [approvalTimeoutMs] for the answer.
     */
    fun connect(approvalTimeoutMs: Int) {
        send(CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
        var step = 0
        while (true) {
            val m = read()
            when {
                m.cmd == CNXN -> return
                m.cmd == AUTH && m.arg0 == AUTH_TOKEN && step == 0 -> send(AUTH, AUTH_SIGNATURE, 0, AdbKeys.sign(key.private, m.data))
                m.cmd == AUTH && m.arg0 == AUTH_TOKEN && step == 1 -> {
                    socket.soTimeout = approvalTimeoutMs
                    val pub = AdbKeys.encodePublic(key.public as RSAPublicKey) + " beamcast@tv\u0000"
                    send(AUTH, AUTH_RSAPUBLICKEY, 0, pub.toByteArray())
                }
                else -> throw IOException("ADB handshake rejected")
            }
            step++
        }
    }

    /** Runs a shell command and returns its output once the command ends. */
    fun shell(command: String): String {
        val local = nextId++
        send(OPEN, local, 0, "shell:$command\u0000".toByteArray())
        val out = ByteArrayOutputStream()
        while (true) {
            val m = read()
            if (m.arg1 != local) continue // leftover from a previous stream
            when (m.cmd) {
                WRTE -> {
                    out.write(m.data)
                    send(OKAY, local, m.arg0, EMPTY)
                }
                CLSE -> {
                    send(CLSE, local, m.arg0, EMPTY)
                    return out.toString("UTF-8")
                }
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun send(cmd: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val buf = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.size)
        buf.putInt(data.sumOf { it.toInt() and 0xff }).putInt(cmd.inv()).put(data)
        output.write(buf.array())
        output.flush()
    }

    private fun read(): Msg {
        val head = ByteArray(24)
        input.readFully(head)
        val h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = h.int
        val arg0 = h.int
        val arg1 = h.int
        val len = h.int
        if (len < 0 || len > MAX_PAYLOAD) throw IOException("Bad ADB packet")
        val data = ByteArray(len)
        input.readFully(data)
        return Msg(cmd, arg0, arg1, data)
    }

    companion object {
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3
        private const val VERSION = 0x01000000
        private const val MAX_DATA = 4096
        private const val MAX_PAYLOAD = 1 shl 20
        private val EMPTY = ByteArray(0)
    }
}
