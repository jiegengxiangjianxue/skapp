@file:OptIn(ExperimentalMaterial3Api::class)

package com.skapp.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val UpColor = Color(0xFFD32F2F)
private val DownColor = Color(0xFF2E7D32)

@Composable
fun SkappTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content)
}

// ---------- ViewModel ----------
class SkappViewModel(private val repo: MarketRepository) : ViewModel() {
    val quotes = MutableStateFlow<Res<List<Quote>>>(Res.Err("加载中"))
    val news = MutableStateFlow<Res<List<NewsItem>>>(Res.Err("加载中"))
    val todayPicks = MutableStateFlow<Res<List<LivePick>>>(Res.Err("加载中"))
    val pulse = MutableStateFlow<Res<MarketPulse>>(Res.Err("加载中"))
    val decision = MutableStateFlow<Res<Pair<PositionAdvice, List<DecisionCard>>>>(Res.Err("加载中"))
    val avoid = MutableStateFlow<Res<List<AvoidItem>>>(Res.Err("加载中"))
    val searchIndex = MutableStateFlow<Res<List<SymbolEntry>>>(Res.Err("加载中"))

    private val _watchlist = MutableStateFlow(
        listOf("sh600519", "sz300750", "sz002594", "sh600941", "hk00700"))
    val watchlist: StateFlow<List<String>> = _watchlist.asStateFlow()

    init {
        // 实时自动刷新：行情每 30s（交易时段内腾讯接口秒级有效）
        viewModelScope.launch {
            while (true) {
                refreshQuotes()
                delay(30_000)
            }
        }
        // 快讯每 60s
        viewModelScope.launch {
            while (true) {
                news.value = repo.news()
                delay(60_000)
            }
        }
        // 引擎数据每 120s（网站 Actions 每 5-10 分钟推一次，2 分钟轮询足够新鲜）
        viewModelScope.launch {
            while (true) {
                todayPicks.value = repo.todayPicks()
                pulse.value = repo.marketPulse()
                decision.value = repo.decisionCards()
                avoid.value = repo.avoidList()
                delay(120_000)
            }
        }
    }

    fun refreshQuotes() {
        viewModelScope.launch {
            val r = repo.realtimeQuotes(_watchlist.value)
            val names = (searchIndex.value as? Res.Ok)?.v?.associate { it.symbol to it.name } ?: emptyMap()
            quotes.value = when (r) {
                is Res.Ok -> Res.Ok(r.v.map { q -> q.copy(name = names[q.symbol] ?: q.name) })
                is Res.Err -> r
            }
        }
    }
    fun refreshNews() { viewModelScope.launch { news.value = repo.news() } }
    fun refreshLive() {
        viewModelScope.launch {
            todayPicks.value = repo.todayPicks()
            pulse.value = repo.marketPulse()
            decision.value = repo.decisionCards()
            avoid.value = repo.avoidList()
        }
    }
    fun ensureSearch() {
        if ((searchIndex.value as? Res.Err)?.message == "加载中") viewModelScope.launch {
            searchIndex.value = repo.searchIndex()
        }
    }
    fun removeSymbol(sym: String) { _watchlist.value -= sym; refreshQuotes() }
    fun addSymbol(sym: String): Boolean {
        val s = sym.trim().lowercase().replace(".", "")
        val ok = s.length >= 6 && (s.startsWith("sh") || s.startsWith("sz") || s.startsWith("hk"))
        if (ok && s !in _watchlist.value) { _watchlist.value += s; refreshQuotes() }
        return ok
    }
    fun addBySearch(entry: SymbolEntry) {
        if (entry.symbol !in _watchlist.value) { _watchlist.value += entry.symbol; refreshQuotes() }
    }
    fun addStockCode(code: String, name: String) {
        val sym = when {
            code.startsWith("6") || code.startsWith("9") || code.startsWith("5") -> "sh$code"
            code.startsWith("8") || code.startsWith("4") -> "bj$code"
            else -> "sz$code"
        }
        if (sym !in _watchlist.value) { _watchlist.value += sym; refreshQuotes() }
    }

