package com.originalgames.dhishoom.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

/** Connection lifecycle reported back to the game. */
enum class NetState { LISTENING, CONNECTING, CONNECTED, FAILED, CLOSED }

/**
 * A bidirectional, message-oriented link between two phones. The two transports
 * (Wi-Fi TCP and Bluetooth RFCOMM) both expose this same tiny surface, so the
 * game's netcode is written once and is transport-agnostic.
 */
interface NetLink {
    fun send(data: ByteArray)
    fun close()
    var onMessage: ((ByteArray) -> Unit)?
    var onState: ((NetState) -> Unit)?
}

/** Wire message tags. */
object Msg {
    const val HELLO: Byte = 1   // client -> host: my chosen fighter index
    const val START: Byte = 2   // host -> client: fighter indices, match begins
    const val STATE: Byte = 3   // host -> client: a full world snapshot
    const val INPUT: Byte = 4   // client -> host: my button bitmask this frame
    const val BYE: Byte = 5     // either way: leaving
}

/**
 * The host-authoritative world snapshot streamed to the client ~30x/second.
 * Plain mutable fields with a fixed binary layout; the client overwrites its
 * fighters from this and renders, never simulating combat itself.
 */
class Snapshot {
    var ax = 0f; var ay = 0f; var afacing = 1; var astate = 0; var atime = 0
    var ahp = 0f; var ameter = 0f
    var bx = 0f; var by = 0f; var bfacing = 1; var bstate = 0; var btime = 0
    var bhp = 0f; var bmeter = 0f
    var phase = 0; var roundNum = 1; var wonA = 0; var wonB = 0
    var timer = 0          // whole seconds remaining
    var comboShow = 0; var comboSide = 0
    var banner = 0
    var startedA = 0; var startedB = 0   // attack-start codes for client SFX

    fun encode(): ByteArray {
        val bos = ByteArrayOutputStream(64)
        val o = DataOutputStream(bos)
        o.writeByte(Msg.STATE.toInt())
        o.writeFloat(ax); o.writeFloat(ay); o.writeByte(afacing); o.writeByte(astate); o.writeShort(atime)
        o.writeFloat(ahp); o.writeFloat(ameter)
        o.writeFloat(bx); o.writeFloat(by); o.writeByte(bfacing); o.writeByte(bstate); o.writeShort(btime)
        o.writeFloat(bhp); o.writeFloat(bmeter)
        o.writeByte(phase); o.writeByte(roundNum); o.writeByte(wonA); o.writeByte(wonB)
        o.writeShort(timer); o.writeShort(comboShow); o.writeByte(comboSide); o.writeByte(banner)
        o.writeByte(startedA); o.writeByte(startedB)
        o.flush()
        return bos.toByteArray()
    }

    /** Decodes a STATE payload (the leading tag byte already consumed). */
    fun decode(data: ByteArray) {
        val i = DataInputStream(ByteArrayInputStream(data, 1, data.size - 1))
        ax = i.readFloat(); ay = i.readFloat(); afacing = i.readByte().toInt(); astate = i.readByte().toInt(); atime = i.readShort().toInt()
        ahp = i.readFloat(); ameter = i.readFloat()
        bx = i.readFloat(); by = i.readFloat(); bfacing = i.readByte().toInt(); bstate = i.readByte().toInt(); btime = i.readShort().toInt()
        bhp = i.readFloat(); bmeter = i.readFloat()
        phase = i.readByte().toInt(); roundNum = i.readByte().toInt(); wonA = i.readByte().toInt(); wonB = i.readByte().toInt()
        timer = i.readShort().toInt(); comboShow = i.readShort().toInt(); comboSide = i.readByte().toInt(); banner = i.readByte().toInt()
        startedA = i.readByte().toInt(); startedB = i.readByte().toInt()
    }
}

/** Small builders for the non-snapshot messages. */
object Packet {
    fun hello(fighter: Int) = byteArrayOf(Msg.HELLO, fighter.toByte())
    fun start(aFighter: Int, bFighter: Int) = byteArrayOf(Msg.START, aFighter.toByte(), bFighter.toByte())
    fun input(buttons: Int) = byteArrayOf(Msg.INPUT, buttons.toByte())
    fun bye() = byteArrayOf(Msg.BYE)
}

/**
 * Drives a connected pair of streams: a reader thread that delivers complete,
 * length-prefixed messages, plus a synchronized writer. Both transports build
 * one of these once their socket connects. All I/O is wrapped so a dropped link
 * surfaces as [NetState.CLOSED] rather than a crash.
 */
class StreamLink(
    private val input: InputStream,
    private val output: OutputStream,
    private val onClose: () -> Unit
) : NetLink {

    override var onMessage: ((ByteArray) -> Unit)? = null
    override var onState: ((NetState) -> Unit)? = null

    @Volatile private var open = true
    private val writeLock = Any()

    fun start() {
        onState?.invoke(NetState.CONNECTED)
        thread(isDaemon = true, name = "net-reader") {
            val header = ByteArray(4)
            try {
                while (open) {
                    readFully(header, 4)
                    val len = ((header[0].toInt() and 0xFF) shl 24) or
                        ((header[1].toInt() and 0xFF) shl 16) or
                        ((header[2].toInt() and 0xFF) shl 8) or
                        (header[3].toInt() and 0xFF)
                    if (len <= 0 || len > MAX_MSG) break
                    val body = ByteArray(len)
                    readFully(body, len)
                    onMessage?.invoke(body)
                }
            } catch (_: Throwable) {
                // fall through to close
            } finally {
                close()
            }
        }
    }

    private fun readFully(buf: ByteArray, len: Int) {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n < 0) throw java.io.EOFException()
            read += n
        }
    }

    override fun send(data: ByteArray) {
        if (!open) return
        try {
            synchronized(writeLock) {
                val len = data.size
                output.write(byteArrayOf(
                    (len ushr 24).toByte(), (len ushr 16).toByte(),
                    (len ushr 8).toByte(), len.toByte()
                ))
                output.write(data)
                output.flush()
            }
        } catch (_: Throwable) {
            close()
        }
    }

    override fun close() {
        if (!open) return
        open = false
        try { input.close() } catch (_: Throwable) {}
        try { output.close() } catch (_: Throwable) {}
        try { onClose() } catch (_: Throwable) {}
        onState?.invoke(NetState.CLOSED)
    }

    companion object { private const val MAX_MSG = 1 shl 16 }
}
