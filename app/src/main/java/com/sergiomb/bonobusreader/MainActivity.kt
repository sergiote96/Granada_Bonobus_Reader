package com.sergiomb.bonobusreader

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sergiomb.bonobusreader.ui.theme.BonobusReaderTheme
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction


enum class CardType { RED, GREEN, UNKNOWN }

data class CardHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val cardType: String = "",
    val balance: Float = 0f,
    val cardId: String? = null
)

data class SavedCard(
    val id: String,
    val name: String,
    val cardType: String
)

class MainActivity : ComponentActivity() {

    private lateinit var nfcAdapter: NfcAdapter

    private val keyRed = byteArrayOf(0x38, 0x5E, 0xFA.toByte(), 0x54, 0x29, 0x07)
    private val keyGreen = byteArrayOf(0x99.toByte(), 0x10, 0x02, 0x25, 0xD8.toByte(), 0x3B)

    lateinit var onTagDetected: (Tag) -> Unit

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setContent {
            BonobusReaderTheme {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            onShowHistory = { cardId -> navController.navigate("history/$cardId") }
                        )
                    }
                    composable("history/{cardId}") { backStackEntry ->
                        val cardId = backStackEntry.arguments?.getString("cardId").orEmpty()
                        HistoryScreen(
                            cardId = cardId,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val filters = arrayOf(IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED))
        val techLists = arrayOf(arrayOf(MifareClassic::class.java.name))
        nfcAdapter.enableForegroundDispatch(this, pendingIntent, filters, techLists)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
        tag?.let { onTagDetected(it) }
    }

    fun saveCardEntry(cardId: String, cardType: String, balance: Float) {
        val entry = CardHistoryEntry(
            timestamp = System.currentTimeMillis(),
            cardType = cardType,
            balance = balance,
            cardId = cardId
        )
        val sharedPrefs = getSharedPreferences("bonobus_prefs", MODE_PRIVATE)
        val gson = Gson()
        val currentList = loadHistory().toMutableList()
        currentList.add(0, entry)
        val json = gson.toJson(currentList)
        sharedPrefs.edit().putString("history", json).apply()
    }

    fun loadHistory(): List<CardHistoryEntry> {
        val sharedPrefs = getSharedPreferences("bonobus_prefs", MODE_PRIVATE)
        val json = sharedPrefs.getString("history", "[]")
        val type = object : TypeToken<List<CardHistoryEntry>>() {}.type
        return Gson().fromJson(json, type)
    }

    fun deleteEntries(entryIds: List<String>) {
        val sharedPrefs = getSharedPreferences("bonobus_prefs", MODE_PRIVATE)
        val gson = Gson()
        val currentList = loadHistory().filterNot { entryIds.contains(it.id) }
        sharedPrefs.edit().putString("history", gson.toJson(currentList)).apply()
    }

    fun readRedCard(tag: Tag): Float? {
        val mfc = MifareClassic.get(tag)
        try {
            mfc.connect()
            val sectorIndex = 1
            if (!mfc.authenticateSectorWithKeyA(sectorIndex, keyRed)) return null
            val blockIndex = mfc.sectorToBlock(sectorIndex)
            val dataBlock0 = mfc.readBlock(blockIndex)
            val saldoCts = (dataBlock0[1].toInt() shl 8) or (dataBlock0[0].toInt() and 0xFF)
            return saldoCts.toFloat() / 100
        } catch (_: Exception) {
            return null
        } finally {
            try { mfc.close() } catch (_: Exception) {}
        }
    }

    fun readGreenCard(tag: Tag): Float? {
        val mfc = MifareClassic.get(tag)
        try {
            mfc.connect()
            val sectorIndex = 9
            if (!mfc.authenticateSectorWithKeyA(sectorIndex, keyGreen)) return null
            val blockIndex = mfc.sectorToBlock(sectorIndex) + 1
            val data = mfc.readBlock(blockIndex)
            val saldoEntero = ((data[1].toInt() and 0xFF) shl 8) or (data[0].toInt() and 0xFF)
            val saldoDecimal = BigDecimal(saldoEntero).multiply(BigDecimal("0.005")).setScale(2, RoundingMode.HALF_UP)
            return saldoDecimal.toFloat()
        } catch (e: Exception) {
            println("Error leyendo tarjeta verde: ${e.message}")
            return null
        } finally {
            try { mfc.close() } catch (_: Exception) {}
        }
    }
}

fun MainActivity.saveCard(card: SavedCard) {
    val sharedPrefs = getSharedPreferences("bonobus_prefs", MODE_PRIVATE)
    val gson = Gson()
    val currentList = loadSavedCards().toMutableList()
    currentList.removeAll { it.id == card.id }
    currentList.add(card)
    sharedPrefs.edit().putString("saved_cards", gson.toJson(currentList)).apply()
}

fun MainActivity.loadSavedCards(): List<SavedCard> {
    val sharedPrefs = getSharedPreferences("bonobus_prefs", MODE_PRIVATE)
    val json = sharedPrefs.getString("saved_cards", "[]")
    val type = object : TypeToken<List<SavedCard>>() {}.type
    return Gson().fromJson(json, type)
}

private fun tagIdToHex(tagId: ByteArray?): String {
    if (tagId == null) return "unknown"
    return tagId.joinToString("") { "%02X".format(it) }
}

