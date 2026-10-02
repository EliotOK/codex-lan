package local.codex.lan

data class UserContent(val text: String, val context: String? = null)
object MessageContent {
    fun forwarded(item: org.json.JSONObject): String? {
        if (item.optString("name") != "send_message_to_thread" || item.optString("namespace") != "codex_app") return null
        val output = item.optJSONObject("output") ?: return null
        val raw = output.optString("text").trim()
        if (output.optBoolean("truncated") || !raw.startsWith("<codex_delegation>") || !raw.endsWith("</codex_delegation>")) return null
        return Regex("<input>(.*)</input>", RegexOption.DOT_MATCHES_ALL).find(raw)?.groupValues?.get(1)?.trim()
    }
    fun user(raw: String): UserContent {
        val trimmed = raw.trimStart()
        if (trimmed.startsWith("<codex_delegation>") && trimmed.trimEnd().endsWith("</codex_delegation>")) {
            val input = Regex("<input>(.*)</input>", RegexOption.DOT_MATCHES_ALL).find(trimmed)
            if (input != null) return UserContent(input.groupValues[1].trim(), raw)
        }
        val marker = Regex("(?m)^## My request:\\s*\\n").find(raw)
        val envelope = trimmed.startsWith("<in-app-browser-context") || trimmed.startsWith("# Files mentioned by the user:") || trimmed.startsWith("<external_codex_apps_open_page>")
        if (envelope && marker != null) return UserContent(raw.substring(marker.range.last + 1).trim(), raw.substring(0, marker.range.first).trim())
        if (trimmed.startsWith("# AGENTS.md instructions") && trimmed.contains("<INSTRUCTIONS>")) return UserContent("会话配置", raw)
        return UserContent(raw)
    }
}
