package com.skapp.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class Quote(val symbol: String, val name: String, val price: Double, val pctChg: Double)
data class NewsItem(val ts: String, val text: String, val sentiment: String)
data class PickItem(val symbol: String, val rank: Int)
data class SymbolEntry(val symbol: String, val name: String)
data class Decision(
    val action: String, val positionPct: Double, val reasons: List<String>,
    val mood: String, val score: Int,
)
data class Fusion(val e: Double, val state: String, val agreement: Double,
                  val parts: Map<String, PartScore>)
data class PartScore(val score: Int, val missing: Boolean, val weight: Double)
data class ScoredPick(val symbol: String, val score: Double)
data class StrategyRank(val name: String, val score: Double, val extra: String)

sealed interface Res<out T> {
    data class Ok<T>(val v: T) : Res<T>
    data class Err(val message: String) : Res<Nothing>
}

private fun httpGet(url: String, headers: Map<String, String> = emptyMap(),
                    timeoutMs: Int = 12000): Pair<Int, ByteArray> {
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

    /** 腾讯实时行情（GBK），A股+港股 */
    suspend fun realtimeQuotes(symbols: List<String>): Res<List<Quote>> =
        withContext(Dispatchers.IO) {
            try {
                if (symbols.isEmpty()) return@withContext Res.Ok(emptyList())
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
                Res.Ok(out)
            } catch (e: Exception) { Res.Err(e.message ?: "行情加载失败") }
        }

    /** 新浪 7x24 快讯 + 情感标注（与 eod_sync 同规则） */
    suspend fun news(num: Int = 30): Res<List<NewsItem>> = withContext(Dispatchers.IO) {
        try {
            val url = "https://zhibo.sina.com.cn/api/zhibo/feed?page=1&page_size=$num&zhibo_id=152&tag_id=0"
            val d = JSONObject(String(httpGet(url).second))
            val list = d.getJSONObject("result").getJSONObject("data")
                .getJSONObject("feed").getJSONArray("list")
            val pos = listOf("利好", "上涨", "增持", "回购", "中标", "增长", "超预期", "分红")
            val neg = listOf("利空", "下跌", "减持", "处罚", "亏损", "警示", "立案", "退市")
            Res.Ok((0 until minOf(num, list.length())).map { i ->
                val it = list.getJSONObject(i)
                val txt = it.optString("rich_text", "")
                val p = pos.count { w -> txt.contains(w) }
                val n = neg.count { w -> txt.contains(w) }
                NewsItem(it.optString("create_time", ""), txt,
                    if (p > n) "利好" else if (n > p) "利空" else "中性")
            })
        } catch (e: Exception) { Res.Err(e.message ?: "快讯加载失败") }
    }

    /** 仓库数据文件 */
    private suspend fun repoFile(path: String): Res<String> = withContext(Dispatchers.IO) {
        try {
            val tok = githubToken().trim()
            if (tok.isEmpty()) return@withContext Res.Err("未配置 GitHub Token（「我的」页填写）")
            val url = "https://api.github.com/repos/jiegengxiangjianxue/skapp/contents/$path"
            val (code, bytes) = httpGet(url, mapOf(
                "Authorization" to "Bearer $tok",
                "Accept" to "application/vnd.github.raw"))
            if (code !in 200..299) return@withContext Res.Err("GitHub $code：$path")
            Res.Ok(String(bytes))
        } catch (e: Exception) { Res.Err(e.message ?: "加载失败") }
    }

    /** 全市场名称搜索索引（首次加载后缓存） */
    private var searchCache: List<SymbolEntry>? = null
    suspend fun searchIndex(): Res<List<SymbolEntry>> {
        searchCache?.let { return Res.Ok(it) }
        val raw = repoFile("data/search/symbols.json")
        return when (raw) {
            is Res.Err -> raw
            is Res.Ok -> try {
                val arr = org.json.JSONArray(raw.v)
                val list = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    SymbolEntry(o.getString("s"), o.getString("n"))
                }
                searchCache = list
                Res.Ok(list)
            } catch (e: Exception) { Res.Err("搜索索引解析失败") }
        }
    }

    suspend fun picks(): Res<List<PickItem>> {
        return when (val raw = repoFile("data/engine/picks_latest.json")) {
            is Res.Err -> raw
            is Res.Ok -> try {
                val d = JSONObject(raw.v)
                val arr = d.getJSONArray("items")
                Res.Ok((0 until arr.length()).take(30).map { i ->
                    val o = arr.getJSONObject(i)
                    PickItem(o.optString("symbol", "?"), o.optInt("rank", i + 1))
                })
            } catch (e: Exception) { Res.Err("推股解析失败") }
        }
    }

    /** 决策：仓位建议+理由（网站「今日决策」同源） */
    suspend fun decision(): Res<Decision> {
        return when (val raw = repoFile("data/engine/decision.json")) {
            is Res.Err -> raw
            is Res.Ok -> try {
                val d = JSONObject(raw.v)
                val dec = d.getJSONObject("decision")
                val g = dec.optJSONObject("global")
                val reasons = d.getJSONArray("reasons").let { a ->
                    (0 until a.length()).map { a.optString(it) } }
                Res.Ok(Decision(
                    action = dec.optString("action", "?"),
                    positionPct = dec.optDouble("position_pct", 0.0),
                    reasons = reasons,
                    mood = g?.optString("mood", "?") ?: "?",
                    score = g?.optInt("score", 0) ?: 0,
                ))
            } catch (e: Exception) { Res.Err("决策解析失败") }
        }
    }

    /** 引擎融合状态 */
    suspend fun fusion(): Res<Fusion> {
        return when (val raw = repoFile("data/engine/engine_fusion.json")) {
            is Res.Err -> raw
            is Res.Ok -> try {
                val d = JSONObject(raw.v)
                val partsRaw = d.optJSONObject("parts")
                val parts = mutableMapOf<String, PartScore>()
                partsRaw?.let { p ->
                    p.keys().forEach { k ->
                        val o = p.getJSONObject(k)
                        parts[k] = PartScore(o.optInt("score", 0),
                            o.optBoolean("missing", false), o.optDouble("weight", 0.0))
                    }
                }
                Res.Ok(Fusion(d.optDouble("E", 0.0), d.optString("state", "?"),
                    d.optDouble("agreement", 0.0), parts))
            } catch (e: Exception) { Res.Err("引擎状态解析失败") }
        }
    }

    /** AI 选股 top50（v4 全市场复合分数） */
    suspend fun aiPicks(): Res<List<ScoredPick>> {
        return when (val raw = repoFile("data/engine/ai_pick_v5.json")) {
            is Res.Err -> raw
            is Res.Ok -> try {
                val d = JSONObject(raw.v)
                val arr = d.getJSONArray("top50")
                Res.Ok((0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ScoredPick(o.getString("symbol"), o.optDouble("score", 0.0))
                })
            } catch (e: Exception) { Res.Err("选股解析失败") }
        }
    }

    /** 策略排名 */
    suspend fun strategyRank(): Res<List<StrategyRank>> {
        return when (val raw = repoFile("data/engine/strategy_rank.json")) {
            is Res.Err -> raw
            is Res.Ok -> try {
                val d = JSONObject(raw.v)
                val rank = d.getJSONObject("ranking")
                val list = rank.keys().asSequence().map { k ->
                    val v = rank.opt(k)
                    val score = when (v) {
                        is Number -> v.toDouble()
                        is JSONObject -> v.optDouble("score", v.optDouble("net", 0.0))
                        else -> 0.0
                    }
                    StrategyRank(k, score, if (v is JSONObject) v.toString().take(80) else v?.toString() ?: "")
                }.sortedByDescending { it.score }.take(15)
                Res.Ok(list)
            } catch (e: Exception) { Res.Err("策略解析失败") }
        }
    }
}
