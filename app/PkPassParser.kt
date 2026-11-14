package com.passbridge.studio.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.passbridge.studio.model.PassCard
import com.passbridge.studio.model.PassField
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

class PkPassParser(private val context: Context) {
    companion object {
        private const val MAX_ARCHIVE = 25L * 1024 * 1024
        private const val MAX_UNCOMPRESSED = 80L * 1024 * 1024
        private const val MAX_JSON = 2L * 1024 * 1024
        private const val MAX_IMAGE = 4L * 1024 * 1024
        private val groups = listOf(
            "headerFields" to "Üst bilgi",
            "primaryFields" to "Ana bilgiler",
            "secondaryFields" to "İkincil bilgiler",
            "auxiliaryFields" to "Ek bilgiler",
            "backFields" to "Arka yüz"
        )
        private val roleHints = mapOf(
            "holder" to listOf("name", "holder", "ticketholdername", "passengername", "passenger", "katilimci", "yolcu", "adsoyad", "isim"),
            "ticketNumber" to listOf("ticketnumber", "ticketno", "biletno", "ticketid"),
            "venue" to listOf("venue", "location", "mekan", "yer"),
            "address" to listOf("address", "adres"),
            "date" to listOf("date", "eventdate", "tarih", "datetime", "departure", "kalkis", "kalkissaati"),
            "seat" to listOf("seat", "seatnumber", "koltuk"),
            "row" to listOf("row", "sira"),
            "section" to listOf("section", "bolum", "block", "blok"),
            "gate" to listOf("gate", "kapi"),
            "origin" to listOf("origin", "from", "origincode", "departureairport", "nereden"),
            "destination" to listOf("destination", "to", "destinationcode", "arrivalairport", "nereye", "varis"),
            "flight" to listOf("flight", "flightnumber", "ucusno", "ucus"),
            "confirmation" to listOf("confirmation", "confirmationcode", "pnr", "bookingreference", "rezervasyon"),
            "accountId" to listOf("accountid", "membershipnumber", "cardnumber", "uyeno", "kartno", "memberid"),
            "accountName" to listOf("accountname", "membername", "uyeadi")
        )
    }

