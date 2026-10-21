package com.skapp.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

data class Quote(val symbol: String, val name: String, val price: Double, val pctChg: Double)
data class NewsItem(val ts: String, val text: String, val sentiment: String)
data class LivePick(
    val code: String, val name: String, val price: Double?, val score: Double,
    val section: String, val why: List<String>, val extra: String,
)
data class MarketPulse(
    val ts: String, val session: String, val advance: Int, val decline: Int,
    val limitUp: Int, val limitDown: Int, val turnoverYi: Double, val phase: String,
    val phaseDesc: String,
)
data class DecisionCard(
    val code: String, val name: String, val composite: Double,
    val action: String, val reasons: List<String>, val dims: Map<String, Double>,
)
data class PositionAdvice(val label: String, val capPct: Int, val reason: String, val regime: String)
data class AvoidItem(val code: String, val name: String, val risk: String, val reasons: List<String>)
data class SymbolEntry(val symbol: String, val name: String)

sealed interface Res<out T> {
    data class Ok<T>(val v: T) : Res<T>
    data class Err(val message: String) : Res<Nothing>
}

private fun httpGet(url: String, headers: Map<String, String> = emptyMap(),
                    timeoutMs: Int = 15000): Pair<Int, ByteArray> {
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

/**
 * 实时数据客户端 —— 与网站同源：
 * 主源 raw.githubusercontent.com/qwerzxcva/stocks-ai-view（网站 pages-data-sync 同一仓，
 * 收盘/盘中 Actions 高频刷新，ts 精确到秒）；备源 skapp-data。
 * githubToken 仅用于 GitHub API 兜底（私仓模式/限额），不影响主链路。
 */
class MarketRepository(private val githubToken: () -> String) {

    private fun firstToken(): String = githubToken().split("\n", " ", ",")
        .map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""

    /** 依序尝试多个源，第一个成功即返回 */
    private suspend fun liveFile(vararg paths: String): Res<String> = withContext(Dispatchers.IO) {
        for (p in paths) {
            try {
                val (code, bytes) = httpGet("$p?t=" + System.currentTimeMillis())
                if (code in 200..299 && bytes.isNotEmpty()) return@withContext Res.Ok(String(bytes))
            } catch (_: Exception) {}
        }
        // token 兜底：GitHub contents API（任意有效 token，避免 raw 被墙/限流）
        val tok = firstToken()
        if (tok.isNotEmpty()) {
            for (repo in LIVE_REPOS) {
                for (p in paths) {
                    val rel = p.substringAfter("/main/")
                    try {
                        val (code, bytes) = httpGet(
                            "https://api.github.com/repos/$repo/contents/$rel?ref=main&t=" + System.currentTimeMillis(),
                            mapOf("Authorization" to "Bearer $tok", "Accept" to "application/vnd.github.raw"))
                        if (code in 200..299 && bytes.isNotEmpty()) return@withContext Res.Ok(String(bytes))
                    } catch (_: Exception) {}
                }
            }
        }
        Res.Err("全部数据源加载失败（检查网络）")
    }

    /** 腾讯实时行情（GBK），A股+港股 —— 自选列表真正的实时 */
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

    /** 新浪 7x24 快讯 + 情感标注 */
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

    /** 大盘脉搏：涨跌家数/涨停/成交额/阶段（网站实时同源） */
    suspend fun marketPulse(): Res<MarketPulse> {
        return when (val raw = liveFile(
            "$RAW_A/data/today_picks.json", "$RAW_B/data/today_picks.json")) {
            is Res.Err -> raw
            is Res.Ok -> try {
                val d = JSONObject(raw.v)
                val m = d.getJSONObject("market")
                Res.Ok(MarketPulse(
                    ts = d.optString("ts", ""), session = d.optString("session", ""),
                    advance = m.optInt("advance", 0), decline = m.optInt("decline", 0),
                    limitUp = m.optInt("limit_up", 0), limitDown = m.optInt("limit_down", 0),
                    turnoverYi = m.optDouble("turnover_yi", 0.0),
                    phase = m.optString("phase", ""), phaseDesc = m.optString("phase_desc", ""),
                ))
            } catch (e: Exception) { Res.Err("市场脉搏解析失败") }
        }
    }

    /** 今日实时推股（signals 多引擎共振 + values 价值池 + limit_up 涨停预测） */
    suspend fun todayPicks(): Res<List<LivePick>> {
        return when (val raw = liveFile(
            "$RAW_A/data/today_picks.json", "$RAW_B/data/today_picks.json")) {
            is Res.Err -> raw
            is Res.Ok -> try {
                val d = JSONObject(raw.v)
                val p = d.getJSONObject("picks")
                val out = mutableListOf<LivePick>()
                p.optJSONArray("signals")?.let { a ->
                    (0 until a.length()).map { i -> a.optJSONObject(i) }.filterNotNull().forEach { o ->
                        val why = o.optJSONArray("why")?.let { w ->
                            (0 until w.length()).map { w.optString(it) } } ?: emptyList()
                        out.add(LivePick(o.optString("code"), o.optString("name", ""),
                            o.optDouble("price", Double.NaN), o.optDouble("score", 0.0),
                            "多引擎共振", why,
                            "共振 ${(o.optDouble("resonance", 0.0) * 100).toInt()}% · 多${o.optInt("bull", 0)}/空${o.optInt("bear", 0)} · 上涨概率 ${o.optDouble("p_up", 0.0)}%"))
                    }
                }
                p.optJSONArray("values")?.let { a ->
                    (0 until a.length()).map { i -> a.optJSONObject(i) }.filterNotNull().forEach { o ->
                        out.add(LivePick(o.optString("code"), o.optString("name", ""),
                            o.optDouble("price", Double.NaN), o.optDouble("score", 0.0) * 100,
                            "价值池",
                            listOf("240日分位 ${(o.optDouble("pos240_pct", 0.0)).toInt()}% · 量比 ${o.optDouble("vratio", 0.0)}"),
                            "当日涨跌 ${o.optDouble("ret_pct", 0.0)}%"))
                    }
                }
                Res.Ok(out)
            } catch (e: Exception) { Res.Err("推股解析失败") }
        }
    }

    /** 十维决策卡（网站「个股决策卡」同源） */
    suspend fun decisionCards(): Res<Pair<PositionAdvice, List<DecisionCard>>> {
        return when (val raw = liveFile(
            "$RAW_A/data/decision.json", "$RAW_B/data/decision.json")) {
            is Res.Err -> raw
            is Res.Ok -> try {
                val d = JSONObject(raw.v)
                val pa = d.getJSONObject("position_advice")
                val advice = PositionAdvice(
                    pa.optString("label", ""), (pa.optDouble("cap", 0.0) * 100).toInt(),
                    pa.optString("reason", ""), d.optString("market_regime", "?"))
                val cards = d.optJSONArray("cards")?.let { a ->
                    (0 until a.length()).map { i -> a.optJSONObject(i) }.filterNotNull().map { o ->
                        val dims = o.optJSONObject("dims")?.let { dd ->
                            dd.keys().asSequence().map { k -> k to dd.optDouble(k, 0.0) }.toMap()
                        } ?: emptyMap()
                        DecisionCard(o.optString("code"), o.optString("name", ""),
                            o.optDouble("composite", 0.0), o.optString("action", ""),
                            o.optJSONArray("reasons")?.let { r ->
                                (0 until r.length()).map { r.optString(it) } } ?: emptyList(),
                            dims)
                    }
                } ?: emptyList()
                Res.Ok(advice to cards)
            } catch (e: Exception) { Res.Err("决策解析失败") }
        }
    }

    /** 回避名单 */
    suspend fun avoidList(): Res<List<AvoidItem>> {
        return when (val raw = liveFile(
            "$RAW_A/data/decision.json", "$RAW_B/data/decision.json")) {
            is Res.Err -> raw
            is Res.Ok -> try {
                val d = JSONObject(raw.v)
                Res.Ok(d.optJSONArray("avoid")?.let { a ->
                    (0 until a.length()).map { i -> a.optJSONObject(i) }.filterNotNull().map { o ->
                        AvoidItem(o.optString("code"), o.optString("name", ""),
                            o.optString("risk", ""),
                            o.optJSONArray("reasons")?.let { r ->
                                (0 until r.length()).map { r.optString(it) } } ?: emptyList())
                    }
                } ?: emptyList())
            } catch (e: Exception) { Res.Err("回避名单解析失败") }
        }
    }

    /** 搜索索引：网站同源 stock_names（5278条，秒级刷新）+ 本地 skapp-data 兜底 */
    private var searchCache: List<SymbolEntry>? = null
    suspend fun searchIndex(): Res<List<SymbolEntry>> {
        searchCache?.let { return Res.Ok(it) }
        val raw = liveFile("$RAW_A/data/stock_names.json", "$RAW_B/data/stock_names.json",
            "$RAW_B2/data/search/symbols.json")
        return when (raw) {
            is Res.Err -> raw
            is Res.Ok -> try {
                val d = JSONObject(raw.v)
                val list = d.keys().asSequence().map { code ->
                    val v = d.opt(code)
                    if (v is JSONObject) SymbolEntry(code, v.optString("n"))
                    else SymbolEntry(code, v.toString())
                }.toList()
                searchCache = list
                Res.Ok(list)
            } catch (e: Exception) { Res.Err("搜索索引解析失败") }
        }
    }

    companion object {
        // 网站同源双仓（qwerzxcva 主、dufuwuxin 备），与 gh_sources.js 相同的降级策略
        const val RAW_A = "https://raw.githubusercontent.com/qwerzxcva/stocks-ai-view/main/data"
        const val RAW_B = "https://raw.githubusercontent.com/dufuwuxin/stocks-ai-view-c/main/data"
        const val RAW_B2 = "https://raw.githubusercontent.com/jiegengxiangjianxue/skapp-data/main/data"
        val LIVE_REPOS = arrayOf(
            "qwerzxcva/stocks-ai-view", "dufuwuxin/stocks-ai-view-c",
            "jiegengxiangjianxue/skapp-data")
    }
}
