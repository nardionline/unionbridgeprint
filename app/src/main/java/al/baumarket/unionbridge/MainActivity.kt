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
import java.io.ByteArrayOutputStream
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
        val pageBg = 0xFFF3F5F9.toInt()
        val red = 0xFFBE1622.toInt()
        val dark = 0xFF172033.toInt()
        val muted = 0xFF667085.toInt()
        val greenBg = 0xFFE5F6EB.toInt()
        val green = 0xFF16803C.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBg)
        }

        // Branded header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            background = rounded(red, 0)
        }
        val logo = TextView(this).apply {
            text = "BV"
            textSize = 25f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = rounded(0x33FFFFFF, 18)
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58)).apply { marginEnd = dp(14) }
        }
        header.addView(logo)
        val brandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandText.addView(TextView(this).apply {
            text = "Unionline Bridge"
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
        })
        brandText.addView(TextView(this).apply {
            text = "Bluetooth printing  ·  TSPL / TSPL2  ·  ESC/POS"
            textSize = 12f
            setTextColor(0xFFFFE7E9.toInt())
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)

        val scroll = ScrollView(this).apply { clipToPadding = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(24))
        }

        // Live bridge status card
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(greenBg, 18)
        }
        val statusIcon = TextView(this).apply {
            text = "✓"
            textSize = 25f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = rounded(green, 50)
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(14) }
        }
        statusCard.addView(statusIcon)
        val statusTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusTexts.addView(TextView(this).apply {
            text = "Bridge Aktiv"
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(dark)
        })
        status = TextView(this).apply {
            text = "Gati për të pranuar printime nga Unionline"
            textSize = 13f
            setTextColor(0xFF344054.toInt())
            setPadding(0, dp(3), 0, 0)
        }
        statusTexts.addView(status)
        statusCard.addView(statusTexts, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(statusCard, spacedParams(bottom = 16))

        // Label printer card
        content.addView(printerCard("▣", "Printeri i etiketave", 0xFFBE1622.toInt()), spacedParams(bottom = 0))
        labelSpinner = Spinner(this)
        content.addView(labelSpinner, spacedParams(bottom = 14, left = 14, right = 14))

        content.addView(printerCard("▤", "Printeri i faturave", 0xFFBE1622.toInt()), spacedParams(bottom = 0))
        receiptSpinner = Spinner(this)
        content.addView(receiptSpinner, spacedParams(bottom = 16, left = 14, right = 14))

        // Refresh and save actions in one row
        val configRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        configRow.addView(actionButton("↻  Rifresko printerët", false) { refreshDevices() },
            LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        configRow.addView(actionButton("▣  Ruaj printerët", false) {
            saveSelections()
            showStatus("Konfigurimi u ruajt.")
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
        content.addView(configRow, spacedParams(bottom = 16))

        // Test actions
        val testRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        testRow.addView(actionButton("▥\nTesto etiketën\n40 × 30 mm", true) { testLabel() },
            LinearLayout.LayoutParams(0, dp(112), 1f).apply { marginEnd = dp(6) })
        testRow.addView(actionButton("▤\nTesto faturën\n(ESC/POS)", false) { testReceipt() },
            LinearLayout.LayoutParams(0, dp(112), 1f).apply { marginStart = dp(6) })
        content.addView(testRow, spacedParams(bottom = 16))

        // Queue / current activity card
        val queueCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(0xFFFFFFFF.toInt(), 18, 0xFFDCE2EC.toInt(), dp(1))
        }
        val queueIcon = TextView(this).apply {
            text = "▰"
            textSize = 24f
            setTextColor(muted)
            layoutParams = LinearLayout.LayoutParams(dp(42), -2).apply { marginEnd = dp(10) }
        }
        queueCard.addView(queueIcon)
        val queueTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        queueTexts.addView(TextView(this).apply {
            text = "Printime nga Unionline"
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(dark)
        })
        queueTexts.addView(TextView(this).apply {
            text = "Bridge pret kërkesën e radhës"
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(2), 0, 0)
        })
        queueCard.addView(queueTexts, LinearLayout.LayoutParams(0, -2, 1f))
        val readyBadge = TextView(this).apply {
            text = "Gati"
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(green)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(0xFFE7F7ED.toInt(), 30)
        }
        queueCard.addView(readyBadge)
        content.addView(queueCard, spacedParams(bottom = 14))

        content.addView(TextView(this).apply {
            text = "Shënim: printeri i etiketave duhet të mbështesë TSPL/TSPL2 dhe Bluetooth Classic SPP. Disa printerë përdorin BLE ose protokoll të pronarit."
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(4), dp(4), dp(4), dp(8))
        })

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
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

    private fun spacedParams(bottom: Int, left: Int = 0, right: Int = 0) =
        LinearLayout.LayoutParams(-1, -2).apply {
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

    private fun saveSelections() {
        val editor = prefs.edit()
        selectedMac(labelSpinner)?.let { editor.putString("label_mac", it) }
        selectedMac(receiptSpinner)?.let { editor.putString("receipt_mac", it) }
        editor.apply()
    }

    private fun testLabel() {
        saveSelections()
        val mac = selectedMac(labelSpinner) ?: return showStatus("Zgjidh/çifto printerin e etiketave.")
        val p = LabelPayload("UNIONLINE", "Trapano 750W Professional con percussione e valigetta", "TR-10025", "4,990 L", "123456789012", 40, 30, "tspl2")
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
