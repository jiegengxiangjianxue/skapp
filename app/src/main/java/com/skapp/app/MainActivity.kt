@file:OptIn(ExperimentalMaterial3Api::class)

package com.skapp.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
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
    val picks = MutableStateFlow<Res<List<PickItem>>>(Res.Err("加载中"))
    val decision = MutableStateFlow<Res<Decision>>(Res.Err("加载中"))
    val fusion = MutableStateFlow<Res<Fusion>>(Res.Err("加载中"))
    val aiPicks = MutableStateFlow<Res<List<ScoredPick>>>(Res.Err("加载中"))
    val strategies = MutableStateFlow<Res<List<StrategyRank>>>(Res.Err("加载中"))
    val searchIndex = MutableStateFlow<Res<List<SymbolEntry>>>(Res.Err("加载中"))

    private val _watchlist = MutableStateFlow(
        listOf("sh600519", "sz300750", "sz002594", "sh600941", "hk00700"))
    val watchlist: StateFlow<List<String>> = _watchlist.asStateFlow()

    fun refreshQuotes() {
        viewModelScope.launch {
            quotes.value = Res.Err("加载中")
            val r = repo.realtimeQuotes(_watchlist.value)
            val names = (searchIndex.value as? Res.Ok)?.v?.associate { it.symbol to it.name } ?: emptyMap()
            quotes.value = when (r) {
                is Res.Ok -> Res.Ok(r.v.map { q -> q.copy(name = names[q.symbol] ?: q.name) })
                is Res.Err -> r
            }
        }
    }
    fun refreshNews() { viewModelScope.launch { news.value = repo.news() } }
    fun refreshPicks() { viewModelScope.launch { picks.value = repo.picks() } }
    fun refreshAnalysis() {
        viewModelScope.launch { decision.value = repo.decision() }
        viewModelScope.launch { fusion.value = repo.fusion() }
        viewModelScope.launch { aiPicks.value = repo.aiPicks() }
        viewModelScope.launch { strategies.value = repo.strategyRank() }
    }
    fun refreshAll() {
        refreshQuotes(); refreshNews(); refreshPicks(); refreshAnalysis(); ensureSearch()
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
                AppRoot(vm, token) { t ->
                    val changed = t != token
                    token = t
                    prefs.edit().putString("gh_token", t).apply()
                    if (changed) vm.refreshAll()
                }
            }
        }
    }
}

@Composable
fun AppRoot(vm: SkappViewModel, token: String, onToken: (String) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { vm.refreshAll() }
    Scaffold(
        bottomBar = {
            NavigationBar {
                val items = listOf("行情", "推股", "分析", "消息", "我的")
                items.forEachIndexed { i, label ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i },
                        icon = { Text(listOf("📈", "⭐", "🧠", "📰", "⚙")[i]) },
                        label = { Text(label) })
                }
            }
        },
        topBar = {
            TopAppBar(title = { Text(listOf("自选行情", "引擎推股", "策略分析", "市场消息", "设置")[tab]) })
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> QuotesTab(vm)
                1 -> PicksTab(vm)
                2 -> AnalysisTab(vm)
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

// ---------- 个股详情弹窗 ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SymbolDetailSheet(symbol: String, name: String, extra: String?, onDismiss: () -> Unit, vm: SkappViewModel) {
    var q by remember { mutableStateOf<Res<Quote>>(Res.Err("加载中")) }
    LaunchedEffect(symbol) {
        val r = repo_quote(vm, symbol)
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.addBySearch(SymbolEntry(symbol, name)); onDismiss() }) { Text("+ 加自选") }
                val ctx = androidx.compose.ui.platform.LocalContext.current
                OutlinedButton(onClick = {
                    try {
                        val sym2 = if (symbol.startsWith("hk")) symbol.removePrefix("hk") else symbol
                        val url = if (symbol.startsWith("hk")) "https://gu.qq.com/hk$sym2" else "https://gu.qq.com/$sym2/gp"
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url)))
                    } catch (_: Exception) {}
                }) { Text("看K线") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private suspend fun repo_quote(vm: SkappViewModel, symbol: String): Res<Quote> {
    // 单票实时行情
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
    val idx by vm.searchIndex.collectAsState()
    var query by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = {
                query = it; vm.ensureSearch()
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("搜股票名字或代码，如：中天科技 / 600522") },
            singleLine = true,
        )
        if (query.isNotBlank()) {
            val results = when (val s = idx) {
                is Res.Ok -> s.v.filter {
                    it.name.contains(query, true) || it.symbol.contains(query.lowercase())
                }.take(10)
                is Res.Err -> emptyList()
            }
            val idxLoading = (idx as? Res.Err)?.message == "加载中"
            if (idxLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(results, key = { it.symbol }) { e ->
                    ListItem(headlineContent = { Text(e.name) },
                        supportingContent = { Text(e.symbol) },
                        trailingContent = {
                            TextButton(onClick = { vm.addBySearch(e); query = "" }) { Text("+自选") }
                        })
                }
            }
            HorizontalDivider()
        }
        ResView(quotes, { vm.refreshQuotes() }) { list ->
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.symbol }) { q ->
                    var showDetail by remember { mutableStateOf(false) }
                    ElevatedCard(onClick = { showDetail = true }, Modifier.fillMaxWidth()) {
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
        }
    }
}

