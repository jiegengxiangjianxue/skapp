package com.skapp.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SkappTheme { WatchScreen() } }
    }
}

/** Material 3 主题 */
@Composable
fun SkappTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(), content = content)
}

data class Quote(val symbol: String, val name: String, val price: Double, val pct: Double)

/** 自选股观察页：实时行情，Material 3 卡片列表 */
@Composable
fun WatchScreen() {
    var quotes by remember { mutableStateOf<List<Quote>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // 部署时把 <HOST>:8800 换成实际 API 地址
            val syms = listOf("sh600519", "sz300750", "sz002594", "sh600941")
            val list = syms.mapNotNull { s ->
                runCatching {
                    val o = org.json.JSONObject(
                        URL("http://<HOST>:8800/api/v1/quote/$s").readText())
                    Quote(o.getString("symbol"), o.optString("name", s),
                        o.getDouble("price"), o.getDouble("pct_chg"))
                }.getOrNull()
            }
            quotes = list
            loading = false
            if (list.isEmpty()) error = "无法连接 API"
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("skapp 自选观察") }) }) { pad ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text(error ?: "")
            }
            else -> LazyColumn(Modifier.padding(pad).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(quotes) { q ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(q.name, style = MaterialTheme.typography.titleMedium)
                                Text(q.symbol, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("%.2f".format(q.price), style = MaterialTheme.typography.titleLarge)
                            Text("%+.2f%%".format(q.pct),
                                color = if (q.pct >= 0) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
