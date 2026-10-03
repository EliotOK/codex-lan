package local.codex.lan

data class ProjectGroup(val key: String, val name: String, val path: String, val threads: List<ThreadInfo>, val updatedAt: Long = 0)

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
    private fun key(thread: ThreadInfo): String {
        return thread.projectId?.takeIf { it.isNotBlank() }?.let { "project:$it" } ?: "unassigned"
    }
    fun parseThreads(list: org.json.JSONObject): List<ThreadInfo> {
        val projects = list.optJSONArray("projects") ?: org.json.JSONArray()
        val lookup = (0 until projects.length()).map { projects.getJSONObject(it) }
            .associateBy { it.optString("projectId") }
        val threads = list.optJSONArray("threads") ?: org.json.JSONArray()
        return (0 until threads.length()).map { index ->
            val row = threads.getJSONObject(index)
            val id = row.optString("projectId").trim().takeIf { it.isNotBlank() && it != "null" }
            val project = id?.let { lookup[it] }
            ThreadInfo(row.getString("id"), row.optString("title", "未命名会话"),
                if (id == null) "" else project?.optString("label")?.takeIf { it.isNotBlank() } ?: "项目（名称暂不可用）",
                ChatViewModel.isActiveStatus(row.opt("status")), project?.optString("path")?.let(::path).orEmpty(), id, path(row.optString("cwd")), row.optLong("updatedAt").coerceAtLeast(0))
        }.distinctBy { it.id }
    }
    fun from(threads: List<ThreadInfo>, search: String = ""): List<ProjectGroup> {
        val query = search.trim()
        val pathQuery = query.replace('\\', '/')
        return threads.groupBy(::key).mapNotNull { (key, members) ->
            val first = members.first()
            val path = if (key == "unassigned") "" else path(first.projectPath)
            val name = if (key == "unassigned") "未分组" else first.project.ifBlank { "项目（名称暂不可用）" }
            val ordered = members.sortedByDescending { it.updatedAt }
            val matching = if (query.isEmpty() || name.contains(query, true) || path.contains(pathQuery, true)) ordered
                else ordered.filter { it.title.contains(query, true) || ProjectGroups.path(it.cwd).contains(pathQuery, true) }
            if (matching.isEmpty()) null else ProjectGroup(key, name, path, matching, ordered.first().updatedAt)
        }.sortedByDescending { it.updatedAt }
    }
}
