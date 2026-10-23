package com.skapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 本地 LLM 模型管理器 (2026-10-24)
 * 模型分发仓: github jiegengxiangjianxue1/llm-models (Qwen3-0.6B Q4_K_M GGUF ~484MB)
 * 流程: 用户点下载 → raw.githubusercontent 拉取到 app 私有目录 → 完成/校验大小 →
 *       推理引擎优先 llama.cpp JNI (若已内置), 否则跳转 ChatterUI/MLC 打开同目录 GGUF。
 * 不内置任何 token; 下载为公开仓 raw 链接无需鉴权。
 */
object LocalLlmManager {

    data class ModelInfo(val name: String, val file: String, val bytes: Long, val desc: String)

    val CATALOG = listOf(
        ModelInfo("Qwen3-0.6B (中文金融特化提示)", "Qwen_Qwen3-0.6B-Q4_K_M.gguf", 508_000_000L,
            "0.6B Q4量化, 16GB机型流畅, 盘中本地问答"),
    )

    private const val RAW_BASE = "https://raw.githubusercontent.com/jiegengxiangjianxue/llm-models/main/models"
    // GitHub 100MB 限制: GGUF 已分片 (qwen06b_q8.part-00..06), 下载全部后合并
    private val PARTS = (0..6).map { "qwen06b_q8.part-%02d".format(it) }

    fun modelFile(dir: File, m: ModelInfo): File = File(dir, "llm/${m.file}")

    fun isDownloaded(dir: File, m: ModelInfo): Boolean {
        val f = modelFile(dir, m)
        return f.exists() && f.length() > m.bytes * 9 / 10  // 容差
    }

    /** 分片下载(每片断点续传) + 按序合并 */
    suspend fun downloadParts(dir: File, onProgress: (Int) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                val outDir = File(dir, "llm"); outDir.mkdirs()
                val merged = File(outDir, "Qwen3-0.6B-Q8_0.gguf")
                if (merged.exists() && merged.length() > 600_000_000) return@withContext Result.success(merged)
                for ((idx, part) in PARTS.withIndex()) {
                    val dst = File(outDir, part)
                    if (dst.exists() && dst.length() > 90_000_000L) continue
                    val tmp = File(dst.absolutePath + ".part")
                    val conn = URL("$RAW_BASE/$part").openConnection() as HttpURLConnection
                    if (tmp.exists()) conn.setRequestProperty("Range", "bytes=${tmp.length()}-")
                    conn.connectTimeout = 30000; conn.readTimeout = 60000; conn.instanceFollowRedirects = true
                    conn.inputStream.use { ins -> java.io.FileOutputStream(tmp, true).use { fos ->
                        val buf = ByteArray(1 shl 16); var n: Int
                        while (ins.read(buf).also { n = it } > 0) fos.write(buf, 0, n)
                    } }
                    check(tmp.length() > 70_000_000L) { "part $idx too small: ${'$'}{tmp.length()}" }
                    if (dst.exists()) dst.delete()
                    check(tmp.renameTo(dst)) { "rename $idx" }
                    onProgress(idx * 100 / PARTS.size)
                }
                java.io.FileOutputStream(merged).use { fos ->
                    PARTS.forEach { p -> File(outDir, p).inputStream().use { it.copyTo(fos) } }
                }
                check(merged.length() > 600_000_000L) { "merged too small" }
                Result.success(merged)
            } catch (e: Exception) { Result.failure(e) }
        }

    /** 下载/断点续传, onProgress: 0..100 */
    suspend fun download(dir: File, m: ModelInfo, onProgress: (Int) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                val out = modelFile(dir, m)
                out.parentFile?.mkdirs()
                val tmp = File(out.absolutePath + ".part")
                val start = if (tmp.exists()) tmp.length() else 0
                val conn = URL("$RAW_BASE/${m.file}").openConnection() as HttpURLConnection
                conn.requestMethod = if (start > 0) "GET" else "GET"
                if (start > 0) conn.setRequestProperty("Range", "bytes=$start-")
                conn.connectTimeout = 30000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = true
                val total = conn.contentLength.toLong() + start
                conn.inputStream.use { ins ->
                    java.io.FileOutputStream(tmp, true).use { fos ->
                        val buf = ByteArray(1 shl 16)
                        var n: Int
                        var done = start
                        while (ins.read(buf).also { n = it } > 0) {
                            fos.write(buf, 0, n); done += n
                            if (total > 0) onProgress((done * 100 / total).toInt())
                        }
                    }
                }
                check(out.delete()) { "replace old" }
                if (!tmp.renameTo(out)) tmp.copyTo(out, overwrite = true)
                Result.success(out)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** 推理入口: 优先本地 JNI, 未内置则返回 null (UI 层引导外部引擎) */
    fun localInferAvailable(dir: File, m: ModelInfo): Boolean =
        isDownloaded(dir, m) && try {
            Class.forName("com.skapp.app.llm.LlamaBridge"); true
        } catch (e: ClassNotFoundException) { false }

    fun chat(dir: File, m: ModelInfo, systemPrompt: String, userMsg: String): String? = try {
        val bridge = Class.forName("com.skapp.app.llm.LlamaBridge")
        val inst = bridge.getDeclaredConstructor().newInstance()
        val load = bridge.getMethod("load", String::class.java)
        val gen = bridge.getMethod("chat", String::class.java, String::class.java)
        load.invoke(inst, modelFile(dir, m).absolutePath)
        gen.invoke(inst, systemPrompt, userMsg) as? String
    } catch (e: Exception) { null }
}