    class Factory(private val repo: MarketRepository) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SkappViewModel(repo) as T
    }
}

// ---------- Activity ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("skapp", Context.MODE_PRIVATE)
        setContent {
            SkappTheme {
                var token by remember { mutableStateOf(prefs.getString("gh_token", "") ?: "") }
                val vm: SkappViewModel = viewModel(
                    factory = SkappViewModel.Factory(MarketRepository { token }))
                AppRoot(vm, token, onToken = { t ->
                    token = t
                    prefs.edit().putString("gh_token", t).apply()
                })
            }
        }
    }
}

@Composable
fun AppRoot(vm: SkappViewModel, token: String, onToken: (String) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { vm.ensureSearch() }
    Scaffold(
        bottomBar = {
            NavigationBar {
                val items = listOf("行情", "推股", "决策", "消息", "我的")
                items.forEachIndexed { i, label ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i },
                        icon = { Text(listOf("📈", "⭐", "🧠", "📰", "⚙")[i]) },
                        label = { Text(label) })
                }
            }
        },
        topBar = {
            TopAppBar(title = { Text(listOf("自选行情 · 30s自动刷新", "今日实时推股", "十维决策", "市场消息 · 60s自动刷新", "设置")[tab]) })
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> QuotesTab(vm)
                1 -> PicksTab(vm)
                2 -> DecisionTab(vm)
                3 -> NewsTab(vm)
                4 -> SettingsTab(token, onToken, vm)
            }
        }
    }
}

// ---------- 通用组件 ----------
@Composable
fun <T> ResView(res: Res<T>, retry: () -> Unit, content: @Composable (T) -> Unit) {
    when (res) {
        is Res.Err -> if (res.message == "加载中") {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        } else {
            Column(Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(res.message, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = retry) { Text("重试") }
            }
        }
        is Res.Ok -> content(res.v)
    }
}

// ---------- 个股详情 ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SymbolDetailSheet(symbol: String, name: String, extra: String?, onDismiss: () -> Unit, vm: SkappViewModel) {
    var q by remember { mutableStateOf<Res<Quote>>(Res.Err("加载中")) }
    LaunchedEffect(symbol) {
        val r = repo_quote(symbol)
        q = r
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name.ifBlank { symbol }, style = MaterialTheme.typography.headlineSmall)
            Text(symbol, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (val r = q) {
                is Res.Err -> Text("行情：${r.message}", style = MaterialTheme.typography.bodySmall)
                is Res.Ok -> Row(verticalAlignment = Alignment.Bottom) {
                    Text("%.2f".format(r.v.price), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.width(10.dp))
                    Text("%+.2f%%".format(r.v.pctChg), style = MaterialTheme.typography.titleMedium,
                        color = if (r.v.pctChg >= 0) UpColor else DownColor)
                }
            }
            extra?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val ctx = LocalContext.current
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (symbol.startsWith("sh") || symbol.startsWith("sz") || symbol.startsWith("hk"))
                        vm.addBySearch(SymbolEntry(symbol, name))
                    else vm.addStockCode(symbol, name)
                    onDismiss()
                }) { Text("+ 加自选") }
                OutlinedButton(onClick = {
                    try {
                        val url = if (symbol.startsWith("hk"))
                            "https://gu.qq.com/hk${symbol.removePrefix("hk")}"
                        else "https://gu.qq.com/${symbol.removePrefix("sh").removePrefix("sz")}/gp"
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url)))
                    } catch (_: Exception) {}
                }) { Text("看K线") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private suspend fun repo_quote(symbol: String): Res<Quote> {
    return try {
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = "https://qt.gtimg.cn/q=$symbol"
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000; conn.readTimeout = 10000
            val text = String(conn.inputStream.readBytes(), charset("GBK"))
            conn.disconnect()
            for (line in text.split(";")) {
                val l = line.trim()
                if (!l.contains("=")) continue
                val sym = l.substringBefore("=").removePrefix("v_")
                if (sym != symbol) continue
                val f = l.substringAfter("=").removeSurrounding("\"").split("~")
                if (f.size < 6) continue
                val price = f.getOrNull(3)?.toDoubleOrNull() ?: 0.0
                val prev = f.getOrNull(4)?.toDoubleOrNull() ?: 0.0
                val pct = f.getOrNull(32)?.toDoubleOrNull()
                    ?: if (prev > 0) (price - prev) / prev * 100 else 0.0
                return@withContext Quote(sym, f[1], price, pct)
            }
            throw Exception("无数据")
        }
        Res.Ok(r)
    } catch (e: Exception) { Res.Err(e.message ?: "加载失败") }
}

