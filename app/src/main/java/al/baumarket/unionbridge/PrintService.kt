package al.baumarket.unionbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Handles print jobs without displaying the Bridge configuration screen.
 * A foreground service is used because Bluetooth socket printing is user-visible
 * device I/O and must remain alive while the job is being sent.
 */
class PrintService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val channelId = "unionbridge_printing"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (intent?.action != ACTION_PRINT) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val json = intent.getStringExtra(EXTRA_PAYLOAD_JSON)
        val mac = intent.getStringExtra(EXTRA_MAC)
        if (json.isNullOrBlank() || mac.isNullOrBlank()) {
            updateNotification("Kërkesa e printimit është e paplotë")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        executor.execute {
            try {
                val payload = PrintPayload.parse(json)
                val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    ?: throw IllegalStateException("Bluetooth nuk është i disponueshëm")
                if (!adapter.isEnabled) throw IllegalStateException("Aktivizo Bluetooth-in në telefon")

                val data: ByteArray
                val description: String
                if (payload.type == "label") {
                    val output = ByteArrayOutputStream()
                    payload.labels.forEach { job ->
                        output.write(CommandBuilders.tspl(job.label, job.copies))
                    }
                    data = output.toByteArray()
                    val copies = payload.labels.sumOf { it.copies }
                    description = "$copies etiketa u dërguan te printeri"
                } else {
                    data = CommandBuilders.escPos(payload.receipt!!, 1)
                    description = "Fatura u dërgua te printeri"
                }

                BluetoothPrinter.print(adapter, mac, data)
                updateNotification(description)
            } catch (e: SecurityException) {
                updateNotification("Lejo Bluetooth Connect për Unionline Bridge")
            } catch (e: Exception) {
                updateNotification("Gabim printimi: ${e.message ?: "Kontrollo printerin"}")
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification("Po përgatitet printimi…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Printimi Unionline", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Statusi i printimeve Bluetooth nga Unionline"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification =
        (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Unionline Bridge")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message).also {
            it.flags = it.flags and Notification.FLAG_ONGOING_EVENT.inv()
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PRINT = "al.baumarket.unionbridge.action.PRINT"
        const val EXTRA_PAYLOAD_JSON = "payload_json"
        const val EXTRA_MAC = "printer_mac"
        private const val NOTIFICATION_ID = 7301
    }
}
