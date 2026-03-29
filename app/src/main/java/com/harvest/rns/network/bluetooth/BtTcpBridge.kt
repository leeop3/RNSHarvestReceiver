package com.harvest.rns.network.bluetooth

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BtTcpBridge
 *
 * Listens on localhost TCP port 7633 and bridges it to the Bluetooth RNode socket.
 * This lets the Python RNodeInterface (which expects a TCP connection) talk to
 * the physical RNode connected via Bluetooth Classic SPP.
 *
 * Data flow:
 *   Python RNodeInterface ←TCP:7633→ BtTcpBridge ←BT SPP→ RNode hardware
 *
 * Lifecycle:
 *   1. Call start(btInputStream, btOutputStream) after BT connects
 *   2. Python calls inject_rnode(..., tcp_host="127.0.0.1", tcp_port=7633)
 *   3. stop() when BT disconnects
 */
class BtTcpBridge {

    companion object {
        private const val TAG       = "BtTcpBridge"
        const val TCP_PORT          = 7633
        private const val BUF_SIZE  = 4096
    }

    private val running    = AtomicBoolean(false)
    private var serverSock: ServerSocket? = null
    private var clientSock: Socket?       = null

    // Called when BT data arrives — forward to Python
    private var tcpOut: OutputStream? = null

    /**
     * Start the bridge.
     * @param btIn  InputStream from the Bluetooth socket
     * @param btOut OutputStream to the Bluetooth socket
     */
    fun start(btIn: InputStream, btOut: OutputStream) {
        if (running.getAndSet(true)) {
            Log.w(TAG, "Already running")
            return
        }
        Log.i(TAG, "Starting BT↔TCP bridge on port $TCP_PORT")

        // Thread 1: TCP server — accepts Python connection, forwards to BT
        Thread({
            try {
                serverSock = ServerSocket(TCP_PORT)
                serverSock!!.reuseAddress = true
                Log.i(TAG, "Waiting for Python RNodeInterface connection on :$TCP_PORT")
                clientSock = serverSock!!.accept()
                clientSock!!.tcpNoDelay = true
                Log.i(TAG, "Python connected to bridge")
                tcpOut = clientSock!!.outputStream

                // Forward TCP→BT (Python → RNode)
                val tcpIn  = clientSock!!.inputStream
                val buf    = ByteArray(BUF_SIZE)
                while (running.get()) {
                    val n = tcpIn.read(buf)
                    if (n < 0) break
                    btOut.write(buf, 0, n)
                    btOut.flush()
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "TCP→BT error: ${e.message}")
            } finally {
                stop()
            }
        }, "BtTcpBridge-TCP").also { it.isDaemon = true; it.start() }

        // Thread 2: BT→TCP (RNode → Python)
        Thread({
            val buf = ByteArray(BUF_SIZE)
            try {
                while (running.get()) {
                    val n = btIn.read(buf)
                    if (n < 0) break
                    tcpOut?.let {
                        try {
                            it.write(buf, 0, n)
                            it.flush()
                        } catch (e: Exception) {
                            if (running.get()) Log.e(TAG, "BT→TCP write error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "BT→TCP read error: ${e.message}")
            } finally {
                stop()
            }
        }, "BtTcpBridge-BT").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        Log.i(TAG, "Stopping BT↔TCP bridge")
        try { clientSock?.close() } catch (_: Exception) {}
        try { serverSock?.close() } catch (_: Exception) {}
        clientSock = null
        serverSock = null
        tcpOut     = null
    }

    val isRunning: Boolean get() = running.get()
}