// ---------- Tab 推股 ----------
@Composable
fun PicksTab(vm: SkappViewModel) {
    val picks by vm.picks.collectAsState()
    val ai by vm.aiPicks.collectAsState()
    var sub by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(sub) {
            Tab(sub == 0, { sub = 0 }) { Text("引擎推股", Modifier.padding(12.dp)) }
            Tab(sub == 1, { sub = 1 }) { Text("AI 选股 Top50", Modifier.padding(12.dp)) }
        }
        if (sub == 0) ResView(picks, { vm.refreshPicks() }) { list ->
            LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.symbol + it.rank }) { p ->
                    var showDetail by remember { mutableStateOf(false) }
                    ElevatedCard(onClick = { showDetail = true }, Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("#${p.rank}", style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(52.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name.ifBlank { p.symbol }, style = MaterialTheme.typography.titleMedium)
                                if (p.name.isNotBlank()) Text(p.symbol, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (showDetail) SymbolDetailSheet(p.symbol, p.name, "引擎推股 #${p.rank}（composite_pool_v3）", { showDetail = false }, vm)
                }
            }
        }
        else ResView(ai, { vm.refreshAnalysis() }) { list ->
            LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.symbol }) { p ->
                    var showDetail by remember { mutableStateOf(false) }
                    ElevatedCard(onClick = { showDetail = true }, Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name.ifBlank { p.symbol }, style = MaterialTheme.typography.titleMedium)
                                if (p.name.isNotBlank()) Text(p.symbol, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("%.3f".format(p.score), style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (showDetail) SymbolDetailSheet(p.symbol, p.name, "AI选股复合分 %.3f（v4 全市场）".format(p.score), { showDetail = false }, vm)
                }
            }
        }
    }
}

// ---------- Tab 分析 ----------
@Composable
fun AnalysisTab(vm: SkappViewModel) {
    val dec by vm.decision.collectAsState()
    val fus by vm.fusion.collectAsState()
    val strat by vm.strategies.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ResView(dec, { vm.refreshAnalysis() }) { d ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("今日决策", style = MaterialTheme.typography.titleMedium)
                        Text(d.action, style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary)
                        Text("建议仓位：${(d.positionPct * 100).toInt()}%   市场情绪：${d.mood}(${d.score})",
                            style = MaterialTheme.typography.bodyMedium)
                        d.reasons.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        item {
            ResView(dec, { vm.refreshAnalysis() }) { d ->
                if (d.finPicks.isNotEmpty()) ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("财务选股 Top${d.finPicks.size}", style = MaterialTheme.typography.titleMedium)
                        d.finPicks.forEach { f ->
                            Column(Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(f.name.ifBlank { f.symbol }, Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text("%.1f".format(f.score), style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                                if (f.why.isNotBlank()) Text(f.why, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        item {
            ResView(fus, { vm.refreshAnalysis() }) { f ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("引擎融合 E=${f.e}", style = MaterialTheme.typography.titleMedium)
                        Text("状态：${f.state}   引擎一致度：${(f.agreement * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium)
                        f.parts.forEach { (k, v) ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text(if (v.missing) "缺数据" else "score=${v.score}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (v.missing) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.primary)
                                Text(" w=${v.weight}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        item {
            ResView(strat, { vm.refreshAnalysis() }) { list ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("策略排名", style = MaterialTheme.typography.titleMedium)
                        list.forEach { s ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(s.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text(s.extra, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
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

// ---------- Tab 我的：凭据接口（可填多个，任一账号有读取权限即可） ----------
@Composable
fun SettingsTab(token: String, onToken: (String) -> Unit, vm: SkappViewModel) {
    var draft by remember { mutableStateOf(token) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("GitHub 凭据（可选，支持多个）", style = MaterialTheme.typography.titleMedium)
        Text(
            "可不填：推股/分析/策略走公开数据仓，匿名即可。\n" +
            "填写后走 GitHub API 读取（更稳、限额更高）。一行一个 token，\n" +
            "支持多个账号（jiegengxiangjianxue / jiegengxiangjianxue1 / dufuwuxin 等），\n" +
            "任一有效即自动使用；全部无效自动回退匿名读取。",
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
            Button(onClick = { vm.refreshAll() }) { Text("全部刷新") }
        }
        HorizontalDivider()
        Text("数据链路", style = MaterialTheme.typography.titleMedium)
        Text(
            "· 行情：腾讯 qt（A股+港股实时）\n" +
            "· 快讯：新浪 7x24（利好/利空规则同后端）\n" +
            "· 推股/决策/融合/策略：skapp-data 仓（网站同源产物）\n" +
            "· 收盘后 Actions 自动更新引擎数据与快照",
            style = MaterialTheme.typography.bodySmall)
        Text("推股与分析为引擎历史产物，仅供观察，不构成投资建议。",
            style = MaterialTheme.typography.bodySmall)
    }
}
