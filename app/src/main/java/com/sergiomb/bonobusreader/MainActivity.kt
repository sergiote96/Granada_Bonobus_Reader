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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
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


enum class CardType { RED, GREEN, UNKNOWN }

data class CardReadEntry(
    val timestamp: Long,
    val cardId: String? = null,
    val cardType: String,
    val balance: Float
)

data class SavedCard(val id: String, val name: String, val type: String)

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
                            onShowHistory = { navController.navigate("history") },
                            onOpenHistory = { cardId -> navController.navigate("history/$cardId") },
                            onTagDetectedCallback = { tag -> onTagDetected(tag) }
                        )
                    }
                    composable("history") {
                        HistoryScreen(onBack = { navController.popBackStack() })
                    }
                    composable("history/{cardId}") { backStackEntry ->
                        val cardId = backStackEntry.arguments?.getString("cardId")
                        HistoryScreen(onBack = { navController.popBackStack() }, cardId = cardId)
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
        val entry = CardReadEntry(System.currentTimeMillis(), cardId, cardType, balance)
        val sharedPrefs = getSharedPreferences("bonobus_prefs", MODE_PRIVATE)
        val gson = Gson()
        val currentList = loadHistory().toMutableList()
        currentList.add(0, entry)
        val json = gson.toJson(currentList)
        sharedPrefs.edit().putString("history", json).apply()
    }

    fun loadHistory(): List<CardReadEntry> {
        val sharedPrefs = getSharedPreferences("bonobus_prefs", MODE_PRIVATE)
        val json = sharedPrefs.getString("history", "[]")
        val type = object : TypeToken<List<CardReadEntry>>() {}.type
        return Gson().fromJson(json, type)
    }

    fun deleteEntries(entries: List<CardReadEntry>) {
        val sharedPrefs = getSharedPreferences("bonobus_prefs", MODE_PRIVATE)
        val gson = Gson()
        val currentList = loadHistory().toMutableList()
        entries.forEach { currentList.remove(it) }
        sharedPrefs.edit().putString("history", gson.toJson(currentList)).apply()
    }

    fun loadSavedCards(): List<SavedCard> {
        val sharedPrefs = getSharedPreferences("bonobus_prefs", MODE_PRIVATE)
        val json = sharedPrefs.getString("cards", "[]")
        val type = object : TypeToken<List<SavedCard>>() {}.type
        return Gson().fromJson(json, type)
    }

    fun saveCard(card: SavedCard) {
        val sharedPrefs = getSharedPreferences("bonobus_prefs", MODE_PRIVATE)
        val gson = Gson()
        val currentList = loadSavedCards().toMutableList()
        currentList.removeAll { it.id == card.id }
        currentList.add(card)
        sharedPrefs.edit().putString("cards", gson.toJson(currentList)).apply()
    }

    fun findSavedCard(cardId: String): SavedCard? = loadSavedCards().firstOrNull { it.id == cardId }

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

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun HomeScreen(
    onShowHistory: () -> Unit,
    onOpenHistory: (String) -> Unit,
    onTagDetectedCallback: (Tag) -> Unit
) {
    val activity = LocalContext.current as MainActivity
    var saldoTexto by remember { mutableStateOf("📲 Acerca tu bonobús...") }
    var cardType by remember { mutableStateOf(CardType.UNKNOWN) }
    var cardName by remember { mutableStateOf<String?>(null) }
    var pendingCardId by remember { mutableStateOf<String?>(null) }
    var pendingBalance by remember { mutableStateOf<Float?>(null) }
    var pendingCardType by remember { mutableStateOf<CardType?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Nueva tarjeta detectada") },
            text = { Text("¿Quieres guardar esta tarjeta para acceder a su histórico personal?") },
            confirmButton = {
                TextButton(onClick = {
                    showSaveDialog = false
                    showNameDialog = true
                }) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Ahora no")
                }
            }
        )
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Nombre de la tarjeta") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Ej. Bonobús de Ana") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val cardId = pendingCardId
                    val balance = pendingBalance
                    val type = pendingCardType
                    if (cardId != null && balance != null && type != null) {
                        val safeName = nameInput.ifBlank {
                            when (type) {
                                CardType.RED -> "CrediBus Granada"
                                CardType.GREEN -> "Bonobús Andalucía"
                                else -> "Bonobús"
                            }
                        }
                        val typeLabel = when (type) {
                            CardType.RED -> "Roja"
                            CardType.GREEN -> "Verde"
                            else -> "Desconocida"
                        }
                        activity.saveCard(SavedCard(cardId, safeName, typeLabel))
                        activity.saveCardEntry(cardId, typeLabel, balance)
                        cardName = safeName
                        saldoTexto = "💳 Saldo actual: %.2f €".format(balance)
                        onOpenHistory(cardId)
                    }
                    nameInput = ""
                    pendingCardId = null
                    pendingBalance = null
                    pendingCardType = null
                    showNameDialog = false
                }) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    nameInput = ""
                    pendingCardId = null
                    pendingBalance = null
                    pendingCardType = null
                    showNameDialog = false
                }) {
                    Text("Cancelar")
                }
            }
        )
    }

    activity.onTagDetected = { tag ->
        val cardId = tag.id.joinToString("") { byte -> "%02X".format(byte) }
        activity.readRedCard(tag)?.let { saldo ->
            cardType = CardType.RED
            val savedCard = activity.findSavedCard(cardId)
            cardName = savedCard?.name
            saldoTexto = "💳 Saldo actual: %.2f €".format(saldo)
            if (savedCard != null) {
                activity.saveCardEntry(cardId, "Roja", saldo)
                onOpenHistory(cardId)
            } else {
                pendingCardId = cardId
                pendingBalance = saldo
                pendingCardType = CardType.RED
                showSaveDialog = true
            }
        } ?: run {
            activity.readGreenCard(tag)?.let { saldo ->
                cardType = CardType.GREEN
                val savedCard = activity.findSavedCard(cardId)
                cardName = savedCard?.name
                saldoTexto = "💳 Saldo actual: %.2f €".format(saldo)
                if (savedCard != null) {
                    activity.saveCardEntry(cardId, "Verde", saldo)
                    onOpenHistory(cardId)
                } else {
                    pendingCardId = cardId
                    pendingBalance = saldo
                    pendingCardType = CardType.GREEN
                    showSaveDialog = true
                }
            } ?: run {
                cardType = CardType.UNKNOWN
                cardName = null
                saldoTexto = "❌ No se pudo leer la tarjeta"
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("BonobusNFCReader") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val bgColor = when (cardType) {
                CardType.RED -> Color(0xFFB71C1C)
                CardType.GREEN -> Color(0xFF1B5E20)
                else -> Color.DarkGray
            }
            val title = when (cardType) {
                CardType.RED -> "CrediBus Granada"
                CardType.GREEN -> "Bonobús Consorcio Transportes Andalucía"
                else -> "Bonobús no detectado"
            }

            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = bgColor),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = cardName ?: title,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        if (cardName != null) {
                            Text(text = title, fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f))
                        }
                    }
                    Text(text = saldoTexto, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Acerca una tarjeta para ver su saldo y acceder a su histórico personal.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onShowHistory) {
                Text("Ver histórico")
            }
        }
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun HistoryScreen(onBack: () -> Unit, cardId: String? = null) {
    val context = LocalContext.current as MainActivity
    var historyList by remember { mutableStateOf(context.loadHistory()) }
    val selectedItems = remember { mutableStateListOf<CardReadEntry>() }
    var selectionMode by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    val cardName = remember(cardId) { cardId?.let { context.findSavedCard(it)?.name } }
    val filteredHistory = remember(historyList, cardId) {
        if (cardId == null) historyList else historyList.filter { it.cardId == cardId }
    }
    val titleText = cardName ?: if (cardId != null) "Histórico de tarjeta" else "Histórico de lecturas"

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("¿Eliminar entradas?") },
            text = { Text("¿Estás seguro de que quieres borrar las lecturas seleccionadas?") },
            confirmButton = {
                TextButton(onClick = {
                    context.deleteEntries(selectedItems.toList())
                    historyList = context.loadHistory()
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

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(titleText) },
                actions = {
                    if (selectionMode && selectedItems.isNotEmpty()) {
                        IconButton(onClick = { showDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Eliminar")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(filteredHistory) { index, entry ->
                    val fecha = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "ES")).format(Date(entry.timestamp))
                    val isSelected = selectedItems.contains(entry)
                    val baseColor = when (entry.cardType) {
                        "Roja" -> Color(0xFFFFEBEE)
                        "Verde" -> Color(0xFFE8F5E9)
                        else -> Color(0xFFF5F5F5)
                    }
                    val bgColor = if (isSelected) Color(0xFFDDDDDD) else baseColor

                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .combinedClickable(
                                onClick = {
                                    if (selectionMode) {
                                        if (isSelected) selectedItems.remove(entry) else selectedItems.add(entry)
                                    }
                                },
                                onLongClick = {
                                    if (!selectionMode) {
                                        selectionMode = true
                                        selectedItems.add(entry)
                                    }
                                }
                            ),
                        colors = CardDefaults.elevatedCardColors(containerColor = bgColor)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(fecha, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Tarjeta ${entry.cardType}", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("Saldo: %.2f €".format(entry.balance), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Volver")
            }
        }
    }
}
