package com.lizongying.mytv.models

import org.json.JSONObject

data class M3UConfig(
    val id: String,
    var name: String,
    var url: String,
    var enabled: Boolean = false,
    val isBuiltin: Boolean = false
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("name", name)
        json.put("url", url)
        json.put("enabled", enabled)
        json.put("isBuiltin", isBuiltin)
        return json
    }

    companion object {
        const val BUILTIN_ID = "builtin_source"

        fun fromJson(json: JSONObject): M3UConfig {
            return M3UConfig(
                id = json.optString("id", System.currentTimeMillis().toString()),
                name = json.optString("name", "未命名"),
                url = json.optString("url", ""),
                enabled = json.optBoolean("enabled", false),
                isBuiltin = json.optBoolean("isBuiltin", false)
            )
        }
    }
}

