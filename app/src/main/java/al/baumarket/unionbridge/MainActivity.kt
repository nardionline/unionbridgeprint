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
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("union_bridge", MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var labelSpinner: Spinner
    private lateinit var receiptSpinner: Spinner
    private lateinit var protocolSpinner: Spinner
    private lateinit var devices: List<android.bluetooth.BluetoothDevice>
    private var pendingUri: Uri? = null
    private val adapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = intent?.data
        if (incoming != null && incoming.scheme == "unionbridge" && incoming.host == "print" &&
            hasBluetoothPermission() && dispatchPrintToBackground(incoming)) {
            finish()
            return
        }
        buildUi()
        pendingUri = incoming
        if (hasBluetoothPermission()) refreshDevices() else requestBluetoothPermission()
        pendingUri?.let { handleBridgeUri(it) }
    }

    /**
     * Route normal print requests to a foreground service. The Activity is only
     * shown when Bluetooth permission or a saved printer configuration is missing.
     */
    private fun dispatchPrintToBackground(uri: Uri): Boolean {
        val encoded = uri.getQueryParameter("payload") ?: return false
        return try {
            val json = String(
                Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8
            )
            val payload = PrintPayload.parse(json)
            val key = if (payload.type == "label") "label_mac" else "receipt_mac"
            val mac = payload.printerMac ?: prefs.getString(key, null)
            if (mac.isNullOrBlank()) return false
            val requestId = java.util.UUID.randomUUID().toString()
            val serviceIntent = Intent(this, PrintService::class.java).apply {
                action = PrintService.ACTION_PRINT
                putExtra(PrintService.EXTRA_PAYLOAD_JSON, json)
                putExtra(PrintService.EXTRA_MAC, mac)
                putExtra(PrintService.EXTRA_REQUEST_ID, requestId)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
            Log.i("UnionBridge", "Dispatch accepted: id=$requestId type=${payload.type} mac=$mac")
            prefs.edit().putString("last_dispatch", "${System.currentTimeMillis()}|$requestId|ACCEPTED|${payload.type}").apply()
            true
        } catch (e: Exception) {
            Log.e("UnionBridge", "Background dispatch failed", e)
            prefs.edit().putString("last_dispatch", "${System.currentTimeMillis()}|ERROR|${e.javaClass.simpleName}|${e.message}").apply()
            false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uri = intent.data
        if (uri != null && uri.scheme == "unionbridge" && uri.host == "print" &&
            hasBluetoothPermission() && dispatchPrintToBackground(uri)) {
            finish()
            return
        }
        uri?.let { handleBridgeUri(it) }
    }

    private fun buildUi() {
        val pageBg = 0xFFF2F4F7.toInt()
        val red = 0xFFB5121B.toInt()
        val dark = 0xFF182230.toInt()
        val muted = 0xFF667085.toInt()
        val white = 0xFFFFFFFF.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
            setBackgroundColor(pageBg)
        }

        // Compact branded header.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(red, 18)
        }
        header.addView(TextView(this).apply {
            text = "BV"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(white)
            gravity = Gravity.CENTER
            background = rounded(0x33FFFFFF, 12)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) }
        })
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(TextView(this).apply {
            text = "Unionline Bridge"
            textSize = 19f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(white)
        })
        brand.addView(TextView(this).apply {
            text = "PRINT SERVICE  ·  BLUETOOTH"
            textSize = 10f
            setTextColor(0xFFFFE6E8.toInt())
        })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header, spacedParams(bottom = 8))

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = rounded(0xFFE7F5EC.toInt(), 12)
        }
        statusRow.addView(TextView(this).apply {
            text = "●"
            textSize = 15f
            setTextColor(0xFF16803C.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(23), -2)
        })
        status = TextView(this).apply {
            text = "Bridge aktiv · Gati për printim"
            textSize = 12f
            setTextColor(dark)
        }
        statusRow.addView(status)
        root.addView(statusRow, spacedParams(bottom = 9))

        fun sectionTitle(value: String) = TextView(this).apply {
            text = value
            textSize = 11f
            letterSpacing = 0.08f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(muted)
        }
        fun card() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(white, 15, 0xFFE1E6ED.toInt(), dp(1))
        }

        // Label printer and protocol share one compact row.
        val labelCard = card()
        val labelHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labelHeading.addView(sectionTitle("PRINTERI I ETIKETAVE"),
            LinearLayout.LayoutParams(0, -2, 1f))
        labelHeading.addView(TextView(this).apply {
            text = "40 × 30 mm"
            textSize = 10f
            setTextColor(muted)
        })
        labelCard.addView(labelHeading)
        val labelControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labelSpinner = Spinner(this)
        labelControls.addView(labelSpinner, LinearLayout.LayoutParams(0, dp(43), 2.2f))
        protocolSpinner = Spinner(this)
        val protocolAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf("TSPL", "TSPL2"))
        protocolAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        protocolSpinner.adapter = protocolAdapter
        protocolSpinner.setSelection(if (prefs.getString("label_protocol", "tspl2") == "tspl") 0 else 1)
        labelControls.addView(protocolSpinner, LinearLayout.LayoutParams(0, dp(43), 1f).apply {
            marginStart = dp(5)
        })
        labelCard.addView(labelControls)
        root.addView(labelCard, spacedParams(bottom = 8))

        val receiptCard = card()
        receiptCard.addView(sectionTitle("PRINTERI I FATURAVE"))
        receiptSpinner = Spinner(this)
        receiptCard.addView(receiptSpinner, LinearLayout.LayoutParams(-1, dp(43)))
        root.addView(receiptCard, spacedParams(bottom = 9))

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(actionButton("↻ Rifresko", false) { refreshDevices() },
            LinearLayout.LayoutParams(0, dp(43), 1f).apply { marginEnd = dp(5) })
        actionRow.addView(actionButton("Ruaj konfigurimin", false) {
            saveSelections()
            prefs.edit().putString("label_protocol", selectedProtocol()).apply()
            showStatus("Konfigurimi u ruajt.")
        }, LinearLayout.LayoutParams(0, dp(43), 1f).apply { marginStart = dp(5) })
        root.addView(actionRow, spacedParams(bottom = 8))

        val testRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        testRow.addView(actionButton("Testo etiketën", true) { testLabel() },
            LinearLayout.LayoutParams(0, dp(49), 1f).apply { marginEnd = dp(5) })
        testRow.addView(actionButton("Testo faturën", false) { testReceipt() },
            LinearLayout.LayoutParams(0, dp(49), 1f).apply { marginStart = dp(5) })
        root.addView(testRow, spacedParams(bottom = 3))

        root.addView(TextView(this).apply {
            text = "Unionline · Print Bridge"
            textSize = 10f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        })
        setContentView(root)
    }

    private fun printerCard(symbol: String, title: String, tint: Int) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(0xFFFFFFFF.toInt(), 16, 0xFFDCE2EC.toInt(), dp(1))
            val icon = TextView(this@MainActivity).apply {
                text = symbol
                textSize = 27f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(tint)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(10) }
            }
            addView(icon)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(0xFF172033.toInt())
            })
        }

    private fun actionButton(label: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else 0xFF172033.toInt())
        background = rounded(if (primary) 0xFFBE1622.toInt() else 0xFFE6EAF0.toInt(), 16)
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Int, strokeColor: Int = 0x00000000, strokeWidth: Int = 0) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun spacedParams(bottom: Int, left: Int = 0, right: Int = 0, top: Int = 0) =
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
            leftMargin = dp(left)
            rightMargin = dp(right)
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
        if (requestCode == 401 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            refreshDevices()
            // If Web opened the Bridge before Android permission was granted, resume that exact request.
            val uri = pendingUri
            pendingUri = null
            uri?.let { handleBridgeUri(it) }
        } else {
            pendingUri = null
            showStatus("Lejo Bluetooth permissions në Android Settings për të përdorur printerët.")
        }
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
            // Persist the current selections (or the first paired device if this is the first setup).
            saveSelections()
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

    private fun selectedProtocol(): String =
        if (::protocolSpinner.isInitialized && protocolSpinner.selectedItemPosition == 0) "tspl" else "tspl2"

    private fun saveSelections() {
        val editor = prefs.edit()
        if (::protocolSpinner.isInitialized) editor.putString("label_protocol", selectedProtocol())
        selectedMac(labelSpinner)?.let { editor.putString("label_mac", it) }
        selectedMac(receiptSpinner)?.let { editor.putString("receipt_mac", it) }
        editor.apply()
    }

    private fun testLabel() {
        saveSelections()
        val mac = selectedMac(labelSpinner) ?: return showStatus("Zgjidh/çifto printerin e etiketave.")
        val p = LabelPayload("UNIONLINE", "Trapano 750W Professional con percussione e valigetta", "TR-10025", "4,990 L", "123456789012", 40, 30, selectedProtocol())
        printAsync(mac, CommandBuilders.tspl(p, 1), "Etiketa test")
    }

    private fun testReceipt() {
        saveSelections()
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
            val savedMac = if (payload.type == "label") prefs.getString("label_mac", null) else prefs.getString("receipt_mac", null)
            val selectedMac = if (payload.type == "label") selectedMac(labelSpinner) else selectedMac(receiptSpinner)
            val mac = payload.printerMac ?: savedMac ?: selectedMac
            if (mac.isNullOrBlank()) {
                showStatus("Nuk u gjet printer i ruajtur për këtë dokument. Hap Bridge, zgjidh printerin dhe shtyp Ruaj printerët.")
                return
            }
            // Keep the selected device saved for the next Web request.
            prefs.edit().putString(if (payload.type == "label") "label_mac" else "receipt_mac", mac).apply()
            if (payload.type == "label") {
                val out = ByteArrayOutputStream()
                payload.labels.forEach { job ->
                    out.write(CommandBuilders.tspl(job.label, job.copies))
                }
                val totalCopies = payload.labels.sumOf { it.copies }
                printAsync(mac, out.toByteArray(), "${totalCopies} etiketa (${payload.labels.size} artikuj)")
            } else {
                val data = CommandBuilders.escPos(payload.receipt!!, 1)
                printAsync(mac, data, "Fatura")
            }
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
