package com.zcloud.ai.ai

import android.graphics.Bitmap
import android.util.Log
import com.zcloud.ai.core.CubicSplineLut
import com.zcloud.ai.core.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AI 视觉逆向调色引擎。
 *
 * 设计要点（为什么这样省 token 且效果好）：
 * 1. 图片先本地降采样到长边 384 / JPEG q62，约 25~40KB，视觉 token 压到最低仍够模型判断影调；
 * 2. 同时把本地直方图统计（黑位/高光/中灰/饱和度/色偏/16桶直方图）压成一行文本随请求发出，
 *    模型不需要靠"猜"去估数值，输出更准，也允许它在只看数字时（DeepSeek 纯文本）依然能工作；
 * 3. System Prompt 把所有硬约束（EXPEED 6 能力边界、曲线的单调性、点数量 2~20）写死，
 *    并要求纯 JSON 输出，避免长篇解释导致 completion token 爆炸；
 * 4. max_tokens 限到 800，够 5~8 个点 + 一句分析。
 */
object AiColorGradingRepository {

    private const val TAG = "AiGrading"
    private const val MAX_TOKENS = 800
    private const val TEMPERATURE = 0.65

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    // ---------------- Prompt 构造 ----------------

    /**
     * System Prompt：把 Z30 / EXPEED 6 的真实硬件能力边界写死，
     * 防止模型输出 HSL 分色、局部蒙版这类相机根本存不下的参数。
     */
    val SYSTEM_PROMPT: String = """
你是尼康 Z30（EXPEED 6）优化校准（Picture Control）逆向工程师。
任务：看一张参考图，输出一套能刷进相机 C-1~C-9 自定义优化校准槽位的参数。

【硬性能力边界，不可越界】
- 相机只有一条主亮度曲线（Tone Curve），18 个等距采样点，输入输出均为 0-255。
- 不支持 HSL 分色、不支持局部蒙版、不支持分离色调、不支持颗粒、不支持渐晕。
- 可调基础参数只有：sharpening（锐化 -3.0~9.0）、clarity（清晰度 -5.0~5.0）、
  contrast（对比度 -3.0~3.0）、brightness（亮度 -2.0~2.0）、
  saturation（饱和度 -3.0~3.0）、hue（色相 -3.0~3.0）。
- base_profile 只能是 Standard / Neutral / Flat / Monochrome 四者之一。
- preset_name 最多 19 个 ASCII 字符，不能含空格以外的特殊符号。

【输出格式】只输出一个 JSON 对象，不要 markdown 代码块，不要任何解释文字。
字段严格如下：
{
  "preset_name": "AI_Cinematic_01",
  "base_profile": "Standard",
  "sharpening": 2.0,
  "clarity": 1.0,
  "contrast": 0.0,
  "brightness": 0.0,
  "saturation": -1.0,
  "hue": 0.0,
  "use_custom_curve": true,
  "tone_curve_points": [
    {"x": 0, "y": 18}, {"x": 64, "y": 52}, {"x": 128, "y": 130},
    {"x": 192, "y": 205}, {"x": 240, "y": 242}
  ],
  "style_analysis": "一句话中文说明影调意图，40 字以内"
}

【曲线规则，违反即无效】
- tone_curve_points 至少 2 个、至多 8 个点，x 严格递增且都在 0-255。
- y 必须单调不减（允许相等），禁止出现 S 形过冲或回勾，否则相机写入会失败。
- 必须包含 x=0 和 x=255 两个端点（x=255 的 y 就是白点位置）。
- 想让暗部抬起（胶片灰雾）就把 x=0 的 y 设为 10~30；想让高光柔化就把 x=255 的 y 设为 225~248。
- 中灰点 x=128 的 y 决定整体曝光感：>140 提亮，<115 压暗。
""".trimIndent()

    /** 组装 user 消息。有图就发图，没图只发特征数字。 */
    fun buildUserPrompt(features: ImageFeatures, hasImage: Boolean, userHint: String): String = buildString {
        append("参考图的客观影调统计（已由本地算法计算，可直接采信）：\n")
        append(features.toPromptLine())
        append("\n\n")
        if (!hasImage) {
            append("注意：当前模型无视觉能力，请仅依据以上统计数字反推曲线。\n")
            append("数值含义：black/high 是实际黑场与白场位置，spread 是对比宽度(0-1)，")
            append("sat 是平均饱和度，warm>0 偏暖、green>0 偏绿，hist 是 16 桶亮度分布。\n")
        }
        if (userHint.isNotBlank()) {
            append("\n用户额外要求：").append(userHint.trim()).append("\n")
        }
        append("\n请输出 JSON。")
    }.toString()

    // ---------------- 网络调用 ----------------

