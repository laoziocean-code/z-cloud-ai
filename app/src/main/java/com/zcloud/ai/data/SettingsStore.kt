package com.zcloud.ai.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zcloud.ai.ai.LlmProvider
import com.zcloud.ai.ai.ProviderAccount
import com.zcloud.ai.core.CubicSplineLut
import com.zcloud.ai.core.Recipe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zcloud_ai")

/** 已保存的预设（AI 生成或 NCP 导入）。 */
data class SavedPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "PRESET",
    val source: String = "AI",          // "AI" / "NCP" / "MANUAL"
    val createdAt: Long = System.currentTimeMillis(),
    val lastSlot: Int = 1,              // 上次刷入的槽位（1..9），0 = 未刷过
    val recipe: Recipe = Recipe(),
)

/** Recipe 与 JSON 的互转（DataStore 只存字符串，所以自己编解码）。 */
object RecipeCodec {

    fun toJson(r: Recipe): JSONObject = JSONObject().apply {
        put("name", r.name)
        put("baseProfile", r.baseProfile)
        put("pcdType", r.pcdType)
        put("sharpening", r.sharpening.toDouble())
        put("clarity", r.clarity.toDouble())
        put("contrast", r.contrast.toDouble())
        put("brightness", r.brightness.toDouble())
        put("saturation", r.saturation.toDouble())
        put("hue", r.hue.toDouble())
        put("useCustomCurve", r.useCustomCurve)
        put("styleAnalysis", r.styleAnalysis)
        val arr = JSONArray()
        r.curvePoints.forEach {
            arr.put(JSONObject().put("x", it.x.toDouble()).put("y", it.y.toDouble()))
        }
        put("curvePoints", arr)
    }

    fun fromJson(o: JSONObject): Recipe {
        val pts = mutableListOf<CubicSplineLut.Point>()
        o.optJSONArray("curvePoints")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                pts.add(CubicSplineLut.Point(p.optDouble("x", 0.0).toFloat(), p.optDouble("y", 0.0).toFloat()))
            }
        }
        val curve = if (pts.size >= 2) pts else Recipe.DEFAULT_CURVE
        return Recipe(
            name = o.optString("name", "PRESET"),
            baseProfile = o.optString("baseProfile", "STANDARD"),
            pcdType = o.optInt("pcdType", 0),
            sharpening = o.optDouble("sharpening", 0.0).toFloat(),
            clarity = o.optDouble("clarity", 0.0).toFloat(),
            contrast = o.optDouble("contrast", 0.0).toFloat(),
            brightness = o.optDouble("brightness", 0.0).toFloat(),
            saturation = o.optDouble("saturation", 0.0).toFloat(),
            hue = o.optDouble("hue", 0.0).toFloat(),
            useCustomCurve = o.optBoolean("useCustomCurve", true),
            curvePoints = curve,
            styleAnalysis = o.optString("styleAnalysis", ""),
            lut = CubicSplineLut.buildLut(curve),
        )
    }

    fun presetToJson(p: SavedPreset): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("source", p.source)
        put("createdAt", p.createdAt)
        put("lastSlot", p.lastSlot)
        put("recipe", toJson(p.recipe))
    }

    fun presetFromJson(o: JSONObject): SavedPreset = SavedPreset(
        id = o.optString("id", UUID.randomUUID().toString()),
        name = o.optString("name", "PRESET"),
        source = o.optString("source", "AI"),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        lastSlot = o.optInt("lastSlot", 0),
        recipe = fromJson(o.optJSONObject("recipe") ?: JSONObject()),
    )

    fun accountToJson(a: ProviderAccount): JSONObject = JSONObject().apply {
        put("providerId", a.providerId)
        put("apiKey", a.apiKey)
        put("modelOverride", a.modelOverride)
        put("enabled", a.enabled)
        put("promptTokens", a.promptTokens)
        put("completionTokens", a.completionTokens)
        put("requestCount", a.requestCount)
        put("lastUsedAt", a.lastUsedAt)
        put("lastError", a.lastError)
        put("balanceNote", a.balanceNote)
    }

    fun accountFromJson(o: JSONObject): ProviderAccount = ProviderAccount(
        providerId = o.optString("providerId"),
        apiKey = o.optString("apiKey", ""),
        modelOverride = o.optString("modelOverride", ""),
        enabled = o.optBoolean("enabled", true),
        promptTokens = o.optLong("promptTokens", 0),
        completionTokens = o.optLong("completionTokens", 0),
        requestCount = o.optLong("requestCount", 0),
        lastUsedAt = o.optLong("lastUsedAt", 0),
        lastError = o.optString("lastError", ""),
        balanceNote = o.optString("balanceNote", ""),
    )
}

/**
 * 本地持久化：API Key、用量统计、预设库、上次选中的供应商。
 * 全部存在 App 私有目录的 DataStore 里，不出本机。
 */
class SettingsStore(private val context: Context) {

    private val keyAccounts = stringPreferencesKey("accounts_json")
    private val keyPresets = stringPreferencesKey("presets_json")
    private val keyActiveProvider = stringPreferencesKey("active_provider")
    private val keySettings = stringPreferencesKey("app_settings")

    /** 所有供应商账户（含 key 与用量）。默认填入三个内置供应商的空账户。 */
    val accounts: Flow<List<ProviderAccount>> = context.dataStore.data.map { prefs ->
        val raw = prefs[keyAccounts]
        val stored = if (raw.isNullOrBlank()) emptyList() else parseAccounts(raw)
        // 补齐新增的内置供应商
        val merged = LlmProvider.ALL.map { p ->
            stored.firstOrNull { it.providerId == p.id } ?: ProviderAccount(providerId = p.id)
        }
        merged
    }

