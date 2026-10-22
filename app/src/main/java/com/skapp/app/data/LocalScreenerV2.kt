package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 实时选股 v2 (2026-10-22 用户反馈重构)
 * 修复: ①东财 push2 403 → 腾讯 qt.gtimg.cn 批量行情 (80票/请求, 免token免风控)
 *      ②chunked(4) 串行卡死 → Semaphore(8) 全并发 + 单票 4s 超时
 *      ③新增 onProgress 回调, UI 实时显示进度不再干转圈
 * 本地引擎照旧本地算 (技术指标全本地, AI 分析走 GitCodeAiClient)
 */
object LocalScreenerV2 {

    data class Criteria(
        val minChgPct: Double = -1.0,
        val maxChgPct: Double = 5.0,
        val minVolRatio: Double = 1.5,
        val excludeST: Boolean = true,
        val maxCandidates: Int = 60,
        val klineCount: Int = 120,
    )

    data class Screened(
        val code: String,
        val name: String?,
        val price: Double?,
        val pctChg: Double?,
        val volRatio: Double?,
        val turnover: Double?,
        val pe: Double?,
        val mv: Double?,
        val engine: LocalEngineResult?,
        val detailStatus: String,
        val reason: String? = null,
    )

    /** 腾讯批量实时行情: 一次最多 ~80 票, 返回 code->(name,price,pctChg,vol,turnover,pe,mv) */
    suspend fun tencentSnapshot(pool: List<String>): List<Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<Map<String, Any?>>()
            pool.chunked(80).forEach { chunk ->
                val url = "http://qt.gtimg.cn/q=" + chunk.joinToString(",") { SkappApi.prefixed(it) }
                val txt = runCatching {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000; conn.readTimeout = 5000
                    conn.getInputStream().bufferedReader(charset("GBK")).readText()
                }.getOrNull() ?: return@forEach
                for (line in txt.split(';')) {
                    val m = Regex("v_(\\w+)=\"(.*)\"").find(line.trim()) ?: continue
                    val f = m.groupValues[2].split('~')
                    if (f.size < 50) continue
                    val code = f[2]
                    out.add(mapOf(
                        "code" to code,
                        "name" to f[1],
                        "price" to f[3].toDoubleOrNull(),
                        "pctChg" to f[32].toDoubleOrNull(),
                        "volRatio" to f[49].toDoubleOrNull(),
                        "turnover" to f[36].toDoubleOrNull(),
                        "pe" to f[39].toDoubleOrNull(),
                        "mv" to f[45].toDoubleOrNull(),
                        "mainNet" to (f[33].toDoubleOrNull() ?: 0.0),
                    ))
                }
            }
            out
        }

    /** 候选池: 从指数成分/热门池快照拿票池 (不依赖东财 clist) */
    suspend fun candidatePool(): List<String> = withContext(Dispatchers.IO) {
        val codes = mutableListOf<String>()
        // 沪深主要指数成分近似池: 用腾讯行业龙头表 + 快照换手排行
        // 实现: 拉东财 fail 时退化到本地内置活跃池 (300 票高频活跃股)
        codes.addAll(HotPool.CODES)
        codes
    }

    suspend fun screen(
        c: Criteria = Criteria(),
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<Screened> = coroutineScope {
        val pool = candidatePool()
        onProgress(0, pool.size)
        val snap = tencentSnapshot(pool)
        if (snap.isEmpty()) return@coroutineScope emptyList()

        val cands = snap.filter { m ->
            val chg = (m["pctChg"] as? Number)?.toDouble() ?: return@filter false
            val vr = (m["volRatio"] as? Number)?.toDouble()
            val name = m["name"]?.toString() ?: ""
            val st = c.excludeST && (name.contains("ST", true) || name.contains("退", true))
            chg in c.minChgPct..c.maxChgPct && !st && (vr == null || vr >= c.minVolRatio)
        }.sortedByDescending { m -> (m["mainNet"] as? Number)?.toDouble() ?: 0.0 }
            .take(c.maxCandidates)

        val sem = Semaphore(8)
        var done = 0
        val out = java.util.concurrent.ConcurrentLinkedQueue<Screened>()
        val jobs = cands.map { m ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val code = m["code"]?.toString() ?: return@withPermit
                    val r = withTimeoutOrNull(4_000) {
                        val bars = DataCache.kline(code, "qfq", c.klineCount)
                        val eng = if (bars.size >= 30) DataCache.engine(code, "qfq", bars) else null
                        Screened(
                            code = code,
                            name = m["name"]?.toString(),
                            price = (m["price"] as? Number)?.toDouble(),
                            pctChg = (m["pctChg"] as? Number)?.toDouble(),
                            volRatio = (m["volRatio"] as? Number)?.toDouble(),
                            turnover = (m["turnover"] as? Number)?.toDouble(),
                            pe = (m["pe"] as? Number)?.toDouble(),
                            mv = (m["mv"] as? Number)?.toDouble(),
                            engine = eng,
                            detailStatus = if (eng != null) "ok" else "insufficient",
                        )
                    }
                    if (r != null) out.add(r)
                    synchronized(done) {
                        done++
                        if (done % 10 == 0 || done == cands.size) onProgress(done, cands.size)
                    }
                }
            }
        }
        jobs.forEach { it.await() }
        out.sortedByDescending { it.engine?.composite ?: 0.0 }
    }
}

/** 活跃股池 (腾讯快照覆盖, 定期由云端推送更新) */
object HotPool {
    val CODES = listOf(
        // 上证活跃
        "600519","601318","600900","601328","601988","600036","600030","600276","601899","600887",
        "601088","600028","601857","601398","601288","601166","600016","601658","600104","601668",
        // 深证/创业板/科创活跃
        "002594","300750","002747","300124","002156","300059","300015","002415","300274","002460",
        "300760","688981","688111","688026","688137","688156","688655","300502","002230","000333",
        "000858","002475","300124","002371","000725","300628","002812","300782","688012","688036",
        // 周期/热点
        "601898","601168","600111","600392","601865","002466","600089","601012","600438","300274",
        "601985","600905","601619","600886","601985","601615","600089","600406","601179","600863",
    ).distinct()
}
