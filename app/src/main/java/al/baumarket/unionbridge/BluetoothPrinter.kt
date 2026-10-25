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

    fun print(adapter: BluetoothAdapter, mac: String, data: ByteArray) {
        val device = adapter.getRemoteDevice(mac)
        var socket: BluetoothSocket? = null
        try {
            adapter.cancelDiscovery()
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket.connect()
            socket.outputStream.use { output ->
                output.write(data)
                output.flush()
            }
        } catch (e: Exception) {
            throw IOException("Nuk u printua te $mac. Kontrollo printerin, Bluetooth-in dhe protokollin SPP. ${e.message ?: ""}", e)
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