    val activeProviderId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[keyActiveProvider] ?: LlmProvider.GLM_FLASH.id
    }

    val presets: Flow<List<SavedPreset>> = context.dataStore.data.map { prefs ->
        val raw = prefs[keyPresets]
        if (raw.isNullOrBlank()) emptyList() else parsePresets(raw)
    }

    /** 常规设置。读不到就用 [AppSettings] 的默认值，不写库。 */
    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val raw = prefs[keySettings]
        if (raw.isNullOrBlank()) AppSettings() else parseSettings(raw)
    }

    /**
     * 改一项设置。
     *
     * 只改传进来的那一个字段：`AppSettings` 是 data class，`copy` 天然只动一处，
     * 不会因为并发改两个开关而互相覆盖。
     */
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val cur = settings.first()
        val next = transform(cur)
        context.dataStore.edit {
            it[keySettings] = JSONObject().apply {
                put("galleryLimit", next.galleryLimit)
                put("liveViewIntervalMs", next.liveViewIntervalMs)
                put("backupBeforeWrite", next.backupBeforeWrite)
                put("verifyAfterWrite", next.verifyAfterWrite)
                put("autoSaveCapture", next.autoSaveCapture)
                put("logToFile", next.logToFile)
                put("hexInLog", next.hexInLog)
                put("defaultTargetSlot", next.defaultTargetSlot)
                put("keepScreenOn", next.keepScreenOn)
            }.toString()
        }
    }

    private fun parseSettings(raw: String): AppSettings = runCatching {
        val o = JSONObject(raw)
        val d = AppSettings()
        AppSettings(
            galleryLimit = o.optInt("galleryLimit", d.galleryLimit),
            liveViewIntervalMs = o.optInt("liveViewIntervalMs", d.liveViewIntervalMs),
            backupBeforeWrite = o.optBoolean("backupBeforeWrite", d.backupBeforeWrite),
            verifyAfterWrite = o.optBoolean("verifyAfterWrite", d.verifyAfterWrite),
            autoSaveCapture = o.optBoolean("autoSaveCapture", d.autoSaveCapture),
            logToFile = o.optBoolean("logToFile", d.logToFile),
            hexInLog = o.optBoolean("hexInLog", d.hexInLog),
            defaultTargetSlot = o.optInt("defaultTargetSlot", d.defaultTargetSlot),
            keepScreenOn = o.optBoolean("keepScreenOn", d.keepScreenOn),
        )
    }.getOrElse { AppSettings() }

    suspend fun setApiKey(providerId: String, apiKey: String) {
        updateAccounts { list ->
            list.map { if (it.providerId == providerId) it.copy(apiKey = apiKey.trim(), lastError = "") else it }
        }
    }

    suspend fun setModelOverride(providerId: String, model: String) {
        updateAccounts { list ->
            list.map { if (it.providerId == providerId) it.copy(modelOverride = model.trim()) else it }
        }
    }

    suspend fun setBalanceNote(providerId: String, note: String) {
        updateAccounts { list ->
            list.map { if (it.providerId == providerId) it.copy(balanceNote = note) else it }
        }
    }

    /** 记录一次调用的 token 用量。 */
    suspend fun recordUsage(providerId: String, promptTokens: Long, completionTokens: Long, error: String = "") {
        updateAccounts { list ->
            list.map {
                if (it.providerId == providerId) it.copy(
                    promptTokens = it.promptTokens + promptTokens,
                    completionTokens = it.completionTokens + completionTokens,
                    requestCount = it.requestCount + 1,
                    lastUsedAt = System.currentTimeMillis(),
                    lastError = error,
                ) else it
            }
        }
    }

    suspend fun resetUsage(providerId: String) {
        updateAccounts { list ->
            list.map {
                if (it.providerId == providerId) it.copy(promptTokens = 0, completionTokens = 0, requestCount = 0)
                else it
            }
        }
    }

    suspend fun setActiveProvider(providerId: String) {
        context.dataStore.edit { it[keyActiveProvider] = providerId }
    }

    suspend fun savePreset(preset: SavedPreset) {
        val current = presets.first()
        val next = current.toMutableList()
        val idx = next.indexOfFirst { it.id == preset.id }
        if (idx >= 0) next[idx] = preset else next.add(0, preset)
        writePresets(next)
    }

    suspend fun deletePreset(id: String) {
        val next = presets.first().filterNot { it.id == id }
        writePresets(next)
    }

    suspend fun markPresetSlot(id: String, slot: Int) {
        val current = presets.first()
        writePresets(current.map { if (it.id == id) it.copy(lastSlot = slot) else it })
    }

    private suspend fun writePresets(list: List<SavedPreset>) {
        val arr = JSONArray()
        list.forEach { arr.put(RecipeCodec.presetToJson(it)) }
        context.dataStore.edit { it[keyPresets] = arr.toString() }
    }

    private suspend fun updateAccounts(transform: (List<ProviderAccount>) -> List<ProviderAccount>) {
        val current = accounts.first()
        val next = transform(current)
        val arr = JSONArray()
        next.forEach { arr.put(RecipeCodec.accountToJson(it)) }
        context.dataStore.edit { it[keyAccounts] = arr.toString() }
    }

    private fun parseAccounts(raw: String): List<ProviderAccount> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { RecipeCodec.accountFromJson(it) }
        }
    }.getOrElse { emptyList() }

    private fun parsePresets(raw: String): List<SavedPreset> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { RecipeCodec.presetFromJson(it) }
        }
    }.getOrElse { emptyList() }
}
