package com.passbridge.studio.model

import org.json.JSONArray
import org.json.JSONObject

data class PassField(
    val label: String,
    val value: String,
    val group: String = "Bilgiler",
    val role: String = ""
)

data class PassCard(
    val type: String = "generic",
    val issuer: String = "Kart sağlayıcısı",
    val title: String = "Kart",
    val background: String = "#17324D",
    val fields: List<PassField> = emptyList(),
    val barcodeType: String = "QR_CODE",
    val barcodeValue: String = "",
    val logoPath: String? = null,
    val heroPath: String? = null,
    val serialNumber: String = "",
    val originalFileName: String = "",
    val sourcePath: String? = null
) {
    fun toOverrides(): JSONObject {
        val arr = JSONArray()
        fields.forEach {
            arr.put(JSONObject().put("label", it.label).put("value", it.value)
                .put("role", it.role).put("back", it.group == "Arka yüz"))
        }
        val barcode = if (barcodeValue.isNotBlank())
            JSONObject().put("type", barcodeType).put("value", barcodeValue).put("alt", barcodeValue.take(80))
        else JSONObject.NULL

        return JSONObject()
            .put("type", type)
            .put("issuer", issuer)
            .put("title", title)
            .put("bg", background)
            .put("fields", arr)
            .put("barcode", barcode)
            .put("logoAlign", "center")
            .put("detailsAll", true)
            .put("layout", "rows")
    }
}

data class ServerConfig(
    val credentialsFound: Boolean = false,
    val clientEmail: String = "",
    val issuerId: String = "",
    val publicBaseUrl: String = "",
    val ready: Boolean = false,
    val apiKeyRequired: Boolean = false
)

data class ConvertResult(
    val saveUrl: String,
    val walletType: String,
    val warnings: List<String>
)
