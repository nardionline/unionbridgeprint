package al.baumarket.unionbridge

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID

object BluetoothPrinter {
    // Standard Serial Port Profile UUID used by most classic Bluetooth receipt/label printers.
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    fun pairedDevices(adapter: BluetoothAdapter): List<BluetoothDevice> =
        adapter.bondedDevices?.sortedBy { it.name ?: it.address } ?: emptyList()

    /**
     * Retry only the Bluetooth connection stage. Once the socket connects, the
     * payload is written exactly once to avoid duplicate labels if the printer
     * received the data but the phone did not receive a clean disconnect result.
     */
    fun print(adapter: BluetoothAdapter, mac: String, data: ByteArray) {
        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (e: Exception) {
            throw IOException("Adresa Bluetooth e printerit nuk është e vlefshme: $mac", e)
        }

        var lastError: Exception? = null
        for (attempt in 1..MAX_CONNECT_ATTEMPTS) {
            var socket: BluetoothSocket? = null
            try {
                adapter.cancelDiscovery()
                // Some printers need a short pause after discovery is cancelled.
                Thread.sleep(if (attempt == 1) 250L else 500L)
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()

                val output = socket.outputStream
                output.write(data)
                output.flush()
                // Give the Bluetooth stack a moment to drain the final bytes.
                Thread.sleep(150L)
                try { output.close() } catch (_: Exception) {}
                return
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                // Only retry when connection was not established. If writing
                // began, automatic resend could print the same label twice.
                if (socket?.isConnected == true) {
                    throw IOException(
                        "Lidhja u hap, por dërgimi i të dhënave dështoi. Kontrollo printerin para se të riprovosh. ${e.message ?: ""}",
                        e
                    )
                }
                if (attempt < MAX_CONNECT_ATTEMPTS) {
                    try { Thread.sleep(RETRY_DELAYS_MS[attempt - 1]) } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Printimi u ndërpre.", ie)
                    }
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }

        throw IOException(
            "Printeri Bluetooth nuk u lidh pas $MAX_CONNECT_ATTEMPTS provash. Kontrollo që printeri është ndezur dhe pranë telefonit. ${lastError?.message ?: ""}",
            lastError
        )
    }

    private const val MAX_CONNECT_ATTEMPTS = 3
    private val RETRY_DELAYS_MS = longArrayOf(700L, 1200L)
}
