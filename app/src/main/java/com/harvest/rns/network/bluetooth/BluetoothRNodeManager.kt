package com.harvest.rns.network.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.harvest.rns.network.rns.RnsFrameDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Manages Bluetooth Classic SPP (Serial Port Profile) connection to an RNode device.
 *
 * RNode uses Bluetooth SPP (UUID: 00001101-...) for serial communication.
 * Data flows as KISS-framed RNS packets over the serial link.
 *
 * Connection lifecycle:
 *  1. User selects RNode from paired device list
 *  2. SPP socket connects
 *  3. Reader coroutine continuously reads bytes into ring buffer
 *  4. KISS framer extracts complete frames
 *  5. Frames emitted as raw ByteArray via [incomingFrames] SharedFlow
 */
@SuppressLint("MissingPermission")
class BluetoothRNodeManager(private val context: Context) {

    companion object {
        private const val TAG = "BtRNodeManager"
        // Standard Bluetooth SPP UUID
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val READ_BUFFER_SIZE = 4096
        private const val RECONNECT_DELAY_MS = 5000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    // ─── State ────────────────────────────────────────────────────────────────

    sealed class BtState {
        object Idle : BtState()
        data class Connecting(val device: BluetoothDevice) : BtState()
        data class Connected(val device: BluetoothDevice) : BtState()
        data class Error(val message: String) : BtState()
        object Reconnecting : BtState()
    }

    private val _state = MutableStateFlow<BtState>(BtState.Idle)
    val state: StateFlow<BtState> = _state

    private val _incomingFrames = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)
    val incomingFrames: SharedFlow<ByteArray> = _incomingFrames

    // ─── Internal ─────────────────────────────────────────────────────────────

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readerJob: Job? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val readBuffer = ByteArray(READ_BUFFER_SIZE)
    private val accumulator = mutableListOf<Byte>()

    // ─── Public API ───────────────────────────────────────────────────────────

    fun connect(device: BluetoothDevice) {
        scope.launch {
            connectInternal(device)
        }
    }

    fun disconnect() {
        readerJob?.cancel()
        closeSocket()
        _state.value = BtState.Idle
        Log.i(TAG, "Disconnected from RNode")
    }

    fun dispose() {
        scope.cancel()
        disconnect()
    }

    /**
     * Send raw bytes to the RNode (e.g., for configuration commands).
     */
    fun sendFrame(data: ByteArray) {
        scope.launch {
            try {
                val kissFrame = RnsFrameDecoder.KissFramer.encodeFrame(data)
                outputStream?.write(kissFrame)
                outputStream?.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Send error: ${e.message}")
            }
        }
    }

    /**
     * Send pre-built raw bytes directly to the RNode without additional KISS wrapping.
     * Use this for RadioConfig frames which are already fully encoded.
     */
    fun sendRawFrame(data: ByteArray) {
        scope.launch {
            try {
                outputStream?.write(data)
                outputStream?.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Send raw error: ${e.message}")
            }
        }
    }

    // ─── Internal Connection Logic ────────────────────────────────────────────

    private suspend fun connectInternal(device: BluetoothDevice, attempt: Int = 1) {
        _state.value = BtState.Connecting(device)
        Log.i(TAG, "Connecting to ${device.name} (${device.address}), attempt $attempt")

        closeSocket()

        try {
            // Create RFCOMM socket for SPP
            val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket = sock

            // Ensure Bluetooth discovery is cancelled before connect
            BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()

            withContext(Dispatchers.IO) {
                sock.connect()
            }

            inputStream  = sock.inputStream
            outputStream = sock.outputStream

            _state.value = BtState.Connected(device)
            Log.i(TAG, "Connected to ${device.name}")

            // Start reading frames
            startReading(device)

        } catch (e: IOException) {
            Log.e(TAG, "Connection failed (attempt $attempt): ${e.message}")
            closeSocket()

            if (attempt < MAX_RECONNECT_ATTEMPTS) {
                _state.value = BtState.Reconnecting
                delay(RECONNECT_DELAY_MS)
                connectInternal(device, attempt + 1)
            } else {
                _state.value = BtState.Error("Failed to connect after $attempt attempts: ${e.message}")
            }
        }
    }

    private fun startReading(device: BluetoothDevice) {
        readerJob?.cancel()
        readerJob = scope.launch(Dispatchers.IO) {
            val stream = inputStream ?: return@launch
            Log.d(TAG, "Reader started for ${device.name}")
            accumulator.clear()

            try {
                while (isActive) {
                    val bytesRead = stream.read(readBuffer)
                    if (bytesRead < 0) {
                        Log.w(TAG, "Stream closed by remote device")
                        break
                    }

                    // Accumulate bytes
                    for (i in 0 until bytesRead) {
                        accumulator.add(readBuffer[i])
                    }

                    // Extract complete KISS frames
                    val accArray = accumulator.toByteArray()
                    val rawFrames = RnsFrameDecoder.KissFramer.extractRawFrames(accArray)

                    if (rawFrames.isNotEmpty()) {
                        // Remove consumed bytes: find last FEND position
                        val lastFend = accArray.indexOfLast { it == RnsFrameDecoder.KissFramer.FEND }
                        if (lastFend >= 0) {
                            val remaining = accArray.drop(lastFend + 1)
                            accumulator.clear()
                            accumulator.addAll(remaining.toList())
                        }

                        // Emit each frame that has a processable payload
                        val cmd_data     = RnsFrameDecoder.KissFramer.CMD_DATA.toInt() and 0xFF
                        val cmd_ifaces   = RnsFrameDecoder.KissFramer.CMD_INTERFACES.toInt() and 0xFF
                        val cmd_ready    = RnsFrameDecoder.KissFramer.CMD_READY.toInt() and 0xFF
                        for (rf in rawFrames) {
                            // Emit ALL frames to service for logging, even if payload is empty
                            // Service will filter based on payload content
                            _incomingFrames.emit(
                                rf.payload.ifEmpty {
                                    // For heartbeat/status frames, emit a 1-byte sentinel
                                    // so the service can log them in the UI
                                    byteArrayOf(rf.cmd.toByte())
                                }.also {
                                    Log.v(TAG, "Frame cmd=0x${rf.cmd.toString(16)} ${rf.payload.size}b: ${rf.rawHex}")
                                }
                            )
                        }
                    }

                    // Prevent unbounded accumulation (max 64KB)
                    if (accumulator.size > 65536) {
                        Log.w(TAG, "Accumulator overflow, clearing")
                        accumulator.clear()
                    }
                }
            } catch (e: IOException) {
                if (isActive) {
                    Log.e(TAG, "Read error: ${e.message}")
                    _state.value = BtState.Error("Connection lost: ${e.message}")
                    // Attempt reconnect
                    delay(RECONNECT_DELAY_MS)
                    connectInternal(device)
                }
            }
        }
    }

    private fun closeSocket() {
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (_: IOException) {}
        socket       = null
        inputStream  = null
        outputStream = null
    }
}
