package com.skapp.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

// ============================================================
// 数据层：与网站同链路，无需自建服务器
//  - 实时行情：腾讯 qt.gtimg.cn 直连（A股+港股，国内手机可用）
//  - 快讯：新浪 7x24 直连
//  - 推股：GitHub 仓库 contents API（token 在「我的」页配置）
// ============================================================

data class Quote(val symbol: String, val name: String, val price: Double, val pctChg: Double)
data class NewsItem(val ts: String, val text: String, val sentiment: String)
data class PickItem(val symbol: String, val rank: Int, val source: String)

sealed interface UiState {
    data object Loading : UiState
    data class Ready(val quotes: List<Quote> = emptyList(),
                     val news: List<NewsItem> = emptyList(),
                     val picks: List<PickItem> = emptyList()) : UiState
    data class Error(val message: String) : UiState
}

private fun httpGet(url: String, headers: Map<String, String> = emptyMap(),
                    timeoutMs: Int = 10000): Pair<Int, ByteArray> {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = timeoutMs
    conn.readTimeout = timeoutMs
    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
    return try {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        Pair(code, stream?.readBytes() ?: ByteArray(0))
    } finally {
        conn.disconnect()
    }
}

class MarketRepository(private val githubToken: () -> String) {

    /** 腾讯实时行情（GBK），A股+港股同接口 */
    suspend fun realtimeQuotes(symbols: List<String>): Result<List<Quote>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (symbols.isEmpty()) return@runCatching emptyList()
                val url = "https://qt.gtimg.cn/q=" + symbols.joinToString(",")
                val text = String(httpGet(url).second, charset("GBK"))
                val out = mutableListOf<Quote>()
                for (line in text.split(";")) {
                    val l = line.trim()
                    if (!l.contains("=")) continue
                    val sym = l.substringBefore("=").removePrefix("v_")
                    val f = l.substringAfter("=").removeSurrounding("\"").split("~")
                    if (f.size < 6) continue
                    val price = f.getOrNull(3)?.toDoubleOrNull() ?: 0.0
                    val prev = f.getOrNull(4)?.toDoubleOrNull() ?: 0.0
                    val pct = f.getOrNull(32)?.toDoubleOrNull()
                        ?: if (prev > 0) (price - prev) / prev * 100 else 0.0
                    out.add(Quote(sym, f[1], price, pct))
                }
                out
            }
        }

    /** 新浪 7x24 快讯 + 情感标注（与 eod_sync 同规则） */
    suspend fun news(num: Int = 30): Result<List<NewsItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://zhibo.sina.com.cn/api/zhibo/feed?page=1&page_size=$num&zhibo_id=152&tag_id=0"
            val d = JSONObject(String(httpGet(url).second))
            val list = d.getJSONObject("result").getJSONObject("data")
                .getJSONObject("feed").getJSONArray("list")
            val pos = listOf("利好", "上涨", "增持", "回购", "中标", "增长", "超预期", "分红")
            val neg = listOf("利空", "下跌", "减持", "处罚", "亏损", "警示", "立案", "退市")
            (0 until minOf(num, list.length())).map { i ->
                val it = list.getJSONObject(i)
                val txt = it.optString("rich_text", "")
                val p = pos.count { w -> txt.contains(w) }
                val n = neg.count { w -> txt.contains(w) }
                NewsItem(it.optString("create_time", ""), txt,
                    if (p > n) "利好" else if (n > p) "利空" else "中性")
            }
        }
    }

    /** 仓库数据文件（推股），contents API + token */
    suspend fun picks(): Result<List<PickItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val tok = githubToken().trim()
            require(tok.isNotEmpty()) { "未配置 GitHub Token，请在「我的」页填写（需 skapp 仓读取权限）" }
            val url = "https://api.github.com/repos/jiegengxiangjianxue/skapp/contents/data/engine/picks_latest.json"
            val (code, bytes) = httpGet(url, mapOf(
                "Authorization" to "Bearer $tok",
                "Accept" to "application/vnd.github.raw"))
            require(code in 200..299) { "GitHub 返回 $code（检查 token 或文件是否存在）" }
            val d = JSONObject(String(bytes))
            val arr = d.getJSONArray("items")
            (0 until arr.length()).take(30).map { i ->
                val o = arr.getJSONObject(i)
                PickItem(o.optString("symbol", "?"), o.optInt("rank", i + 1),
                    d.optString("source", ""))
            }
        }
    }
}

