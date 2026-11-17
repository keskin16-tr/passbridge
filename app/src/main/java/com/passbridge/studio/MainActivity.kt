package com.passbridge.studio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.passbridge.studio.model.PassCard
import com.passbridge.studio.model.PassField
import com.passbridge.studio.model.ServerConfig
import com.passbridge.studio.network.WalletApi
import com.passbridge.studio.parser.PkPassParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("passbridge", MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF83B8FF), secondary = Color(0xFF8ED8C5),
                background = Color(0xFF0B1220), surface = Color(0xFF131F31)
            )) {
                PassBridgeApp(
                    initialUrl = prefs.getString("base_url", "") ?: "",
                    initialKey = prefs.getString("api_key", "") ?: "",
                    onSavePrefs = { url, key ->
                        prefs.edit().putString("base_url", url).putString("api_key", key).apply()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassBridgeApp(initialUrl: String, initialKey: String, onSavePrefs: (String, String) -> Unit) {
    val context = LocalContext.current
    val api = remember { WalletApi() }
    var baseUrl by remember { mutableStateOf(initialUrl) }
    var apiKey by remember { mutableStateOf(initialKey) }
    var issuerId by remember { mutableStateOf("") }
    var card by remember { mutableStateOf<PassCard?>(null) }
    var serverConfig by remember { mutableStateOf(ServerConfig()) }
    var serviceFile by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf("Bir .pkpass dosyası seçerek başlayın.") }
    var busy by remember { mutableStateOf(false) }
    var saveUrl by remember { mutableStateOf("") }
    var warnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var showSettings by remember { mutableStateOf(false) }

    val passPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            status = "PKPASS analiz ediliyor…"
            (context as? ComponentActivity)?.lifecycleScope?.launch {
                try {
                    val parsed = withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("Dosya okunamadı.")
                        val name = uri.lastPathSegment ?: "wallet.pkpass"
                        PkPassParser(context).parse(bytes, name)
                    }
                    card = parsed
                    saveUrl = ""
                    warnings = emptyList()
                    status = "PKPASS başarıyla çözümlendi."
                } catch (e: Exception) {
                    status = e.message ?: "PKPASS okunamadı."
                } finally { busy = false }
            }
        }
    }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val file = File(context.cacheDir, "service-account-upload.json")
                context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use(input::copyTo) }
                serviceFile = file
                status = "Service Account JSON seçildi. Sunucuya yüklemek için Ayarlar'da Kaydet'e basın."
            } catch (e: Exception) { status = e.message ?: "Anahtar dosyası okunamadı." }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PassBridge Studio Pro", fontWeight = FontWeight.Bold)
                        Text("Apple Wallet → Google Wallet", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = { IconButton(onClick = { showSettings = !showSettings }) { Icon(Icons.Default.Settings, "Ayarlar") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF101B2C))
            )
        },
        containerColor = Color(0xFF0B1220)
    ) { padding ->
        if (showSettings) {
            SettingsScreen(
                modifier = Modifier.padding(padding),
                baseUrl = baseUrl, onUrl = { baseUrl = it },
                apiKey = apiKey, onApiKey = { apiKey = it },
                issuerId = issuerId, onIssuer = { issuerId = it },
                config = serverConfig, status = status,
                onPickKey = { keyPicker.launch(arrayOf("application/json", "text/json", "*/*")) },
                onRefresh = {
                    if (baseUrl.isBlank()) { status = "Önce sunucu adresini girin."; return@SettingsScreen }
                    busy = true
                    (context as? ComponentActivity)?.lifecycleScope?.launch {
                        try {
                            val cfg = withContext(Dispatchers.IO) { api.getConfig(baseUrl.trimEnd('/'), apiKey) }
                            serverConfig = cfg
                            issuerId = cfg.issuerId
                            status = if (cfg.ready) "Sunucu ve Google Wallet ayarları hazır." else "Sunucuya bağlanıldı; Google Wallet ayarları tamamlanmalı."
                        } catch (e: Exception) { status = e.message ?: "Bağlantı hatası." }
                        finally { busy = false }
                    }
                },
                onSave = {
                    if (baseUrl.isBlank()) { status = "Sunucu adresi gerekli."; return@SettingsScreen }
                    onSavePrefs(baseUrl.trimEnd('/'), apiKey)
                    busy = true
                    (context as? ComponentActivity)?.lifecycleScope?.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                if (issuerId.isNotBlank()) api.saveIssuer(baseUrl.trimEnd('/'), apiKey, issuerId)
                                serviceFile?.let { api.uploadServiceAccount(baseUrl.trimEnd('/'), apiKey, it) }
                            }
                            serverConfig = withContext(Dispatchers.IO) { api.getConfig(baseUrl.trimEnd('/'), apiKey) }
                            status = "Ayarlar sunucuya kaydedildi."
                        } catch (e: Exception) { status = e.message ?: "Ayarlar kaydedilemedi." }
                        finally { busy = false }
                    }
                },
                busy = busy
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF16263D)), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Cüzdan dönüştürücü", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("PKPASS dosyanı cihazında çözümle, kart bilgilerini düzenle ve Google Wallet'a aktar.", color = Color(0xFFB9C7D9))
                            Button(onClick = { passPicker.launch(arrayOf("application/vnd.apple.pkpass", "application/zip", "*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("PKPASS dosyası seç")
                            }
                        }
                    }
                }
                item { StatusLine(status, busy) }
                val current = card
                if (current != null) {
                    item { PassPreview(current) }
                    item {
                        Text("Google Wallet kart bilgileri", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Apple pass türü otomatik eşleştirilir. Alanları aktarım öncesi düzenleyebilirsin.", color = Color(0xFF9EADC2), fontSize = 12.sp)
                    }
                    item {
                        OutlinedTextField(value = current.issuer, onValueChange = { card = current.copy(issuer = it) }, label = { Text("Sağlayıcı / Kurum") }, modifier = Modifier.fillMaxWidth())
                    }
                    item {
                        OutlinedTextField(value = current.title, onValueChange = { card = current.copy(title = it) }, label = { Text("Kart başlığı") }, modifier = Modifier.fillMaxWidth())
                    }
                    item {
                        OutlinedTextField(value = current.background, onValueChange = { card = current.copy(background = it) }, label = { Text("Arka plan rengi (#RRGGBB)") }, modifier = Modifier.fillMaxWidth())
                    }
                    itemsIndexed(current.fields) { index, field ->
                        FieldEditor(field, onChange = { changed ->
                            card = current.copy(fields = current.fields.toMutableList().also { it[index] = changed })
                        }, onDelete = {
                            card = current.copy(fields = current.fields.toMutableList().also { it.removeAt(index) })
                        })
                    }
                    item {
                        OutlinedButton(onClick = { card = current.copy(fields = current.fields + PassField("Yeni alan", "", "Ana bilgiler")) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Alan ekle")
                        }
                    }
                    item {
                        OutlinedTextField(value = current.barcodeValue, onValueChange = { card = current.copy(barcodeValue = it) }, label = { Text("Barkod değeri") }, modifier = Modifier.fillMaxWidth())
                    }
                    item {
                        Button(
                            onClick = {
                                if (baseUrl.isBlank()) { status = "Ayarlar bölümünden Flask sunucu adresini girin."; showSettings = true; return@Button }
                                busy = true; status = "Google Wallet kartı hazırlanıyor…"
                                (context as? ComponentActivity)?.lifecycleScope?.launch {
                                    try {
                                        val result = withContext(Dispatchers.IO) { api.convert(baseUrl.trimEnd('/'), apiKey, current) }
                                        saveUrl = result.saveUrl
                                        warnings = result.warnings
                                        status = if (saveUrl.isNotBlank()) "Google Wallet kayıt bağlantısı oluşturuldu." else "Sunucu yanıtında kayıt bağlantısı bulunamadı."
                                    } catch (e: Exception) { status = e.message ?: "Dönüştürme başarısız." }
                                    finally { busy = false }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Wallet, null); Spacer(Modifier.width(8.dp)); Text("Google Wallet'a dönüştür")
                        }
                    }
                    if (saveUrl.isNotBlank()) item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF16352F))) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF8ED8C5))
                                    Spacer(Modifier.width(8.dp)); Text("Dönüştürme hazır", fontWeight = FontWeight.Bold)
                                }
                                Text("Google Wallet bağlantısını açarak kartı hesabına ekle.")
                                Button(onClick = {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(saveUrl))) }
                                        .onFailure { status = "Bağlantı açılamadı: ${it.message}" }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Google Wallet'ta aç") }
                            }
                        }
                    }
                    if (warnings.isNotEmpty()) item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2D1D))) {
                            Column(Modifier.padding(14.dp)) {
                                Text("Uyarılar", fontWeight = FontWeight.Bold)
                                warnings.forEach { Text("• $it", fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    modifier: Modifier, baseUrl: String, onUrl: (String) -> Unit,
    apiKey: String, onApiKey: (String) -> Unit,
    issuerId: String, onIssuer: (String) -> Unit,
    config: ServerConfig, status: String, onPickKey: () -> Unit,
    onRefresh: () -> Unit, onSave: () -> Unit, busy: Boolean
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                Spacer(Modifier.width(8.dp)); Text("Sunucu ve Wallet ayarları", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        item { Text("Flask API adresi", color = Color(0xFFB9C7D9)) }
        item { OutlinedTextField(baseUrl, onUrl, label = { Text("https://alanadiniz.com") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { OutlinedTextField(apiKey, onApiKey, label = { Text("API Key (varsa)") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { OutlinedTextField(issuerId, onIssuer, label = { Text("Google Wallet Issuer ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
        item {
            OutlinedButton(onClick = onPickKey, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CloudUpload, null); Spacer(Modifier.width(8.dp)); Text("Service Account JSON seç")
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF16263D))) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Sunucu durumu", fontWeight = FontWeight.Bold)
                    Text("Kimlik bilgisi: ${if (config.credentialsFound) "Yüklü" else "Yüklenmedi"}")
                    Text("Hesap: ${config.clientEmail.ifBlank { "—" }}", fontSize = 12.sp)
                    Text("Issuer: ${config.issuerId.ifBlank { "—" }}")
                    Text("Herkese açık medya HTTPS: ${if (config.publicBaseUrl.startsWith("https://")) "Hazır" else "Eksik"}")
                }
            }
        }
        item { Button(onClick = onRefresh, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Sunucu durumunu kontrol et") } }
        item { Button(onClick = onSave, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Ayarları sunucuya kaydet") } }
        item { Text(status, color = Color(0xFF9EADC2), fontSize = 12.sp) }
        item {
            Text("Güvenlik: Service Account özel anahtarı APK içine yazılmaz. Seçtiğin JSON, TLS üzerinden kendi Flask sunucuna yüklenir; cihazda yalnızca geçici önbellek kopyası tutulur.", color = Color(0xFFE6C58B), fontSize = 12.sp)
        }
    }
}

@Composable
private fun StatusLine(status: String, busy: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF8ED8C5), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(status, color = Color(0xFFB9C7D9), fontSize = 12.sp)
    }
}

@Composable
private fun PassPreview(card: PassCard) {
    val bg = runCatching { Color(android.graphics.Color.parseColor(card.background)) }.getOrDefault(Color(0xFF17324D))
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = bg), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            card.logoPath?.let { path ->
                val bitmap = remember(path) { android.graphics.BitmapFactory.decodeFile(path) }
                if (bitmap != null) Image(bitmap.asImageBitmap(), null, modifier = Modifier.size(54.dp))
            }
            Text(card.issuer, color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
            Text(card.title, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            card.heroPath?.let { path ->
                val bitmap = remember(path) { android.graphics.BitmapFactory.decodeFile(path) }
                if (bitmap != null) Image(bitmap.asImageBitmap(), null, modifier = Modifier.fillMaxWidth().height(100.dp))
            }
            card.fields.take(4).forEach { field ->
                Column {
                    Text(field.label, color = Color.White.copy(alpha = .65f), fontSize = 10.sp)
                    Text(field.value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (card.barcodeValue.isNotBlank()) Text("BARKOD  •  ${card.barcodeValue.take(42)}", color = Color.White, fontSize = 11.sp)
            Text("APPLE PASS ÖNİZLEMESİ · YAKLAŞIK", color = Color.White.copy(alpha = .55f), fontSize = 9.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldEditor(field: PassField, onChange: (PassField) -> Unit, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF131F31))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(field.group, color = Color(0xFF8ED8C5), fontSize = 11.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text("Sil") }
            }
            OutlinedTextField(field.label, { onChange(field.copy(label = it)) }, label = { Text("Etiket") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(field.value, { onChange(field.copy(value = it)) }, label = { Text("Değer") }, modifier = Modifier.fillMaxWidth())
            var expanded by remember { mutableStateOf(false) }
            val roles = listOf("" to "Otomatik / detay", "holder" to "Kart sahibi", "ticketNumber" to "Bilet numarası", "venue" to "Mekan", "address" to "Adres", "date" to "Tarih", "seat" to "Koltuk", "row" to "Sıra", "section" to "Bölüm", "gate" to "Kapı", "origin" to "Kalkış", "destination" to "Varış", "flight" to "Uçuş numarası", "confirmation" to "Rezervasyon", "accountId" to "Üye numarası", "accountName" to "Üye adı")
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = roles.firstOrNull { it.first == field.role }?.second ?: "Otomatik / detay",
                    onValueChange = {}, readOnly = true, label = { Text("Google Wallet alan eşlemesi") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    roles.forEach { (key, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { onChange(field.copy(role = key)); expanded = false })
                    }
                }
            }
        }
    }
}
