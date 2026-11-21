package al.baumarket.unionbridge

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.text.Normalizer
import kotlin.math.roundToInt

object CommandBuilders {
    // TSPL uses millimetres and is widely shared by TSPL/TSPL2-compatible label printers.
    // 203 dpi is approximately 8 dots/mm.
    fun tspl(label: LabelPayload, copies: Int): ByteArray {
        if (label.language.equals("tspl", ignoreCase = true)) {
            return tsplWinDev(label, copies)
        }

        // Existing TSPL2 implementation remains unchanged.
        val w = label.widthMm
        val h = label.heightMm
        fun clean(s: String) = s.replace("\"", "'").replace("\r", " ").replace("\n", " ").trim()

        val name = clean(label.name)
        val sku = clean(label.sku).take(20)
        val price = clean(label.price)
            .replace(Regex("[\\u00A0\\u202F\\u2009\\u2007]"), ",")
            .replace("，", ",")
            .filter { it.code in 32..126 }
            .take(12)
        val barcode = clean(label.barcode).filter { it.isLetterOrDigit() || it in "-._/" }.take(20)

        val b = StringBuilder()
        b.append("SIZE ${w} mm,${h} mm\r\n")
        b.append("GAP 2 mm,0 mm\r\n")
        b.append("DIRECTION 1\r\n")
        b.append("REFERENCE 0,0\r\n")
        b.append("CLS\r\n")

        val titleLines = splitTitle(name, 22)
        if (titleLines.first.isNotBlank()) {
            b.append("TEXT 16,5,\"2\",0,1,1,\"${clean(titleLines.first)}\"\r\n")
        }
        if (titleLines.second.isNotBlank()) {
            b.append("TEXT 16,25,\"2\",0,1,1,\"${clean(titleLines.second)}\"\r\n")
        }
        b.append("BAR 16,48,288,1\r\n")

        if (price.isNotBlank()) {
            val priceCharWidth = 24
            val rightEdge = 304
            val priceX = (rightEdge - price.length * priceCharWidth).coerceAtLeast(16)
            b.append("TEXT $priceX,64,\"4\",0,1,1,\"$price\"\r\n")
        }
        if (sku.isNotBlank()) b.append("TEXT 16,104,\"2\",0,1,1,\"$sku\"\r\n")
        if (barcode.isNotBlank()) {
            b.append("BARCODE 20,130,\"128\",34,0,0,2,3,\"$barcode\"\r\n")
            val barcodeTextX = ((320 - barcode.length * 8) / 2).coerceAtLeast(8)
            b.append("TEXT $barcodeTextX,171,\"1\",0,1,1,\"$barcode\"\r\n")
        }
        b.append("PRINT ${copies.coerceIn(1,100)},1\r\n")
        return b.toString().toByteArray(Charsets.US_ASCII)
    }

    /**
     * MHT-P29L profile based on the user's proven WinDev TSPL commands.
     * Only visual refinements: smaller text, divider under title and shorter barcode.
     */
    private fun tsplWinDev(label: LabelPayload, copies: Int): ByteArray {
        fun clean(value: String): String = value
            .replace("\"", "")
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(Regex("[\\u00A0\\u202F\\u2009\\u2007]"), ",")
            .replace("，", ",")
            .trim()
            .filter { it.code in 32..126 }

        val name = clean(label.name)
        val first = name.take(25)
        val second = if (name.length > 25) name.drop(25).take(25) else ""
        val price = clean(label.price)
            .replace(Regex("(?i)\\s*lek\\s*$"), "")
            .removeSuffix(" L")
            .trim()
        val sku = clean(label.sku)
        val barcode = clean(label.barcode).replace(" ", "")

        val b = StringBuilder()
        b.append("SIZE 40 mm,30 mm\r\n")
        b.append("DIRECTION 0\r\n")
        b.append("GAP 2 mm,0\r\n")
        b.append("DENSITY 8\r\n")
        b.append("CLS\r\n")
        if (first.isNotBlank()) b.append("TEXT 4,13,\"2\",0,1,1,\"$first\"\r\n")
        if (second.isNotBlank()) b.append("TEXT 4,35,\"2\",0,1,1,\"$second\"\r\n")
        b.append("BAR 4,60,312,1\r\n")
        if (price.isNotBlank()) b.append("TEXT 120,70,\"2\",0,2,2,\"$price L\"\r\n")
        if (sku.isNotBlank()) b.append("TEXT 4,120,\"2\",0,1,1,\"$sku\"\r\n")
        if (barcode.isNotBlank()) b.append("BARCODE 40,150,\"128\",50,1,0,2,2,\"$barcode\"\r\n")
        b.append("PRINT ${copies.coerceIn(1,100)}\r\n")
        return b.toString().toByteArray(Charsets.US_ASCII)
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
