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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

data class CardReadEntry(val timestamp: Long, val cardType: String, val balance: Float)

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
                            onTagDetectedCallback = { tag -> onTagDetected(tag) }
                        )
                    }
                    composable("history") {
                        HistoryScreen(onBack = { navController.popBackStack() })
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

    fun saveCardEntry(cardType: String, balance: Float) {
        val entry = CardReadEntry(System.currentTimeMillis(), cardType, balance)
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

    fun deleteEntries(indices: List<Int>) {
        val sharedPrefs = getSharedPreferences("bonobus_prefs", MODE_PRIVATE)
        val gson = Gson()
        val currentList = loadHistory().toMutableList()
        indices.sortedDescending().forEach { currentList.removeAt(it) }
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

@Composable
fun HomeScreen(onShowHistory: () -> Unit, onTagDetectedCallback: (Tag) -> Unit) {
    val activity = LocalContext.current as MainActivity
    var saldoTexto by remember { mutableStateOf("📲 Acerca tu bonobús...") }
    var cardType by remember { mutableStateOf(CardType.UNKNOWN) }

    activity.onTagDetected = { tag ->
        activity.readRedCard(tag)?.let { saldo ->
            cardType = CardType.RED
            saldoTexto = "💳 Saldo actual: %.2f €".format(saldo)
            activity.saveCardEntry("Roja", saldo)
        } ?: run {
            activity.readGreenCard(tag)?.let { saldo ->
                cardType = CardType.GREEN
                saldoTexto = "💳 Saldo actual: %.2f €".format(saldo)
                activity.saveCardEntry("Verde", saldo)
            } ?: run {
                cardType = CardType.UNKNOWN
                saldoTexto = "❌ No se pudo leer la tarjeta"
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
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

            Text(text = title, fontSize = 18.sp, modifier = Modifier.padding(bottom = 12.dp))
            Box(modifier = Modifier.width(280.dp).height(160.dp).background(bgColor, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Text(text = saldoTexto, color = Color.White, fontSize = 22.sp)
            }
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = onShowHistory) {
                Text("Ver histórico")
            }
        }
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current as MainActivity
    var historyList by remember { mutableStateOf(context.loadHistory()) }
    val selectedItems = remember { mutableStateListOf<Int>() }
    var selectionMode by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }

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

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("📜 Histórico de lecturas", fontSize = 22.sp, modifier = Modifier.weight(1f))
                if (selectionMode && selectedItems.isNotEmpty()) {
                    IconButton(onClick = { showDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Eliminar")
                    }
                }
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(historyList) { index, entry ->
                    val fecha = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "ES")).format(Date(entry.timestamp))
                    val isSelected = selectedItems.contains(index)
                    val bgColor = if (isSelected) Color(0xFFDDDDDD) else Color(0xFFF5F5F5)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .combinedClickable(
                                onClick = {
                                    if (selectionMode) {
                                        if (isSelected) selectedItems.remove(index) else selectedItems.add(index)
                                    }
                                },
                                onLongClick = {
                                    if (!selectionMode) {
                                        selectionMode = true
                                        selectedItems.add(index)
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
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Volver")
            }
        }
    }
}
