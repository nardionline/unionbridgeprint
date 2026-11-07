package al.baumarket.unionbridge

import org.json.JSONObject
import java.util.Locale

data class PrintPayload(
    val type: String,
    val printerMac: String?,
    val labels: List<LabelJob>,
    val receipt: ReceiptPayload?
) {
    companion object {
        fun parse(json: String): PrintPayload {
            val root = JSONObject(json)
            val type = root.optString("type", "").lowercase(Locale.ROOT)
            require(type == "label" || type == "receipt") { "type duhet të jetë label ose receipt" }
            val mac = root.optString("printerMac", "").takeIf { it.isNotBlank() }

            if (type == "label") {
                val jobs = mutableListOf<LabelJob>()
                val array = root.optJSONArray("labels")
                if (array != null) {
                    require(array.length() in 1..200) { "Shporta duhet të ketë 1 deri në 200 artikuj." }
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        jobs += parseLabel(item, root.optString("company", "UNIONLINE"), root.optInt("widthMm", 40), root.optInt("heightMm", 30), root.optString("language", "tspl2"))
                    }
                } else {
                    val item = root.optJSONObject("label") ?: root
                    jobs += parseLabel(item, root.optString("company", "UNIONLINE"), root.optInt("widthMm", 40), root.optInt("heightMm", 30), root.optString("language", "tspl2"))
                }
                require(jobs.isNotEmpty()) { "Nuk ka etiketa për printim." }
                val totalCopies = jobs.sumOf { it.copies }
                require(totalCopies <= 500) { "Shumë etiketa në një kërkesë (maksimumi 500 kopje)." }
                return PrintPayload(type, mac, jobs, null)
            }

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
            val receipt = ReceiptPayload(
                obj.optString("company", "UNIONLINE"),
                obj.optString("title", "FATURË"),
                obj.optString("number", ""),
                obj.optString("date", ""),
                items,
                obj.optString("subtotal", ""),
                obj.optString("total", ""),
                obj.optString("footer", "Faleminderit!")
            )
            return PrintPayload(type, mac, emptyList(), receipt)
        }

        private fun parseLabel(item: JSONObject, defaultCompany: String, defaultWidth: Int, defaultHeight: Int, defaultLanguage: String): LabelJob {
            val label = LabelPayload(
                item.optString("company", defaultCompany).ifBlank { defaultCompany },
                item.optString("name", ""),
                item.optString("sku", ""),
                item.optString("price", ""),
                item.optString("barcode", item.optString("sku", "")),
                item.optInt("widthMm", defaultWidth).coerceIn(20, 100),
                item.optInt("heightMm", defaultHeight).coerceIn(15, 100),
                item.optString("language", defaultLanguage).lowercase(Locale.ROOT)
            )
            require(label.name.isNotBlank() || label.sku.isNotBlank()) { "Artikulli nuk ka emër ose kod." }
            val copies = item.optInt("copies", item.optInt("qty", 1)).coerceIn(1, 100)
            return LabelJob(label, copies)
        }
    }
}

data class LabelJob(val label: LabelPayload, val copies: Int)
data class LabelPayload(
    val company: String, val name: String, val sku: String, val price: String,
    val barcode: String, val widthMm: Int, val heightMm: Int, val language: String
)
data class ReceiptItem(val name: String, val qty: String, val price: String, val total: String)
data class ReceiptPayload(
    val company: String, val title: String, val number: String, val date: String,
    val items: List<ReceiptItem>, val subtotal: String, val total: String, val footer: String
)
