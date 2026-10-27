package al.baumarket.unionbridge

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.text.Normalizer
import kotlin.math.roundToInt

object CommandBuilders {
    // TSPL uses millimetres and is widely shared by TSPL/TSPL2-compatible label printers.
    // 203 dpi is approximately 8 dots/mm.
    fun tspl(label: LabelPayload, copies: Int): ByteArray {
        // Layout calibrated as a first physical test for 40 x 30 mm at 203 dpi (8 dots/mm).
        // Full product name: up to two lines; thin divider; small SKU; moderate price; Code 128.
        val w = label.widthMm
        val h = label.heightMm
        fun clean(s: String) = s.replace("\"", "'").replace("\r", " ").replace("\n", " ").trim()
        val company = clean(label.company).take(24)
        val name = clean(label.name)
        val sku = clean(label.sku).take(24)
        val price = clean(label.price).take(16)
        val barcode = clean(label.barcode).filter { it.isLetterOrDigit() || it in "-._/" }.take(32)

        // Dot positions below are designed for 40 x 30 mm at 203 dpi.
        // If a different label size is requested, keep the layout inside safe margins.
        val b = StringBuilder()
        b.append("SIZE ${w} mm,${h} mm\r\n")
        b.append("GAP 2 mm,0 mm\r\n")
        b.append("DIRECTION 1\r\n")
        b.append("REFERENCE 0,0\r\n")
        b.append("CLS\r\n")

        // Product title is fitted into two compact lines. Use supplied explicit line break if present;
        // otherwise split at the nearest space around the midpoint, preserving the complete name.
        val titleLines = splitTitle(name, 28)
        val titleFont = "2"
        b.append("TEXT 12,8,\"$titleFont\",0,1,1,\"${clean(titleLines.first)}\"\r\n")
        if (titleLines.second.isNotBlank()) {
            b.append("TEXT 12,32,\"$titleFont\",0,1,1,\"${clean(titleLines.second)}\"\r\n")
        }
        // Thin divider below the title.
        b.append("BAR 12,57,296,1\r\n")
        // Small product code on left and moderately sized price on right.
        if (sku.isNotBlank()) b.append("TEXT 12,65,\"1\",0,1,1,\"$sku\"\r\n")
        if (price.isNotBlank()) b.append("TEXT 174,63,\"3\",0,1,1,\"$price\"\r\n")
        // Code 128 barcode; human-readable value printed separately in small text below.
        if (barcode.isNotBlank()) {
            b.append("BARCODE 12,91,\"128\",75,0,0,2,4,\"$barcode\"\r\n")
            b.append("TEXT 100,169,\"1\",0,1,1,\"$barcode\"\r\n")
        }
        b.append("PRINT ${copies.coerceIn(1,100)},1\r\n")
        return b.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun splitTitle(value: String, targetChars: Int): Pair<String, String> {
        val normalized = value.replace("\r", " ").replace("\n", " ").trim().replace(Regex("\\s+"), " ")
        if (normalized.length <= targetChars) return normalized to ""
        val splitAt = normalized.lastIndexOf(' ', targetChars.coerceAtMost(normalized.length - 1))
            .takeIf { it > 0 } ?: targetChars.coerceAtMost(normalized.length - 1)
        return normalized.substring(0, splitAt).trim() to normalized.substring(splitAt).trim()
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
