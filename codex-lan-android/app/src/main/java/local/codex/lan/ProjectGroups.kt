package local.codex.lan

import java.util.Locale

data class ProjectGroup(val key: String, val name: String, val path: String, val threads: List<ThreadInfo>)

object ProjectGroups {
    fun path(raw: String): String {
        val input = raw.trim().takeUnless { it == "null" }.orEmpty().replace('\\', '/')
        val value = input.trimEnd('/')
        return when {
            input.isEmpty() -> ""
            value.isEmpty() -> "/"
            Regex("^[A-Za-z]:$").matches(value) -> "$value/"
            else -> value
        }
    }
    fun name(raw: String): String {
        val value = path(raw)
        return value.substringAfterLast('/').ifBlank { value }
    }
    private fun key(thread: ThreadInfo): String {
        val value = path(thread.projectPath)
        if (value.isBlank()) return if (thread.project.isBlank()) "unassigned" else "label:${thread.project}"
        val windows = Regex("^[A-Za-z]:/").containsMatchIn(value) || value.startsWith("//")
        return "path:" + if (windows) value.lowercase(Locale.ROOT) else value
    }
    fun from(threads: List<ThreadInfo>, search: String = ""): List<ProjectGroup> {
        val query = search.trim()
        return threads.groupBy(::key).mapNotNull { (key, members) ->
            val first = members.first()
            val path = path(first.projectPath)
            val name = name(path).ifBlank { first.project.ifBlank { "其他会话" } }
            val matching = if (query.isEmpty() || name.contains(query, true) || path.contains(query, true)) members
                else members.filter { it.title.contains(query, true) }
            if (matching.isEmpty()) null else ProjectGroup(key, name, path, matching)
        }.sortedWith(compareBy<ProjectGroup> { it.key == "unassigned" }
            .thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.path })
    }
}
