package com.originalgames.dhishoom.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Local Bluetooth transport over an RFCOMM serial channel. To keep things
 * simple and avoid the discovery-permission dance, devices are paired once in
 * the system Bluetooth settings; the host then opens a service and the client
 * connects to it by picking a paired device. Requires BLUETOOTH_CONNECT on API
 * 31+, which the Activity requests before this is used.
 */
object BluetoothLink {

    // A fixed, app-specific service UUID so host and client rendezvous.
    private val SERVICE_UUID: UUID = UUID.fromString("b7e3a1c0-6f4d-4a2e-9c1b-5d8f0a2e7c33")
    private const val SERVICE_NAME = "Dhishoom"

    data class BtPeer(val name: String, val address: String)

    fun adapter(context: Context): BluetoothAdapter? = try {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        mgr?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
    } catch (_: Throwable) {
        null
    }

    fun isAvailable(context: Context): Boolean = adapter(context)?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun bondedPeers(context: Context): List<BtPeer> = try {
        adapter(context)?.bondedDevices?.map {
            BtPeer(it.name ?: it.address ?: "Unknown", it.address ?: "")
        } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    @SuppressLint("MissingPermission")
    fun host(
        context: Context,
        onState: (NetState) -> Unit,
        onLink: (NetLink) -> Unit
    ): Closeable {
        val cancelled = AtomicBoolean(false)
        var server: BluetoothServerSocket? = null

        onState(NetState.LISTENING)

        thread(isDaemon = true, name = "bt-host") {
            try {
                val ad = adapter(context) ?: throw IllegalStateException("no bluetooth")
                val srv = ad.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                server = srv
                val socket: BluetoothSocket = srv.accept()
                cancelled.set(true)
                try { srv.close() } catch (_: Throwable) {}
                bindSocket(socket, onState, onLink)
            } catch (_: Throwable) {
                if (!cancelled.get()) onState(NetState.FAILED)
            }
        }

        return Closeable {
            cancelled.set(true)
            try { server?.close() } catch (_: Throwable) {}
        }
    }

    @SuppressLint("MissingPermission")
    fun join(
        context: Context,
        address: String,
        onState: (NetState) -> Unit,
        onLink: (NetLink) -> Unit
    ): Closeable {
        val cancelled = AtomicBoolean(false)
        var socket: BluetoothSocket? = null

        onState(NetState.CONNECTING)

        thread(isDaemon = true, name = "bt-join") {
            try {
                val ad = adapter(context) ?: throw IllegalStateException("no bluetooth")
                try { ad.cancelDiscovery() } catch (_: Throwable) {}
                val device = ad.getRemoteDevice(address)
                val sock = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket = sock
                sock.connect()   // blocking
                bindSocket(sock, onState, onLink)
            } catch (_: Throwable) {
                if (!cancelled.get()) onState(NetState.FAILED)
            }
        }

        return Closeable {
            cancelled.set(true)
            try { socket?.close() } catch (_: Throwable) {}
        }
    }

    private fun bindSocket(
        socket: BluetoothSocket,
        onState: (NetState) -> Unit,
        onLink: (NetLink) -> Unit
    ) {
        val link = StreamLink(socket.inputStream, socket.outputStream) {
            try { socket.close() } catch (_: Throwable) {}
        }
        link.onState = onState
        onLink(link)
        link.start()
    }
}