// ============================================================
// ViewModel
// ============================================================
class SkappViewModel(private val repo: MarketRepository) : ViewModel() {
    private val _quotes = MutableStateFlow<UiState>(UiState.Loading)
    val quotes: StateFlow<UiState> = _quotes.asStateFlow()

    private val _news = MutableStateFlow<UiState>(UiState.Loading)
    val news: StateFlow<UiState> = _news.asStateFlow()

    private val _picks = MutableStateFlow<UiState>(UiState.Loading)
    val picks: StateFlow<UiState> = _picks.asStateFlow()

    private val _watchlist = MutableStateFlow(
        listOf("sh600519", "sz300750", "sz002594", "sh600941", "hk00700"))
    val watchlist: StateFlow<List<String>> = _watchlist.asStateFlow()

    fun refreshQuotes() {
        _quotes.value = UiState.Loading
        viewModelScope.launch {
            repo.realtimeQuotes(_watchlist.value)
                .onSuccess { _quotes.value = if (it.isEmpty()) UiState.Error("行情数据为空") else UiState.Ready(quotes = it) }
                .onFailure { _quotes.value = UiState.Error(it.message ?: "行情加载失败") }
        }
    }

    fun refreshNews() {
        _news.value = UiState.Loading
        viewModelScope.launch {
            repo.news()
                .onSuccess { _news.value = UiState.Ready(news = it) }
                .onFailure { _news.value = UiState.Error(it.message ?: "快讯加载失败") }
        }
    }

    fun refreshPicks() {
        _picks.value = UiState.Loading
        viewModelScope.launch {
            repo.picks()
                .onSuccess { _picks.value = UiState.Ready(picks = it) }
                .onFailure { _picks.value = UiState.Error(it.message ?: "推股加载失败") }
        }
    }

    fun removeSymbol(sym: String) {
        _watchlist.value = _watchlist.value - sym
        refreshQuotes()
    }

    fun addSymbol(raw: String): Boolean {
        val sym = raw.trim().lowercase().replace(".", "")
        val ok = sym.length >= 6 && (sym.startsWith("sh") || sym.startsWith("sz") || sym.startsWith("hk"))
        if (ok && sym !in _watchlist.value) {
            _watchlist.value = _watchlist.value + sym
            refreshQuotes()
        }
        return ok
    }

    class Factory(private val repo: MarketRepository) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SkappViewModel(repo) as T
    }
}

// ============================================================
// UI 层（M3）：底部导航 4 Tab：行情 / 推股 / 消息 / 我的
// ============================================================
private val UpColor = Color(0xFFD32F2F)     // A股习惯：涨红
private val DownColor = Color(0xFF2E7D32)   // 跌绿

@Composable
fun SkappTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(vm: SkappViewModel, token: String, onTokenChange: (String) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val quotes by vm.quotes.collectAsState()
    val news by vm.news.collectAsState()
    val picks by vm.picks.collectAsState()

    LaunchedEffect(Unit) {
        vm.refreshQuotes(); vm.refreshNews(); vm.refreshPicks()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(listOf("行情", "推股", "消息", "我的")[tab]) })
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> QuotesTab(quotes, vm)
                1 -> PicksTab(picks, vm)
                2 -> NewsTab(news, vm)
                3 -> SettingsTab(token, onTokenChange, vm)
            }
        }
    }
}

