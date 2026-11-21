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
import android.widget.RadioButton
import android.widget.RadioGroup
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("union_bridge", MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var labelSpinner: Spinner
    private lateinit var receiptSpinner: Spinner
    private lateinit var protocolGroup: RadioGroup
    private var tsplRadioId: Int = View.NO_ID
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
        val pageBg = 0xFFF2F4F8.toInt()
        val red = 0xFFB91524.toInt()
        val dark = 0xFF182230.toInt()
        val muted = 0xFF687386.toInt()
        val white = 0xFFFFFFFF.toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(18))
            setBackgroundColor(pageBg)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(pageBg)
            addView(content)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF9F1020.toInt(), 0xFFD21E30.toInt())
            ).apply { cornerRadius = dp(20).toFloat() }
            elevation = dp(3).toFloat()
        }
        header.addView(TextView(this).apply {
            text = "BV"
            textSize = 20f
            setTextColor(white)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(0x33FFFFFF, 14)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(14) }
        })
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(TextView(this).apply {
            text = "Unionline Bridge"
            textSize = 21f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(white)
        })
        brand.addView(TextView(this).apply {
            text = "PRINT SERVICE  ·  BLUETOOTH"
            textSize = 10f
            letterSpacing = 0.08f
            setTextColor(0xFFFFE6E9.toInt())
        })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(header, spacedParams(bottom = 14))

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(0xFFE6F5EC.toInt(), 16, 0xFFC8E8D4.toInt(), dp(1))
        }
        statusCard.addView(TextView(this).apply {
            text = "✓"
            textSize = 20f
            setTextColor(0xFF16834A.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(28), -2)
        })
        status = TextView(this).apply {
            text = "Bridge aktiv · Gati për printim"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF195B39.toInt())
        }
        statusCard.addView(status)
        content.addView(statusCard, spacedParams(bottom = 16))

        // Label printer card
        val labelCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(white, 18, 0xFFE0E5ED.toInt(), dp(1))
            elevation = dp(1).toFloat()
        }
        labelCard.addView(TextView(this).apply {
            text = "▣   PRINTERI I ETIKETAVE"
            textSize = 12f
            letterSpacing = 0.05f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(red)
        })
        labelCard.addView(TextView(this).apply {
            text = "Zgjidh printerin dhe protokollin e etiketës"
            textSize = 11f
            setTextColor(muted)
            setPadding(0, dp(3), 0, dp(7))
        })
        labelSpinner = Spinner(this)
        labelSpinner.background = rounded(0xFFF7F8FA.toInt(), 12, 0xFFD9DFE8.toInt(), dp(1))
        labelCard.addView(labelSpinner, LinearLayout.LayoutParams(-1, dp(48)))
        val protocolLabel = TextView(this).apply {
            text = "PROTOKOLLI I PRINTIMIT"
            textSize = 10f
            letterSpacing = 0.06f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(muted)
            setPadding(dp(2), dp(10), 0, dp(5))
        }
        labelCard.addView(protocolLabel)
        protocolGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(3), dp(4), dp(3))
            background = rounded(0xFFF3F5F8.toInt(), 13, 0xFFD9DFE8.toInt(), dp(1))
        }
        val savedProtocol = prefs.getString("label_protocol", "tspl2")
        val tsplRadio = RadioButton(this).apply {
            id = View.generateViewId()
            text = "TSPL"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(dark)
            buttonTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(red, 0xFF8A94A5.toInt())
            )
            setPadding(dp(8), dp(7), dp(8), dp(7))
            layoutParams = RadioGroup.LayoutParams(0, dp(46), 1f)
        }
        tsplRadioId = tsplRadio.id
        val tspl2Radio = RadioButton(this).apply {
            id = View.generateViewId()
            text = "TSPL2"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(dark)
            buttonTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(red, 0xFF8A94A5.toInt())
            )
            setPadding(dp(8), dp(7), dp(8), dp(7))
            layoutParams = RadioGroup.LayoutParams(0, dp(46), 1f)
        }
        protocolGroup.addView(tsplRadio)
        protocolGroup.addView(tspl2Radio)
        protocolGroup.check(if (savedProtocol == "tspl") tsplRadio.id else tspl2Radio.id)
        labelCard.addView(protocolGroup, LinearLayout.LayoutParams(-1, dp(54)))
        content.addView(labelCard, spacedParams(bottom = 12))

        // Receipt printer card
        val receiptCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(white, 18, 0xFFE0E5ED.toInt(), dp(1))
            elevation = dp(1).toFloat()
        }
        receiptCard.addView(TextView(this).apply {
            text = "▤   PRINTERI I FATURAVE"
            textSize = 12f
            letterSpacing = 0.05f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(red)
        })
        receiptCard.addView(TextView(this).apply {
            text = "Për printimin e faturave me ESC/POS"
            textSize = 11f
            setTextColor(muted)
            setPadding(0, dp(3), 0, dp(7))
        })
        receiptSpinner = Spinner(this)
        receiptSpinner.background = rounded(0xFFF7F8FA.toInt(), 12, 0xFFD9DFE8.toInt(), dp(1))
        receiptCard.addView(receiptSpinner, LinearLayout.LayoutParams(-1, dp(48)))
        content.addView(receiptCard, spacedParams(bottom = 14))

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(actionButton("⟳  Rifresko", false) { refreshDevices() },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(6) })
        actionRow.addView(actionButton("▣  Ruaj", false) {
            saveSelections()
            prefs.edit().putString("label_protocol", selectedProtocol()).apply()
            showStatus("Konfigurimi u ruajt.")
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(6) })
        content.addView(actionRow, spacedParams(bottom = 10))

        val testRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        testRow.addView(actionButton("▧  Testo etiketën 40×30", true) { testLabel() },
            LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginEnd = dp(6) })
        testRow.addView(actionButton("▤  Testo faturën", false) { testReceipt() },
            LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginStart = dp(6) })
        content.addView(testRow, spacedParams(bottom = 12))

        val footer = TextView(this).apply {
            text = "UNIONLINE  ·  PRINT BRIDGE"
            textSize = 10f
            letterSpacing = 0.08f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(4))
        }
        content.addView(footer)
        setContentView(scroll)
    }

    private fun readableSpinnerAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            private fun style(view: View): View {
                (view as? TextView)?.apply {
                    textSize = 16f
                    setTextColor(0xFF172033.toInt())
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                return view
            }

            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                style(super.getView(position, convertView, parent))

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                style(super.getDropDownView(position, convertView, parent))
        }.also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
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
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else 0xFF263244.toInt())
        background = if (primary) rounded(0xFFB91524.toInt(), 16) else rounded(0xFFE8ECF2.toInt(), 16, 0xFFD5DCE6.toInt(), dp(1))
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
            labelSpinner.adapter = readableSpinnerAdapter(list)
            receiptSpinner.adapter = readableSpinnerAdapter(list)
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
        if (::protocolGroup.isInitialized && protocolGroup.checkedRadioButtonId == tsplRadioId) "tspl" else "tspl2"

    private fun saveSelections() {
        val editor = prefs.edit()
        if (::protocolGroup.isInitialized) editor.putString("label_protocol", selectedProtocol())
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
