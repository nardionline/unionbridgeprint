package al.baumarket.unionbridge

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("union_bridge", MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var labelSpinner: Spinner
    private lateinit var receiptSpinner: Spinner
    private lateinit var devices: List<android.bluetooth.BluetoothDevice>
    private var pendingUri: Uri? = null
    private val adapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        pendingUri = intent?.data
        if (hasBluetoothPermission()) refreshDevices() else requestBluetoothPermission()
        pendingUri?.let { handleBridgeUri(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { handleBridgeUri(it) }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(0xFFF5F7FA.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            text = "Union Bridge"
            textSize = 28f
            setTextColor(0xFF182230.toInt())
            setPadding(0, 0, 0, 8)
        }
        content.addView(title)
        content.addView(TextView(this).apply {
            text = "Bluetooth printing · TSPL / TSPL2 labels · ESC/POS receipts"
            textSize = 14f
            setTextColor(0xFF475467.toInt())
            setPadding(0, 0, 0, 24)
        })
        content.addView(section("Printeri i etiketave"))
        labelSpinner = Spinner(this)
        content.addView(labelSpinner)
        content.addView(section("Printeri i faturave"))
        receiptSpinner = Spinner(this)
        content.addView(receiptSpinner)
        content.addView(button("Rifresko printerët Bluetooth") { refreshDevices() })
        content.addView(button("Ruaj printerët e zgjedhur") {
            saveSelections()
            showStatus("Konfigurimi u ruajt.")
        })
        content.addView(button("Testo etiketën 40 × 30 mm") { testLabel() })
        content.addView(button("Testo faturën (ESC/POS)") { testReceipt() })
        status = TextView(this).apply {
            text = "Gati për konfigurim."
            textSize = 14f
            setTextColor(0xFF344054.toInt())
            setPadding(0, 24, 0, 8)
        }
        content.addView(status)
        content.addView(TextView(this).apply {
            text = "Shënim: printeri i etiketave duhet të mbështesë TSPL/TSPL2 dhe Bluetooth Classic SPP. Disa printerë Bluetooth përdorin BLE ose protokoll të pronarit."
            textSize = 12f
            setTextColor(0xFF667085.toInt())
            setPadding(0, 20, 0, 0)
        })
        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
    }

    private fun section(s: String) = TextView(this).apply {
        text = s; textSize = 16f; setTextColor(0xFF101828.toInt())
        setPadding(0, 14, 0, 4)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) requestPermissions(
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 401
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 401 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) refreshDevices()
        else showStatus("Lejo Bluetooth permissions në Android Settings për të përdorur printerët.")
    }

    private fun refreshDevices() {
        if (!hasBluetoothPermission()) { requestBluetoothPermission(); return }
        val a = adapter
        if (a == null) { showStatus("Ky telefon nuk ka Bluetooth."); return }
        if (!a.isEnabled) { showStatus("Aktivizo Bluetooth-in në telefon."); return }
        try {
            devices = BluetoothPrinter.pairedDevices(a)
            val labels = devices.map { "${it.name ?: "Printer pa emër"} (${it.address})" }
            val list = if (labels.isEmpty()) listOf("Nuk ka printerë të çiftuar") else labels
            val spAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, list).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            labelSpinner.adapter = spAdapter
            receiptSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, list).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            selectMac(labelSpinner, prefs.getString("label_mac", null))
            selectMac(receiptSpinner, prefs.getString("receipt_mac", null))
            showStatus("U gjetën ${devices.size} pajisje Bluetooth të çiftuara.")
        } catch (e: SecurityException) {
            showStatus("Lejo Bluetooth Connect dhe provo përsëri.")
        }
    }

    private fun selectMac(spinner: Spinner, mac: String?) {
        if (mac == null) return
        val index = devices.indexOfFirst { it.address.equals(mac, true) }
        if (index >= 0) spinner.setSelection(index)
    }

    private fun selectedMac(spinner: Spinner): String? {
        if (!hasBluetoothPermission() || !::devices.isInitialized || devices.isEmpty()) return null
        val pos = spinner.selectedItemPosition
        return devices.getOrNull(pos)?.address
    }

    private fun saveSelections() {
        val editor = prefs.edit()
        selectedMac(labelSpinner)?.let { editor.putString("label_mac", it) }
        selectedMac(receiptSpinner)?.let { editor.putString("receipt_mac", it) }
        editor.apply()
    }

    private fun testLabel() {
        val mac = selectedMac(labelSpinner) ?: return showStatus("Zgjidh/çifto printerin e etiketave.")
        val p = LabelPayload("UNIONLINE", "Trapano 750W Professional con percussione e valigetta", "TR-10025", "4,990 L", "123456789012", 40, 30, "tspl2")
        printAsync(mac, CommandBuilders.tspl(p, 1), "Etiketa test")
    }

    private fun testReceipt() {
        val mac = selectedMac(receiptSpinner) ?: return showStatus("Zgjidh/çifto printerin e faturave.")
        val p = ReceiptPayload("UNIONLINE", "FATURE TEST", "TEST-0001", "2026-10-25",
            listOf(ReceiptItem("Produkt test", "2", "500", "1,000")),
            "1,000 LEK", "1,000 LEK", "Test printimi OK")
        printAsync(mac, CommandBuilders.escPos(p, 1), "Fatura test")
    }

    private fun handleBridgeUri(uri: Uri) {
        if (uri.scheme != "unionbridge") return
        if (uri.host != "print") {
            showStatus("Lidhja Union Bridge nuk njihet.")
            return
        }
        if (!hasBluetoothPermission()) {
            pendingUri = uri
            requestBluetoothPermission()
            return
        }
        val encoded = uri.getQueryParameter("payload")
        if (encoded.isNullOrBlank()) { showStatus("Mungon payload i printimit."); return }
        try {
            val json = String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
            val payload = PrintPayload.parse(json)
            val mac = payload.printerMac ?: if (payload.type == "label") prefs.getString("label_mac", null) else prefs.getString("receipt_mac", null)
            if (mac.isNullOrBlank()) {
                showStatus("Nuk është zgjedhur printeri për këtë lloj dokumenti.")
                return
            }
            val data = if (payload.type == "label") CommandBuilders.tspl(payload.label!!, payload.copies)
                       else CommandBuilders.escPos(payload.receipt!!, payload.copies)
            printAsync(mac, data, if (payload.type == "label") "Etiketa" else "Fatura")
        } catch (e: Exception) {
            showStatus("Kërkesë printimi e pavlefshme: ${e.message}")
        }
    }

    private fun printAsync(mac: String, data: ByteArray, label: String) {
        showStatus("$label: po lidhem me printerin…")
        worker.execute {
            try {
                val a = adapter ?: throw IllegalStateException("Bluetooth nuk është i disponueshëm")
                BluetoothPrinter.print(a, mac, data)
                runOnUiThread { showStatus("$label u dërgua te printeri. Kontrollo daljen fizike.") }
            } catch (e: Exception) {
                runOnUiThread { showStatus("Gabim printimi: ${e.message}") }
            }
        }
    }

    private fun showStatus(message: String) {
        if (::status.isInitialized) status.text = message
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
