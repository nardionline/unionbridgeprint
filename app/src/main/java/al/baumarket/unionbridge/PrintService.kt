package al.baumarket.unionbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.Executors

/** Serial background print queue. One service instance owns all jobs in arrival order. */
class PrintService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val channelId = "unionbridge_printing"
    private val queue = ArrayDeque<PrintJob>()
    private val queueLock = Any()
    @Volatile private var workerRunning = false
    @Volatile private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        startAsForeground("Po pranohet kërkesa e printimit…")
        if (intent?.action != ACTION_PRINT) {
            log("ERROR", "Intent pa action print")
            finishIfIdle(startId)
            return START_NOT_STICKY
        }

        val json = intent.getStringExtra(EXTRA_PAYLOAD_JSON)
        val mac = intent.getStringExtra(EXTRA_MAC)
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: "unknown-${System.currentTimeMillis()}"
        if (json.isNullOrBlank() || mac.isNullOrBlank()) {
            log(requestId, "REJECTED: payload ose MAC bosh")
            updateNotification("Kërkesa e printimit është e paplotë")
            finishIfIdle(startId)
            return START_NOT_STICKY
        }

        synchronized(queueLock) {
            queue.addLast(PrintJob(requestId, json, mac, startId))
            log(requestId, "RECEIVED: në radhë (${queue.size} kërkesa)")
            updateNotification("Kërkesa u mor · në radhë: ${queue.size}")
            if (!workerRunning) {
                workerRunning = true
                executor.execute { drainQueue() }
            }
        }
        return START_NOT_STICKY
    }

    private fun drainQueue() {
        while (true) {
            val next = synchronized(queueLock) {
                if (queue.isEmpty()) {
                    workerRunning = false
                    null to latestStartId
                } else queue.removeFirst() to null
            }
            val job = next.first
            if (job == null) {
                val stopId = next.second ?: latestStartId
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelfResult(stopId)
                return
            }

            try {
                log(job.id, "START: përgatitja e të dhënave")
                updateNotification("Po printohet · ${job.id.take(8)}")
                val payload = PrintPayload.parse(job.json)
                val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    ?: throw IllegalStateException("Bluetooth nuk është i disponueshëm")
                if (!adapter.isEnabled) throw IllegalStateException("Aktivizo Bluetooth-in në telefon")

                val data: ByteArray
                val description: String
                if (payload.type == "label") {
                    val output = ByteArrayOutputStream()
                    payload.labels.forEach { item -> output.write(CommandBuilders.tspl(item.label, item.copies)) }
                    data = output.toByteArray()
                    val copies = payload.labels.sumOf { it.copies }
                    description = "$copies etiketa u dërguan te printeri"
                } else {
                    data = CommandBuilders.escPos(payload.receipt!!, 1)
                    description = "Fatura u dërgua te printeri"
                }

                log(job.id, "CONNECT: printer=${job.mac}")
                BluetoothPrinter.print(adapter, job.mac, data)
                log(job.id, "SENT: ${data.size} byte; printeri nuk jep konfirmim fizik")
                updateNotification(description)
            } catch (e: SecurityException) {
                log(job.id, "ERROR SecurityException: ${e.message}")
                updateNotification("Lejo Bluetooth Connect për Unionline Bridge")
            } catch (e: Exception) {
                log(job.id, "ERROR ${e.javaClass.simpleName}: ${e.message}")
                updateNotification("Gabim printimi: ${e.message ?: "Kontrollo printerin"}")
            }
        }

    }

    private fun finishIfIdle(startId: Int) {
        synchronized(queueLock) {
            if (!workerRunning && queue.isEmpty()) {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelfResult(startId)
            }
        }
    }

    private fun log(id: String, message: String) {
        val line = "${System.currentTimeMillis()}|$id|$message"
        Log.i("UnionBridgePrint", line)
        val prefs = getSharedPreferences("union_bridge", MODE_PRIVATE)
        synchronized(prefs) {
            val old = prefs.getString("print_log", "") ?: ""
            val lines = (old.lines().filter { it.isNotBlank() } + line).takeLast(100)
            prefs.edit().putString("print_log", lines.joinToString("\n")).apply()
        }
    }

    private fun startAsForeground(message: String) {
        val notification = buildNotification(message)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(NOTIFICATION_ID, notification)
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
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private data class PrintJob(val id: String, val json: String, val mac: String, val startId: Int)

    companion object {
        const val ACTION_PRINT = "al.baumarket.unionbridge.action.PRINT"
        const val EXTRA_PAYLOAD_JSON = "payload_json"
        const val EXTRA_MAC = "printer_mac"
        const val EXTRA_REQUEST_ID = "request_id"
        private const val NOTIFICATION_ID = 7301
    }
}
