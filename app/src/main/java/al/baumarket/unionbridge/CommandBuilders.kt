package al.baumarket.unionbridge

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.text.Normalizer
import kotlin.math.roundToInt

object CommandBuilders {
    // TSPL uses millimetres and is widely shared by TSPL/TSPL2-compatible label printers.
    // 203 dpi is approximately 8 dots/mm.
    fun tspl(label: LabelPayload, copies: Int): ByteArray {
        // Compact 40 x 30 mm label layout for TSPL/TSPL2-compatible printers at ~203 dpi.
        // Product name is centered and smaller to prevent clipping; large right-aligned price,
        // SKU below price and slightly inset from the left; shorter centered Code 128 barcode.
        val w = label.widthMm
        val h = label.heightMm
        fun clean(s: String) = s.replace("\"", "'").replace("\r", " ").replace("\n", " ").trim()
        val company = clean(label.company).take(24)
        val name = clean(label.name)
        val sku = clean(label.sku).take(24)
        val rawPrice = clean(label.price).take(16)
        val price = if (rawPrice.isNotBlank() && !rawPrice.endsWith(" L", ignoreCase = true) && !rawPrice.endsWith("L", ignoreCase = true)) "$rawPrice L" else rawPrice
        val barcode = clean(label.barcode).filter { it.isLetterOrDigit() || it in "-._/" }.take(32)

        // Centered layout for 40 x 30 mm at 203 dpi (approximately 8 dots/mm).
        // Keep the full product title on up to two centered lines and emphasize the price.
        val b = StringBuilder()
        b.append("SIZE ${w} mm,${h} mm\r\n")
        b.append("GAP 2 mm,0 mm\r\n")
        b.append("DIRECTION 1\r\n")
        b.append("REFERENCE 0,0\r\n")
        b.append("CLS\r\n")

        // Font 1 is smaller than font 2; split into two wider lines to avoid right-edge clipping.
        val titleLines = splitTitle(name, 25)
        fun centeredX(text: String, charWidth: Int, minX: Int = 16): Int {
            // Use a safe 16-dot margin on both sides of the 320-dot label.
            return (((320 - text.length * charWidth) / 2).coerceIn(minX, 320 - minX - text.length * charWidth))
        }
        val line1 = titleLines.first
        val line2 = titleLines.second
        if (line1.isNotBlank()) {
            b.append("TEXT ${centeredX(line1, 10)},5,\"2\",0,1,1,\"${clean(line1)}\"\r\n")
        }
        if (line2.isNotBlank()) {
            b.append("TEXT ${centeredX(line2, 10)},25,\"2\",0,1,1,\"${clean(line2)}\"\r\n")
        }
        // Thin divider below the title.
        b.append("BAR 16,48,288,1\r\n")
        // Large price right-aligned. SKU sits on the next line, inset from the left edge.
        if (price.isNotBlank()) {
            val priceCharWidth = 16
            val priceRightEdge = 296 // reserve 24 dots on the right to prevent clipping of the final L
            val priceX = (priceRightEdge - price.length * priceCharWidth).coerceAtLeast(24)
            b.append("TEXT $priceX,57,\"4\",0,1,1,\"$price\"\r\n")
        }
        if (sku.isNotBlank()) b.append("TEXT 88,98,\"2\",0,1,1,\"$sku\"\r\n")
        // Shorter barcode to leave more whitespace and keep the label compact.
        if (barcode.isNotBlank()) {
            b.append("BARCODE 54,123,\"128\",38,0,0,2,3,\"$barcode\"\r\n")
            b.append("TEXT ${centeredX(barcode, 7)},166,\"1\",0,1,1,\"$barcode\"\r\n")
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