    fun parse(bytes: ByteArray, filename: String): PassCard {
        require(bytes.size <= MAX_ARCHIVE) { "PKPASS dosyası 25 MB sınırını aşıyor." }
        val sourceFile = File(context.cacheDir, "source_${System.currentTimeMillis()}.pkpass")
        sourceFile.writeBytes(bytes)
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val name = entry.name.replace('\\', '/').removePrefix("./")
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= MAX_UNCOMPRESSED) { "PKPASS açılmış boyutu 80 MB sınırını aşıyor." }
                        out.write(buffer, 0, n)
                    }
                    entries.putIfAbsent(name, out.toByteArray())
                    entries.putIfAbsent(name.lowercase(), out.toByteArray())
                }
                zip.closeEntry()
            }
        }
        val jsonBytes = find(entries, "pass.json") ?: error("Arşivde pass.json bulunamadı.")
        require(jsonBytes.size <= MAX_JSON) { "pass.json dosyası çok büyük." }
        val jsonText = if (jsonBytes.size >= 2 && ((jsonBytes[0] == 0xff.toByte() && jsonBytes[1] == 0xfe.toByte()) ||
                    (jsonBytes[0] == 0xfe.toByte() && jsonBytes[1] == 0xff.toByte())))
            jsonBytes.toString(Charsets.UTF_16) else jsonBytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val pass = JSONObject(jsonText)

        val (typeLabel, type) = detectType(pass)
        val section = when (type) {
            "event" -> "eventTicket"
            "loyalty" -> "storeCard"
            "offer" -> "coupon"
            "flight", "transit" -> "boardingPass"
            else -> "generic"
        }
        val passSection = pass.optJSONObject(section) ?: JSONObject()
        val fields = mutableListOf<PassField>()
        groups.forEach { (key, group) ->
            val arr = passSection.optJSONArray(key) ?: return@forEach
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val value = plain(item.opt("value"))
                if (value.isNotBlank()) {
                    val label = plain(item.opt("label")).ifBlank { group }
                    val fieldKey = plain(item.opt("key"))
                    val role = guessRole(fieldKey, label)
                    fields += PassField(label, value, group, role)
                }
            }
        }
        val relevantDate = plain(pass.opt("relevantDate"))
        if (relevantDate.isNotBlank() && fields.none { it.role == "date" }) {
            fields += PassField("Tarih", relevantDate, "Ana bilgiler", "date")
        }
        val barcode = extractBarcode(pass)
        val assetsDir = File(context.cacheDir, "pass_assets").apply { mkdirs() }
        val logo = saveImage(findAny(entries, listOf("logo@3x.png", "logo@2x.png", "logo.png"))
            ?: findAny(entries, listOf("icon@3x.png", "icon@2x.png", "icon.png")), assetsDir, "logo")
        val hero = saveImage(findAny(entries, listOf("strip@3x.png", "strip@2x.png", "strip.png"))
            ?: findAny(entries, listOf("background@3x.png", "background@2x.png", "background.png")), assetsDir, "hero")

        return PassCard(
            type = type,
            issuer = plain(pass.opt("organizationName")).ifBlank { plain(pass.opt("logoText")).ifBlank { "Kart sağlayıcısı" } },
            title = plain(pass.opt("description")).ifBlank { plain(pass.opt("logoText")).ifBlank { typeLabel } },
            background = normalizeColor(plain(pass.opt("backgroundColor"))),
            fields = fields,
            barcodeType = barcode.first,
            barcodeValue = barcode.second,
            logoPath = logo,
            heroPath = hero,
            serialNumber = plain(pass.opt("serialNumber")),
            originalFileName = filename,
            sourcePath = sourceFile.absolutePath
        )
    }

    private fun find(entries: Map<String, ByteArray>, target: String): ByteArray? {
        entries[target]?.let { return it }
        entries.entries.firstOrNull { it.key.substringAfterLast('/').equals(target, true) }?.let { return it.value }
        return null
    }

    private fun findAny(entries: Map<String, ByteArray>, names: List<String>): ByteArray? {
        names.forEach { name ->
            val data = find(entries, name)
            if (data != null && data.size <= MAX_IMAGE) return data
        }
        return null
    }

    private fun saveImage(bytes: ByteArray?, dir: File, prefix: String): String? {
        if (bytes == null || bytes.size > MAX_IMAGE) return null
        val bitmap: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val file = File(dir, "${prefix}_${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file.absolutePath
    }

    private fun detectType(pass: JSONObject): Pair<String, String> {
        val boarding = pass.optJSONObject("boardingPass")
        if (boarding != null) {
            return when (boarding.optString("transitType")) {
                "PKTransitTypeAir" -> "Uçuş kartı" to "flight"
                "PKTransitTypeTrain" -> "Ulaşım kartı · Tren" to "transit"
                "PKTransitTypeBoat" -> "Ulaşım kartı · Feribot" to "transit"
                "PKTransitTypeBus" -> "Ulaşım kartı · Otobüs" to "transit"
                else -> "Ulaşım kartı" to "transit"
            }
        }
        return when {
            pass.optJSONObject("eventTicket") != null -> "Etkinlik bileti" to "event"
            pass.optJSONObject("storeCard") != null -> "Sadakat kartı" to "loyalty"
            pass.optJSONObject("coupon") != null -> "Teklif / kupon" to "offer"
            pass.optJSONObject("generic") != null -> "Genel kart" to "generic"
            else -> "Tanımlanamayan kart" to "generic"
        }
    }

    private fun extractBarcode(pass: JSONObject): Pair<String, String> {
        val formats = mapOf(
            "PKBarcodeFormatQR" to "QR_CODE", "PKBarcodeFormatPDF417" to "PDF_417",
            "PKBarcodeFormatCode128" to "CODE_128", "PKBarcodeFormatAztec" to "AZTEC",
            "PKBarcodeFormatEAN13" to "EAN_13", "PKBarcodeFormatUPCE" to "UPC_A",
            "PKBarcodeFormatCodabar" to "CODABAR"
        )
        val arr = pass.optJSONArray("barcodes")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val value = plain(item.opt("message"))
                if (value.isNotBlank()) return (formats[item.optString("format")] ?: "QR_CODE") to value
            }
        }
        val old = pass.optJSONObject("barcode")
        if (old != null) {
            val value = plain(old.opt("message"))
            if (value.isNotBlank()) return (formats[old.optString("format")] ?: "QR_CODE") to value
        }
        return "QR_CODE" to ""
    }

    private fun guessRole(key: String, label: String): String {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val k = norm(key); val l = norm(label)
        return roleHints.entries.firstOrNull { (_, hints) -> hints.any { norm(it) == k || norm(it) == l } }?.key ?: ""
    }

    private fun plain(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is JSONObject -> listOf("value", "string", "number").firstNotNullOfOrNull {
            if (value.has(it)) plain(value.opt(it)) else null
        } ?: value.toString()
        is org.json.JSONArray -> (0 until minOf(value.length(), 5)).joinToString(", ") { plain(value.opt(it)) }
        else -> value.toString()
    }

    private fun normalizeColor(raw: String): String {
        if (raw.matches(Regex("#[0-9a-fA-F]{6}"))) return raw.uppercase()
        val match = Regex("""rgb\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)""").matchEntire(raw) ?: return "#17324D"
        return "#" + match.groupValues.drop(1).joinToString("") { it.toInt().coerceIn(0,255).toString(16).padStart(2,'0') }.uppercase()
    }
}
