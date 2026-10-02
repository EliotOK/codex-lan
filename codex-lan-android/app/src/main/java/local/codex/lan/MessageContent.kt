package local.codex.lan

data class UserContent(val text: String, val context: String? = null)
object MessageContent {
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