@Composable
fun HomeScreen(onShowHistory: (String) -> Unit) {
    val activity = LocalContext.current as MainActivity
    var saldoTexto by remember { mutableStateOf("📲 Acerca tu bonobús...") }
    var cardType by remember { mutableStateOf(CardType.UNKNOWN) }
    var cardName by remember { mutableStateOf<String?>(null) }
    var lastCardId by remember { mutableStateOf<String?>(null) }
    var lastSavedCardId by remember { mutableStateOf<String?>(null) }
    var lastBalance by remember { mutableStateOf<Float?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var savedCards by remember { mutableStateOf(activity.loadSavedCards()) }
    var newCardName by remember { mutableStateOf("") }

    activity.onTagDetected = { tag ->
        val cardId = tagIdToHex(tag.id)
        val savedCard = savedCards.firstOrNull { it.id == cardId }
        val redBalance = activity.readRedCard(tag)
        val greenBalance = if (redBalance == null) activity.readGreenCard(tag) else null
        val balance = redBalance ?: greenBalance
        if (balance == null) {
            cardType = CardType.UNKNOWN
            cardName = null
            lastBalance = null
            saldoTexto = "❌ No se pudo leer la tarjeta"
            return@onTagDetected
        }

        val detectedType = if (redBalance != null) CardType.RED else CardType.GREEN
        cardType = detectedType
        lastCardId = cardId
        lastBalance = balance
        cardName = savedCard?.name
        saldoTexto = "💳 Saldo actual: %.2f €".format(balance)

        if (savedCard != null) {
            activity.saveCardEntry(cardId, savedCard.cardType, balance)
            lastSavedCardId = cardId
            onShowHistory(cardId)
        } else {
            newCardName = when (detectedType) {
                CardType.RED -> "CrediBus"
                CardType.GREEN -> "Consorcio Andalucía"
                else -> "Bonobús"
            }
            showSaveDialog = true
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Nueva tarjeta detectada") },
            text = {
                Column {
                    Text("¿Quieres guardar esta tarjeta para acceder a su histórico?")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newCardName,
                        onValueChange = { newCardName = it },
                        label = { Text("Nombre de la tarjeta") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cardId = lastCardId
                        val balance = lastBalance
                        if (cardId != null && balance != null) {
                            val typeLabel = if (cardType == CardType.RED) "Roja" else "Verde"
                            val card = SavedCard(
                                id = cardId,
                                name = newCardName.ifBlank { "Bonobús" },
                                cardType = typeLabel
                            )
                            activity.saveCard(card)
                            savedCards = activity.loadSavedCards()
                            cardName = card.name
                            activity.saveCardEntry(card.id, card.cardType, balance)
                            lastSavedCardId = card.id
                            onShowHistory(cardId)
                        }
                        showSaveDialog = false
                    }
                ) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSaveDialog = false
                        cardName = null
                    }
                ) {
                    Text("Ahora no")
                }
            }
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text(
                text = "BonobusNFCReader",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            val bgColor = when (cardType) {
                CardType.RED -> Color(0xFFE53935)
                CardType.GREEN -> Color(0xFF2E7D32)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            val title = when (cardType) {
                CardType.RED -> "CrediBus Granada"
                CardType.GREEN -> "Bonobús Consorcio Transportes Andalucía"
                else -> "Bonobús no detectado"
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = bgColor),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = title, color = Color.White, fontSize = 16.sp)
                    Text(
                        text = saldoTexto,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (cardName != null) {
                        Text(
                            text = "Tarjeta: $cardName",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 14.sp
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    val cardId = lastSavedCardId
                    if (cardId != null) {
                        onShowHistory(cardId)
                    }
                },
                enabled = lastSavedCardId != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Ver histórico")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Escanea una tarjeta para ver su saldo y acceder a su histórico.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(cardId: String, onBack: () -> Unit) {
    val context = LocalContext.current as MainActivity
    var historyList by remember { mutableStateOf(context.loadHistory().filter { it.cardId == cardId }) }
    val selectedItems = remember { mutableStateListOf<String>() }
    var selectionMode by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    val savedCard = remember(cardId) { context.loadSavedCards().firstOrNull { it.id == cardId } }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("¿Eliminar entradas?") },
            text = { Text("¿Estás seguro de que quieres borrar las lecturas seleccionadas?") },
            confirmButton = {
                TextButton(onClick = {
                    context.deleteEntries(selectedItems.toList())
                    historyList = context.loadHistory().filter { it.cardId == cardId }
                    selectedItems.clear()
                    selectionMode = false
                    showDialog = false
                }) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onBack) {
                    Text("Volver")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = savedCard?.name ?: "Histórico de tarjeta",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (savedCard != null) {
                        Text(
                            text = "Tarjeta ${savedCard.cardType}",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (selectionMode && selectedItems.isNotEmpty()) {
                    TextButton(onClick = { showDialog = true }) {
                        Text("Eliminar")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (historyList.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Aún no hay lecturas guardadas para esta tarjeta.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(historyList) { entry ->
                    val fecha = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "ES")).format(Date(entry.timestamp))
                    val isSelected = selectedItems.contains(entry.id)
                    val baseColor = when (entry.cardType) {
                        "Roja" -> Color(0xFFFFEBEE)
                        "Verde" -> Color(0xFFE8F5E9)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val bgColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else baseColor

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .combinedClickable(
                                onClick = {
                                    if (selectionMode) {
                                        if (isSelected) selectedItems.remove(entry.id) else selectedItems.add(entry.id)
                                    }
                                },
                                onLongClick = {
                                    if (!selectionMode) {
                                        selectionMode = true
                                        selectedItems.add(entry.id)
                                    }
                                }
                            ),
                        colors = CardDefaults.cardColors(containerColor = bgColor)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Fecha: $fecha", fontSize = 16.sp)
                            Text("Tarjeta ${entry.cardType} -> %.2f €".format(entry.balance), fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}