    /**
     * 调用 OpenAI 兼容 /chat/completions。
     *
     * @param base64Jpeg 已压缩的参考图（无视觉能力的模型请传 null）
     */
    suspend fun analyze(
        provider: LlmProvider,
        apiKey: String,
        model: String,
        base64Jpeg: String?,
        features: ImageFeatures,
        userHint: String = "",
    ): AnalyzeResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        if (apiKey.isBlank()) {
            return@withContext AnalyzeResult(
                success = false,
                error = "未配置 ${provider.displayName} 的 API Key",
                providerUsed = provider.id,
            )
        }

        val image = if (provider.supportsVision) base64Jpeg else null
        val userText = buildUserPrompt(features, image != null, userHint)

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        if (image != null) {
            val content = JSONArray()
                .put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", userText)
                )
                .put(
                    JSONObject()
                        .put("type", "image_url")
                        .put(
                            "image_url",
                            JSONObject()
                                .put("url", "data:image/jpeg;base64,$image")
                                .put("detail", "low")   // low detail = 更少 token，够判断影调
                        )
                )
            messages.put(JSONObject().put("role", "user").put("content", content))
        } else {
            messages.put(JSONObject().put("role", "user").put("content", userText))
        }

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", TEMPERATURE)
            .put("max_tokens", MAX_TOKENS)
            .put("stream", false)
        if (provider.supportsJsonMode && image == null) {
            // 带图时部分供应商会拒绝 response_format，只在纯文本路径启用
            body.put("response_format", JSONObject().put("type", "json_object"))
        }

        val url = provider.baseUrl.trimEnd('/') + "/chat/completions"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = extractApiError(text).ifBlank { "HTTP ${resp.code}" }
                    return@withContext AnalyzeResult(
                        success = false,
                        error = msg,
                        providerUsed = provider.id,
                        elapsedMs = System.currentTimeMillis() - started,
                    )
                }
                val root = JSONObject(text)
                val usage = root.optJSONObject("usage")
                val pt = usage?.optLong("prompt_tokens", 0) ?: 0
                val ct = usage?.optLong("completion_tokens", 0) ?: 0

                val choices = root.optJSONArray("choices")
                val content = choices?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()

                if (content.isBlank()) {
                    return@withContext AnalyzeResult(
                        success = false,
                        error = "模型返回空内容（响应长度 ${text.length}）",
                        promptTokens = pt,
                        completionTokens = ct,
                        providerUsed = provider.id,
                        elapsedMs = System.currentTimeMillis() - started,
                    )
                }

                val json = extractJsonObject(content)
                if (json == null) {
                    return@withContext AnalyzeResult(
                        success = false,
                        error = "无法从模型输出中提取 JSON：${content.take(160)}",
                        promptTokens = pt,
                        completionTokens = ct,
                        providerUsed = provider.id,
                        elapsedMs = System.currentTimeMillis() - started,
                    )
                }

                AnalyzeResult(
                    success = true,
                    recipeJson = json,
                    promptTokens = pt,
                    completionTokens = ct,
                    providerUsed = provider.id,
                    elapsedMs = System.currentTimeMillis() - started,
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "analyze failed", t)
            AnalyzeResult(
                success = false,
                error = "${t.javaClass.simpleName}: ${t.message ?: "网络异常"}",
                providerUsed = provider.id,
                elapsedMs = System.currentTimeMillis() - started,
            )
        }
    }

    // ---------------- JSON 解析 ----------------

    /** 从可能带 ```json 围栏或前后废话的输出里抠出第一个完整 JSON 对象。 */
    fun extractJsonObject(raw: String): String? {
        val s = raw.trim()
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            when {
                esc -> esc = false
                c == '\\' && inStr -> esc = true
                c == '"' -> inStr = !inStr
                !inStr && c == '{' -> depth++
                !inStr && c == '}' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * 把模型返回的 JSON 变成内部 Recipe。
     * 做了三层防御：值域钳制、曲线单调化、端点强制补齐。
     */
    fun jsonToRecipe(json: String): Result<Recipe> = runCatching {
        val o = JSONObject(json)
        val name = o.optString("preset_name", "AI_RECIPE").trim().take(19).ifBlank { "AI_RECIPE" }
        val profile = normalizeProfile(o.optString("base_profile", "Standard"))

        val sharpen = o.optDouble("sharpening", 1.0).toFloat().coerceIn(-3f, 9f)
        val clarity = o.optDouble("clarity", 0.0).toFloat().coerceIn(-5f, 5f)
        val contrast = o.optDouble("contrast", 0.0).toFloat().coerceIn(-3f, 3f)
        val brightness = o.optDouble("brightness", 0.0).toFloat().coerceIn(-2f, 2f)
        val saturation = o.optDouble("saturation", 0.0).toFloat().coerceIn(-3f, 3f)
        val hue = o.optDouble("hue", 0.0).toFloat().coerceIn(-3f, 3f)
        val useCurve = o.optBoolean("use_custom_curve", true)

        val pts = mutableListOf<CubicSplineLut.Point>()
        val arr = o.optJSONArray("tone_curve_points")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val x = p.optDouble("x", Double.NaN)
                val y = p.optDouble("y", Double.NaN)
                if (x.isNaN() || y.isNaN()) continue
                pts.add(
                    CubicSplineLut.Point(
                        x.toFloat().coerceIn(0f, 255f),
                        y.toFloat().coerceIn(0f, 255f)
                    )
                )
            }
        }

        val curve = sanitizeCurve(pts)
        val lut = if (useCurve) CubicSplineLut.buildLut(curve) else CubicSplineLut.identityLut()

        Recipe(
            name = name,
            baseProfile = profile.first,
            pcdType = profile.second,
            sharpening = sharpen,
            clarity = clarity,
            contrast = contrast,
            brightness = brightness,
            saturation = saturation,
            hue = hue,
            useCustomCurve = useCurve,
            curvePoints = curve,
            styleAnalysis = o.optString("style_analysis", "").take(120),
            lut = lut,
        )
    }

    /** 曲线清洗：排序去重、强制首尾端点、单调化。 */
    fun sanitizeCurve(input: List<CubicSplineLut.Point>): List<CubicSplineLut.Point> {
        var pts = input
            .filter { it.x.isFinite() && it.y.isFinite() }
            .map {
                CubicSplineLut.Point(
                    it.x.coerceIn(0f, 255f),
                    it.y.coerceIn(0f, 255f)
                )
            }
            .sortedBy { it.x }

        // 去重：同 x 只保留最后一个
        val dedup = mutableListOf<CubicSplineLut.Point>()
        for (p in pts) {
            if (dedup.isNotEmpty() && kotlin.math.abs(dedup.last().x - p.x) < 0.5f) {
                dedup[dedup.size - 1] = p
            } else {
                dedup.add(p)
            }
        }
        pts = dedup

        // 端点强制
        if (pts.isEmpty()) return com.zcloud.ai.core.Recipe.DEFAULT_CURVE
        if (pts.first().x > 0.5f) pts = listOf(CubicSplineLut.Point(0f, pts.first().y)) + pts
        if (pts.last().x < 254.5f) pts = pts + CubicSplineLut.Point(255f, pts.last().y)

        // 单调化：y 不得下降
        val mono = mutableListOf<CubicSplineLut.Point>()
        var lastY = 0f
        for (p in pts) {
            val y = maxOf(p.y, lastY)
            mono.add(CubicSplineLut.Point(p.x, y))
            lastY = y
        }

        // 点数上限 20（UI 与插值都能接受）
        return if (mono.size <= 20) mono else mono.filterIndexed { i, _ ->
            i == 0 || i == mono.size - 1 || i % (mono.size / 18) == 0
        }.let { if (it.size >= 2) it else mono }
    }

    private fun normalizeProfile(raw: String): Pair<String, Int> {
        val n = raw.trim().uppercase()
        return when {
            n.startsWith("MONO") -> "MONOCHROME" to 1
            n.startsWith("FLAT") -> "FLAT" to 0
            n.startsWith("NEUTRAL") -> "NEUTRAL" to 0
            n.startsWith("VIVID") -> "VIVID" to 0
            n.startsWith("PORTRAIT") -> "PORTRAIT" to 0
            n.startsWith("LANDSCAPE") -> "LANDSCAPE" to 0
            else -> "STANDARD" to 0
        }
    }

    private fun extractApiError(text: String): String = runCatching {
        val o = JSONObject(text)
        val err = o.optJSONObject("error")
        err?.optString("message").orEmpty().ifBlank { o.optString("message", "") }
    }.getOrElse { text.take(180) }

    // ---------------- 一站式：Bitmap → Recipe ----------------

    /**
     * UI 直接调这个。内部完成：特征提取 → 压缩图 → 请求 → 解析 → Recipe。
     * 返回 Pair(Recipe?, AnalyzeResult)，即使失败也带回 token 用量。
     */
    suspend fun analyzeBitmap(
        bitmap: Bitmap,
        account: ProviderAccount,
        userHint: String = "",
    ): Pair<Recipe?, AnalyzeResult> = withContext(Dispatchers.Default) {
        val features = ImageFeatureExtractor.extract(bitmap)
        val b64 = if (account.provider.supportsVision) {
            ImageFeatureExtractor.toBase64Jpeg(bitmap)
        } else null

        val result = analyze(
            provider = account.provider,
            apiKey = account.apiKey,
            model = account.model,
            base64Jpeg = b64,
            features = features,
            userHint = userHint,
        )
        val recipe = if (result.success) jsonToRecipe(result.recipeJson).getOrNull() else null
        recipe to result
    }
}
