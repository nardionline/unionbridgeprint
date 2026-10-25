package al.baumarket.unionbridge

import org.json.JSONObject
import java.util.Locale

data class PrintPayload(
    val type: String,
    val printerMac: String?,
    val copies: Int,
    val label: LabelPayload?,
    val receipt: ReceiptPayload?
) {
    companion object {
        fun parse(json: String): PrintPayload {
            val root = JSONObject(json)
            val type = root.optString("type", "").lowercase(Locale.ROOT)
            require(type == "label" || type == "receipt") { "type duhet të jetë label ose receipt" }
            val copies = root.optInt("copies", 1).coerceIn(1, 100)
            val mac = root.optString("printerMac", "").takeIf { it.isNotBlank() }
            return if (type == "label") {
                val item = root.optJSONObject("label") ?: root
                PrintPayload(type, mac, copies, LabelPayload(
                    item.optString("company", "UNIONLINE"),
                    item.optString("name", ""),
                    item.optString("sku", ""),
                    item.optString("price", ""),
                    item.optString("barcode", item.optString("sku", "")),
                    item.optInt("widthMm", 40).coerceIn(20, 100),
                    item.optInt("heightMm", 30).coerceIn(15, 100),
                    item.optString("language", "tspl2").lowercase(Locale.ROOT)
                ), null)
            } else {
                val obj = root.optJSONObject("receipt") ?: root
                val items = mutableListOf<ReceiptItem>()
                val array = obj.optJSONArray("items")
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val it = array.optJSONObject(i) ?: continue
                        items += ReceiptItem(
                            it.optString("name", ""),
                            it.optString("qty", "1"),
                            it.optString("price", ""),
                            it.optString("total", "")
                        )
                    }
                }
                PrintPayload(type, mac, copies, null, ReceiptPayload(
                    obj.optString("company", "UNIONLINE"),
                    obj.optString("title", "FATURË"),
                    obj.optString("number", ""),
                    obj.optString("date", ""),
                    items,
                    obj.optString("subtotal", ""),
                    obj.optString("total", ""),
                    obj.optString("footer", "Faleminderit!")
                ))
            }
        }
    }
}

data class LabelPayload(
    val company: String, val name: String, val sku: String, val price: String,
    val barcode: String, val widthMm: Int, val heightMm: Int, val language: String
)
data class ReceiptItem(val name: String, val qty: String, val price: String, val total: String)
data class ReceiptPayload(
    val company: String, val title: String, val number: String, val date: String,
    val items: List<ReceiptItem>, val subtotal: String, val total: String, val footer: String
)
