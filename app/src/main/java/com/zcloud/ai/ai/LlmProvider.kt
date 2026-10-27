package com.zcloud.ai.ai

/**
 * LLM 供应商配置。全部走 OpenAI 兼容 /chat/completions 格式，
 * 因此一套 HTTP 客户端可以同时驱动 DeepSeek / 小米 MiMo / 智谱 GLM。
 */
data class LlmProvider(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val supportsVision: Boolean,
    val freeTier: Boolean,
    val note: String,
    /** 估算单价（元 / 1K tokens），仅用于本地花费估算，非官方报价。 */
    val priceInPer1k: Double = 0.0,
    val priceOutPer1k: Double = 0.0,
    /** 是否支持 response_format=json_object */
    val supportsJsonMode: Boolean = true,
) {
    companion object {
        /**
         * 三个内置供应商：
         * - GLM-4V-Flash：完全免费、原生支持视觉 → 默认首选，token 成本 0
         * - DeepSeek：文本推理强、价格极低，但不支持图片输入 → 走"本地特征 → 曲线"降级路径
         * - 小米 MiMo-VL：支持视觉，OpenAI 兼容
         */
        val GLM_FLASH = LlmProvider(
            id = "glm-4v-flash",
            displayName = "智谱 GLM-4V-Flash（免费/视觉）",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            defaultModel = "glm-4v-flash",
            supportsVision = true,
            freeTier = true,
            note = "免费额度，原生视觉，最适合本应用的参考图分析",
        )

        val DEEPSEEK = LlmProvider(
            id = "deepseek",
            displayName = "DeepSeek Chat（极低价/纯文本）",
            baseUrl = "https://api.deepseek.com/v1",
            defaultModel = "deepseek-chat",
            supportsVision = false,
            freeTier = false,
            note = "无视觉能力，将用本地直方图特征做文本推理生成曲线",
            priceInPer1k = 0.001,
            priceOutPer1k = 0.002,
        )

        val MIMO = LlmProvider(
            id = "mimo",
            displayName = "小米 MiMo-VL（视觉）",
            baseUrl = "https://api.xiaomimimo.com/v1",
            defaultModel = "mimo-vl-7b-rl",
            supportsVision = true,
            freeTier = false,
            note = "OpenAI 兼容，支持图像输入",
        )

        val ALL = listOf(GLM_FLASH, DEEPSEEK, MIMO)

        fun byId(id: String): LlmProvider = ALL.firstOrNull { it.id == id } ?: GLM_FLASH
    }
}

/** 每个供应商的本地凭据与用量。 */
data class ProviderAccount(
    val providerId: String,
    val apiKey: String = "",
    val modelOverride: String = "",
    val enabled: Boolean = true,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val requestCount: Long = 0,
    val lastUsedAt: Long = 0,
    val lastError: String = "",
    val balanceNote: String = "",
) {
    val provider: LlmProvider get() = LlmProvider.byId(providerId)
    val model: String get() = modelOverride.ifBlank { provider.defaultModel }
    val totalTokens: Long get() = promptTokens + completionTokens

    /** 本地估算花费（元）。免费供应商恒为 0。 */
    val estimatedCostCny: Double
        get() = if (provider.freeTier) 0.0
        else promptTokens / 1000.0 * provider.priceInPer1k +
            completionTokens / 1000.0 * provider.priceOutPer1k
}

/** 一次 AI 分析的完整结果，含用量统计。 */
data class AnalyzeResult(
    val success: Boolean,
    val recipeJson: String = "",
    val error: String = "",
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val providerUsed: String = "",
    val elapsedMs: Long = 0,
)