// ---------- Tab 行情 ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotesTab(vm: SkappViewModel) {
    val quotes by vm.quotes.collectAsState()
    val pulse by vm.pulse.collectAsState()
    val idx by vm.searchIndex.collectAsState()
    var query by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 市场脉搏条（网站同源实时）
        item {
            when (val p = pulse) {
                is Res.Ok -> ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${p.v.session} · ${p.v.ts.take(16)}", Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${p.v.phase}", style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        Text("涨 ${p.v.advance} / 跌 ${p.v.decline} · 涨停 ${p.v.limitUp} 跌停 ${p.v.limitDown} · 成交 ${p.v.turnoverYi} 亿",
                            style = MaterialTheme.typography.bodySmall)
                        if (p.v.phaseDesc.isNotBlank()) Text(p.v.phaseDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {}
            }
        }
        // 搜索框
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it; vm.ensureSearch() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("搜股票名字或代码") },
                singleLine = true,
            )
        }
        if (query.isNotBlank()) {
            item {
                val results = when (val s = idx) {
                    is Res.Ok -> s.v.filter {
                        it.name.contains(query, true) || it.symbol.contains(query.lowercase())
                    }.take(8)
                    is Res.Err -> emptyList()
                }
                Column {
                    results.forEach { e ->
                        ListItem(headlineContent = { Text(e.name) },
                            supportingContent = { Text(e.symbol) },
                            trailingContent = {
                                TextButton(onClick = { vm.addBySearch(e); query = "" }) { Text("+自选") }
                            })
                    }
                }
            }
        }
        // 自选卡片
        when (val r = quotes) {
            is Res.Err -> item {
                if (r.message != "加载中") Column(Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(r.message, textAlign = TextAlign.Center)
                    Button(onClick = { vm.refreshQuotes() }) { Text("重试") }
                } else LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            }
            is Res.Ok -> items(r.v, key = { it.symbol }) { q ->
                var showDetail by remember { mutableStateOf(false) }
                ElevatedCard(onClick = { showDetail = true },
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
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
                if (showDetail) SymbolDetailSheet(q.symbol, q.name, null, { showDetail = false }, vm)
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

// ---------- Tab 推股（今日实时） ----------
@Composable
fun PicksTab(vm: SkappViewModel) {
    val picks by vm.todayPicks.collectAsState()
    ResView(picks, { vm.refreshLive() }) { list ->
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.section + it.code }) { p ->
                var showDetail by remember { mutableStateOf(false) }
                ElevatedCard(onClick = { showDetail = true }, Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name.ifBlank { p.code }, style = MaterialTheme.typography.titleMedium)
                                Text("${p.code} · ${p.section}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (!p.price.isNaN()) Text("%.2f".format(p.price),
                                style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(8.dp))
                            Text("%.1f".format(p.score), style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        if (p.extra.isNotBlank()) Text(p.extra, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (p.why.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            p.why.take(2).forEach { Text("· $it", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                        }
                    }
                }
                if (showDetail) {
                    val detail = p.section + " · 评分 " + "%.1f".format(p.score) +
                        (if (p.extra.isNotBlank()) "\n" + p.extra else "") +
                        p.why.joinToString("") { "\n· $it" }
                    SymbolDetailSheet(p.code, p.name, detail, { showDetail = false }, vm)
                }
            }
        }
    }
}

// ---------- Tab 决策（十维决策卡+仓位+回避） ----------
@Composable
fun DecisionTab(vm: SkappViewModel) {
    val dec by vm.decision.collectAsState()
    val avoid by vm.avoid.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ResView(dec, { vm.refreshLive() }) { (advice, cards) ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("仓位建议：${advice.label}", style = MaterialTheme.typography.titleMedium)
                            Text("市场环境 ${advice.regime} · 仓位上限 ${advice.capPct}%",
                                style = MaterialTheme.typography.bodyMedium)
                            if (advice.reason.isNotBlank()) Text(advice.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    cards.forEach { c ->
                        var showDetail by remember { mutableStateOf(false) }
                        ElevatedCard(onClick = { showDetail = true }, Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(c.name.ifBlank { c.code }, Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleMedium)
                                    Text("%.1f".format(c.composite),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = when (c.action) {
                                            "buy" -> UpColor; "avoid" -> DownColor
                                            else -> MaterialTheme.colorScheme.primary
                                        })
                                }
                                Text("${c.code} · ${c.action} · 十维: " +
                                    c.dims.entries.filter { it.value != 50.0 }
                                        .joinToString(" ") { "${it.key}${it.value.toInt()}" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                c.reasons.take(1).forEach { Text(it, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2) }
                            }
                        }
                        if (showDetail) SymbolDetailSheet(c.code, c.name,
                            "综合 %.1f · %s\n%s".format(c.composite, c.action,
                                c.reasons.joinToString("\n") { "· $it" }), { showDetail = false }, vm)
                    }
                    // 回避名单
                    when (val a = avoid) {
                        is Res.Ok -> if (a.v.isNotEmpty()) ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("今日回避", style = MaterialTheme.typography.titleMedium,
                                    color = DownColor)
                                a.v.forEach { av ->
                                    Column {
                                        Row {
                                            Text("${av.name.ifBlank { av.code }}", Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodyMedium)
                                        }
                                        if (av.risk.isNotBlank()) Text("⚠ ${av.risk}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = DownColor)
                                    }
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}

// ---------- Tab 消息 ----------
@Composable
fun NewsTab(vm: SkappViewModel) {
    val news by vm.news.collectAsState()
    ResView(news, { vm.refreshNews() }) { list ->
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list) { n ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(n.sentiment, style = MaterialTheme.typography.labelMedium,
                                color = when (n.sentiment) {
                                    "利好" -> UpColor; "利空" -> DownColor
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                })
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

// ---------- Tab 我的 ----------
@Composable
fun SettingsTab(token: String, onToken: (String) -> Unit, vm: SkappViewModel) {
    var draft by remember { mutableStateOf(token) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("GitHub 凭据（可选，支持多个）", style = MaterialTheme.typography.titleMedium)
        Text(
            "推股/决策等数据与网站同源（raw 直读），一般无需凭据。\n" +
            "raw 被限流/无法访问时自动改用 GitHub API，此时需 token。\n" +
            "一行一个 token，支持多个账号（jiegengxiangjianxue / jiegengxiangjianxue1 / dufuwuxin），\n" +
            "取首个有效行使用。",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = draft, onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("每行一个 ghp_… / github_pat_…") },
            minLines = 3,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onToken(draft) }) { Text("保存") }
            OutlinedButton(onClick = { draft = ""; onToken("") }) { Text("清除") }
            Button(onClick = { vm.refreshQuotes(); vm.refreshNews(); vm.refreshLive() }) { Text("立即刷新") }
        }
        HorizontalDivider()
        Text("数据链路（与网站同源）", style = MaterialTheme.typography.titleMedium)
        Text(
            "· 自选行情：腾讯接口，30 秒自动刷新\n" +
            "· 今日推股/决策卡/回避：qwerzxcva/stocks-ai-view（网站实时仓，2 分钟轮询）\n" +
            "· 备源：dufuwuxin/stocks-ai-view-c\n" +
            "· 快讯：新浪 7x24，60 秒自动刷新",
            style = MaterialTheme.typography.bodySmall)
        Text("推股与决策为引擎自动产出，仅供观察，不构成投资建议。",
            style = MaterialTheme.typography.bodySmall)
    }
}
