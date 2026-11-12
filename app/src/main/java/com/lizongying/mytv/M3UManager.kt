package com.lizongying.mytv

import android.util.Log
import com.lizongying.mytv.models.M3UConfig
import com.lizongying.mytv.models.ProgramType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.BufferedReader
import java.io.StringReader
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object M3UManager {
    private const val TAG = "M3UManager"
    const val DEFAULT_M3U_URL = "https://gh-proxy.org/https://github.com/zysaii/render-proxy/blob/main/m3u/A_migu.m3u"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // 内存中缓存已解析成功的 m3u 频道：Map<GroupTitle, List<TV>>
    private var cachedM3UGroups: Map<String, List<TV>> = emptyMap()

    fun getCachedM3UGroups(): Map<String, List<TV>> {
        return cachedM3UGroups
    }

    fun getM3UList(): MutableList<M3UConfig> {
        val jsonStr = SP.m3uListJson
        val list = mutableListOf<M3UConfig>()
        if (jsonStr.isNotBlank()) {
            try {
                val jsonArray = JSONArray(jsonStr)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    list.add(M3UConfig.fromJson(obj))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing m3uListJson", e)
            }
        }

        // 确保内置源存在于列表首位
        val hasBuiltin = list.any { it.id == M3UConfig.BUILTIN_ID }
        if (!hasBuiltin) {
            val builtinConfig = M3UConfig(
                id = M3UConfig.BUILTIN_ID,
                name = "内置官方源 (央视/卫视/地方)",
                url = "系统内置央视、卫视、地方官方直播源",
                enabled = true,
                isBuiltin = true
            )
            list.add(0, builtinConfig)
        }

        // 如果用户从未配置过外部源，初始化默认的咪咕 M3U
        val hasMigu = list.any { it.id == "default_migu" }
        if (!hasMigu && list.size == 1) {
            val defaultConfig = M3UConfig(
                id = "default_migu",
                name = "咪咕源",
                url = DEFAULT_M3U_URL,
                enabled = true,
                isBuiltin = false
            )
            list.add(defaultConfig)
        }

        saveM3UList(list)
        return list
    }

    fun saveM3UList(list: List<M3UConfig>) {
        try {
            val jsonArray = JSONArray()
            list.forEach { jsonArray.put(it.toJson()) }
            SP.m3uListJson = jsonArray.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving m3uListJson", e)
        }
    }

    /**
     * 将网页链接转换为可以直接获取原始 m3u 文本的 Raw 链接（如 GitHub 网页转 raw）
     */
    fun normalizeUrl(rawUrl: String): String {
        var url = rawUrl.trim()
        if (url.contains("github.com") && url.contains("/blob/")) {
            url = url.replace("github.com", "raw.githubusercontent.com").replace("/blob/", "/")
        }
        return url
    }

    /**
     * 异步加载并合并所有勾选的 M3U 源内容，并更新 TVList
     */
    suspend fun loadAndApplyEnabledM3U(): Boolean = withContext(Dispatchers.IO) {
        val allConfigs = getM3UList()
        val builtinConfig = allConfigs.firstOrNull { it.id == M3UConfig.BUILTIN_ID }
        val includeBase = builtinConfig?.enabled ?: true

        val externalConfigs = allConfigs.filter { !it.isBuiltin && it.enabled && it.url.isNotBlank() }
        val allParsedGroups = mutableMapOf<String, MutableList<TV>>()

        for (config in externalConfigs) {
            val targetUrl = normalizeUrl(config.url)
            Log.i(TAG, "Loading M3U from ${config.name}: $targetUrl")
            try {
                val request = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: ""
                    if (bodyString.isNotBlank()) {
                        val parsed = parseM3U(bodyString, config.name)
                        for ((group, tvs) in parsed) {
                            val list = allParsedGroups.getOrPut(group) { mutableListOf() }
                            list.addAll(tvs)
                        }
                    }
                } else {
                    Log.w(TAG, "Failed to load ${config.name}, code: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception loading M3U ${config.name}", e)
            }
        }

        cachedM3UGroups = allParsedGroups
        TVList.updateM3UGroups(allParsedGroups, includeBase = includeBase)
        return@withContext true
    }

    /**
     * 解析 M3U 文本，提取 group-title、tvg-logo、频道名称、播放 url
     */
    fun parseM3U(content: String, sourceName: String): Map<String, List<TV>> {
        val groups = linkedMapOf<String, MutableList<TV>>()
        val reader = BufferedReader(StringReader(content))
        var line: String?

        var currentTitle = ""
        var currentLogo = ""
        var currentGroup = ""

        val groupPattern = Pattern.compile("""group-title="([^"]*)"""")
        val logoPattern = Pattern.compile("""tvg-logo="([^"]*)"""")
        val namePattern = Pattern.compile("""tvg-name="([^"]*)"""")

        while (reader.readLine().also { line = it } != null) {
            val trimmed = line!!.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("#EXTINF:")) {
                // 提取 group-title
                val groupMatcher = groupPattern.matcher(trimmed)
                currentGroup = if (groupMatcher.find()) {
                    groupMatcher.group(1)?.trim() ?: ""
                } else {
                    ""
                }
                if (currentGroup.isEmpty()) {
                    currentGroup = "M3U-$sourceName"
                }

                // 提取 tvg-logo
                val logoMatcher = logoPattern.matcher(trimmed)
                currentLogo = if (logoMatcher.find()) {
                    logoMatcher.group(1)?.trim() ?: ""
                } else {
                    ""
                }

                // 提取频道名称（逗号后面的内容或 tvg-name）
                val commaIndex = trimmed.lastIndexOf(',')
                if (commaIndex != -1 && commaIndex < trimmed.length - 1) {
                    currentTitle = trimmed.substring(commaIndex + 1).trim()
                } else {
                    val nameMatcher = namePattern.matcher(trimmed)
                    currentTitle = if (nameMatcher.find()) {
                        nameMatcher.group(1)?.trim() ?: "频道"
                    } else {
                        "频道"
                    }
                }
            } else if (!trimmed.startsWith("#") && (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("rtmp://"))) {
                if (currentTitle.isEmpty()) {
                    currentTitle = "频道"
                }
                if (currentGroup.isEmpty()) {
                    currentGroup = "M3U-$sourceName"
                }

                val tv = TV(
                    id = 0,
                    title = currentTitle,
                    alias = currentTitle,
                    videoUrl = listOf(trimmed),
                    channel = currentGroup,
                    logo = if (currentLogo.isNotEmpty()) currentLogo else R.drawable.logo,
                    pid = "",
                    sid = "",
                    programType = ProgramType.M3U,
                    needToken = false,
                    mustToken = false
                )

                val list = groups.getOrPut(currentGroup) { mutableListOf() }
                list.add(tv)

                // 重置暂存信息
                currentTitle = ""
                currentLogo = ""
                currentGroup = ""
            }
        }

        return groups
    }
}

