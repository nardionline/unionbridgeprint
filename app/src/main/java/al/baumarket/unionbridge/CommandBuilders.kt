package al.baumarket.unionbridge

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.text.Normalizer
import kotlin.math.roundToInt

object CommandBuilders {
    // TSPL uses millimetres and is widely shared by TSPL/TSPL2-compatible label printers.
    // 203 dpi is approximately 8 dots/mm.
    fun tspl(label: LabelPayload, copies: Int): ByteArray {
        // Conservative layout for 40 x 30 mm labels at 203 dpi (~8 dots/mm).
        // Keep every text field inside the 320-dot printable width.
        val w = label.widthMm
        val h = label.heightMm
        fun clean(s: String) = s.replace("\"", "'").replace("\r", " ").replace("\n", " ").trim()

        val company = clean(label.company).take(20)
        val name = clean(label.name)
        val sku = clean(label.sku).take(20)
        val price = clean(label.price).take(12)
        val barcode = clean(label.barcode).filter { it.isLetterOrDigit() || it in "-._/" }.take(20)

        val b = StringBuilder()
        b.append("SIZE ${w} mm,${h} mm\r\n")
        b.append("GAP 2 mm,0 mm\r\n")
        b.append("DIRECTION 1\r\n")
        b.append("REFERENCE 0,0\r\n")
        b.append("CLS\r\n")

        // TSPL font 2 is approximately 12 dots wide; 22 chars fit safely.
        val titleLines = splitTitle(name, 22)
        if (titleLines.first.isNotBlank()) {
            b.append("TEXT 16,5,\"2\",0,1,1,\"${clean(titleLines.first)}\"\r\n")
        }
        if (titleLines.second.isNotBlank()) {
            b.append("TEXT 16,25,\"2\",0,1,1,\"${clean(titleLines.second)}\"\r\n")
        }
        b.append("BAR 16,48,288,1\r\n")

        // Font 4 is wider than the earlier estimate. Use its real conservative
        // width so the last digit and the unit remain inside the right edge.
        if (price.isNotBlank()) {
            val priceCharWidth = 24
            val rightEdge = 304
            val priceX = (rightEdge - price.length * priceCharWidth).coerceAtLeast(16)
            b.append("TEXT $priceX,64,\"4\",0,1,1,\"$price\"\r\n")
        }

        if (sku.isNotBlank()) {
            b.append("TEXT 16,104,\"2\",0,1,1,\"$sku\"\r\n")
        }

        if (barcode.isNotBlank()) {
            // Keep barcode width bounded; human-readable digits use compact font 1.
            b.append("BARCODE 20,130,\"128\",34,0,0,2,3,\"$barcode\"\r\n")
            val barcodeTextX = ((320 - barcode.length * 8) / 2).coerceAtLeast(8)
            b.append("TEXT $barcodeTextX,171,\"1\",0,1,1,\"$barcode\"\r\n")
        }
        b.append("PRINT ${copies.coerceIn(1,100)},1\r\n")
        return b.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun splitTitle(value: String, targetChars: Int): Pair<String, String> {
        // Limit both lines to the printable width. The old implementation put the entire
        // remainder of a long name on line 2, making centeredX() coerce an invalid range
        // and crashing the app when the test-label button was pressed.
        val normalized = value.replace("\r", " ").replace("\n", " ").trim()
            .replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) return "" to ""
        val words = normalized.split(' ')
        val first = StringBuilder()
        val second = StringBuilder()
        var current = first
        for (word in words) {
            if (current.isEmpty()) {
                current.append(word.take(targetChars))
            } else if (current.length + 1 + word.length <= targetChars) {
                current.append(' ').append(word)
            } else if (current === first && second.isEmpty()) {
                current = second
                current.append(word.take(targetChars))
            } else {
                // Two lines maximum; ignore overflow to keep every coordinate within bounds.
                break
            }
        }
        return first.toString() to second.toString()
    }

    fun escPos(receipt: ReceiptPayload, copies: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun bytes(vararg values: Int) { values.forEach { out.write(it) } }
        fun text(s: String) {
            // Safe fallback for printers with unknown code pages. Replace Albanian diacritics
            // instead of emitting garbled bytes; a code-page selector can be added per model.
            out.write(asciiSafe(s).toByteArray(Charset.forName("CP437")))
        }
        repeat(copies) {
            bytes(0x1B, 0x40) // initialize
            bytes(0x1B, 0x61, 0x01); bytes(0x1B, 0x45, 0x01)
            text(receipt.company.ifBlank { "UNIONLINE" }); bytes(0x0A)
            bytes(0x1B, 0x45, 0x00)
            text(receipt.title); bytes(0x0A)
            bytes(0x1B, 0x61, 0x00)
            if (receipt.number.isNotBlank()) { text("Nr: ${receipt.number}"); bytes(0x0A) }
            if (receipt.date.isNotBlank()) { text("Data: ${receipt.date}"); bytes(0x0A) }
            text("--------------------------------"); bytes(0x0A)
            receipt.items.forEach { item ->
                val name = asciiSafe(item.name).take(32)
                text(name); bytes(0x0A)
                val detail = listOf(item.qty, item.price, item.total).filter { it.isNotBlank() }.joinToString("   ")
                if (detail.isNotBlank()) { text("  $detail"); bytes(0x0A) }
            }
            text("--------------------------------"); bytes(0x0A)
            if (receipt.subtotal.isNotBlank()) { text("Nentotali: ${receipt.subtotal}"); bytes(0x0A) }
            bytes(0x1B, 0x45, 0x01)
            text("TOTAL: ${receipt.total}"); bytes(0x0A)
            bytes(0x1B, 0x45, 0x00)
            bytes(0x0A)
            bytes(0x1B, 0x61, 0x01)
            text(receipt.footer); bytes(0x0A, 0x0A, 0x0A)
            bytes(0x1B, 0x61, 0x00)
            // GS V 0: full cut where supported; ignored by printers without a cutter.
            bytes(0x1D, 0x56, 0x00)
        }
        return out.toByteArray()
    }

    private fun asciiSafe(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace("ë", "e", true).replace("ç", "c", true)
            .filter { it == '\n' || it == '\r' || it.code in 32..126 }
    }
}