// ---------- Tab 1 行情 ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuotesTab(state: UiState, vm: SkappViewModel) {
    var adding by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                label = { Text("添加自选 (如 sh601318)") },
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (vm.addSymbol(input)) { input = "" } else adding = true
            }) { Text("添加") }
        }
        when (state) {
            is UiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            is UiState.Error -> ErrView(state.message) { vm.refreshQuotes() }
            is UiState.Ready -> LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.quotes, key = { it.symbol }) { q ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp).heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(q.name, style = MaterialTheme.typography.titleMedium)
                                Text(q.symbol, style = MaterialTheme.typography.bodySmall)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("%.2f".format(q.price), style = MaterialTheme.typography.titleLarge)
                                Text("%+.2f%%".format(q.pctChg),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (q.pctChg >= 0) UpColor else DownColor)
                            }
                            TextButton(onClick = { vm.removeSymbol(q.symbol) },
                                modifier = Modifier.semantics { contentDescription = "删除 ${q.name}" }
                            ) { Text("✕") }
                        }
                    }
                }
            }
        }
    }
    if (adding) {
        AlertDialog(onDismissRequest = { adding = false },
            title = { Text("代码格式") },
            text = { Text("支持：sh 沪 / sz 深 / hk 港 + 代码，如 sh601318、hk00700") },
            confirmButton = { TextButton(onClick = { adding = false }) { Text("知道了") } })
    }
}

// ---------- Tab 2 推股 ----------
@Composable
private fun PicksTab(state: UiState, vm: SkappViewModel) {
    when (state) {
        is UiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        is UiState.Error -> ErrView(state.message) { vm.refreshPicks() }
        is UiState.Ready -> Column(Modifier.fillMaxSize().padding(16.dp)) {
            val src = state.picks.firstOrNull()?.source ?: ""
            if (src.isNotEmpty()) {
                Text("来源：$src", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.picks, key = { it.symbol + it.rank }) { p ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp).heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("#${p.rank}", style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.width(48.dp))
                            Text(p.symbol, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

// ---------- Tab 3 消息 ----------
@Composable
private fun NewsTab(state: UiState, vm: SkappViewModel) {
    when (state) {
        is UiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        is UiState.Error -> ErrView(state.message) { vm.refreshNews() }
        is UiState.Ready -> LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.news) { n ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AssistChip(onClick = {}, label = { Text(n.sentiment) },
                                colors = AssistChipDefaults.assistChipColors(
                                    labelColor = when (n.sentiment) {
                                        "利好" -> UpColor; "利空" -> DownColor
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }))
                            Spacer(Modifier.width(8.dp))
                            Text(n.ts, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(n.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

// ---------- Tab 4 我的 ----------
@Composable
private fun SettingsTab(token: String, onTokenChange: (String) -> Unit, vm: SkappViewModel) {
    var draft by remember { mutableStateOf(token) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("GitHub Token", style = MaterialTheme.typography.titleMedium)
        Text("用于读取 skapp 仓的推股/快照数据（仅保存在本机）。行情与快讯无需 token，直连公开数据源。",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = draft, onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("ghp_...（fine-grained token 亦可）") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onTokenChange(draft); vm.refreshPicks() }) { Text("保存") }
            OutlinedButton(onClick = { vm.refreshQuotes(); vm.refreshNews() }) { Text("刷新行情/快讯") }
        }
        HorizontalDivider()
        Text("数据源", style = MaterialTheme.typography.titleMedium)
        Text("· 行情：腾讯 qt.gtimg.cn（A股+港股实时）\n· 快讯：新浪 7x24\n· 推股/复盘：GitHub 仓库快照（收盘后 Actions 自动生成）",
            style = MaterialTheme.typography.bodySmall)
        Text("推股与复盘为引擎历史产物快照，仅供观察，不构成投资建议。",
            style = MaterialTheme.typography.bodySmall)
    }
}

// ---------- 通用 ----------
@Composable
private fun ErrView(message: String, retry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text(message, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = retry) { Text("重试") }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("skapp", Context.MODE_PRIVATE)
        setContent {
            SkappTheme {
                var token by remember { mutableStateOf(prefs.getString("gh_token", "") ?: "") }
                val vm: SkappViewModel = viewModel(
                    factory = SkappViewModel.Factory(MarketRepository { token }))
                AppScreen(vm, token) { t ->
                    token = t
                    prefs.edit().putString("gh_token", t).apply()
                }
            }
        }
    }
}
