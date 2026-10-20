package com.skapp.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

// ---------- 数据层 ----------
data class Quote(
    val symbol: String,
    val name: String,
    val price: Double,
    val pctChg: Double,
)

sealed interface UiState {
    data object Loading : UiState
    data class Ready(val quotes: List<Quote>) : UiState
    data class Error(val message: String) : UiState
}

/** 服务器响应字段全部可空（规范 3.6） */
private data class QuoteResponse(
    val symbol: String? = null,
    val name: String? = null,
    val price: Double? = null,
    val pctChg: Double? = null,
)

/** API 客户端：IO 线程执行，main-safe */
class SkappRepository(private val baseUrl: String) {
    suspend fun fetchQuote(symbol: String): Result<Quote> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("$baseUrl/api/v1/quote/$symbol").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            try {
                val body = conn.inputStream.bufferedReader().readText()
                val o = JSONObject(body)
                val q = QuoteResponse(
                    symbol = o.optString("symbol", symbol),
                    name = o.optString("name", symbol),
                    price = if (o.has("price")) o.getDouble("price") else null,
                    pctChg = if (o.has("pct_chg")) o.getDouble("pct_chg") else null,
                )
                Quote(
                    symbol = q.symbol ?: symbol,
                    name = q.name ?: symbol,
                    price = q.price ?: 0.0,
                    pctChg = q.pctChg ?: 0.0,
                )
            } finally {
                conn.disconnect()
            }
        }
    }
}

/** ViewModel：状态在 Main 线程更新，网络在 IO */
class WatchViewModel(private val repo: SkappRepository) : ViewModel() {
    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // 自选股（与 py/config/symbols.yaml 对齐；腾讯控股为港股）
    private val watchlist = listOf("sh600519", "sz300750", "sz002594", "sh600941", "hk00700")

    fun refresh() {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            val quotes = watchlist.map { sym ->
                repo.fetchQuote(sym).getOrNull()
            }.filterNotNull()
            _uiState.value = if (quotes.isEmpty()) {
                UiState.Error("无法连接 API，请检查网络与服务器地址")
            } else {
                UiState.Ready(quotes)
            }
        }
    }

    class Factory(private val baseUrl: String) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WatchViewModel(SkappRepository(baseUrl)) as T
    }
}

// ---------- UI 层（Material 3，金融风格：专业信任配色 + 深色支持） ----------
private val LightColors = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF1B6EF3),
)
private val DarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF9ECAFF),
)

@Composable
fun SkappTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun WatchScreen(baseUrl: String) {
    val vm: WatchViewModel = viewModel(factory = WatchViewModel.Factory(baseUrl))
    val state by vm.uiState.collectAsState()

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("skapp 自选观察") }) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { vm.refresh() },
                modifier = Modifier.semantics { contentDescription = "刷新行情" },
            ) { Text("⟳") }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (val s = state) {
                is UiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is UiState.Error -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(s.message, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { vm.refresh() }) { Text("重试") }
                }
                is UiState.Ready -> QuoteList(s.quotes)
            }
        }
    }
}

@Composable
private fun QuoteList(quotes: List<Quote>) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(quotes, key = { it.symbol }) { q ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(q.name, style = MaterialTheme.typography.titleMedium)
                        Text(q.symbol, style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "%.2f".format(q.price),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        val up = q.pctChg >= 0
                        Text(
                            "%+.2f%%".format(q.pctChg),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (up) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 部署后改成实际 API 地址；模拟器里 10.0.2.2 指向宿主机
        val baseUrl = "http://10.0.2.2:8800"
        setContent {
            SkappTheme { WatchScreen(baseUrl) }
        }
    }
}
