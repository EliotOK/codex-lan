package local.codex.lan

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProjectGroupsTest {
    private fun thread(id: String, title: String, projectId: String? = null, project: String = "", cwd: String = "", path: String = "") =
        ThreadInfo(id, title, project, false, path, projectId, cwd)
    @Test fun unassignedChatsRemainTogetherDespiteDifferentWorkingDirectories() {
        val groups = ProjectGroups.from(listOf(thread("a", "会话 A", cwd = "C:/Codex/c-j"),
            thread("b", "会话 B", cwd = "C:/Codex/ce"), thread("c", "会话 C", project = "旧目录名称", cwd = "D:/work/project")))
        assertEquals(1, groups.size)
        assertEquals("未分组", groups.single().name)
        assertEquals("", groups.single().path)
        assertEquals(listOf("a", "b", "c"), groups.single().threads.map { it.id })
    }
    @Test fun projectIdentityGroupsSubdirectoriesAndDistinguishesSharedPaths() {
        val groups = ProjectGroups.from(listOf(thread("a", "最新", "p1", "真实项目", "C:/work/a", "C:/work"),
            thread("b", "较早", "p1", "真实项目", "D:/worktree", "C:/work"),
            thread("c", "另一项目", "p2", "另一个名称", "C:/work", "C:/work")))
        assertEquals(2, groups.size)
        assertEquals(listOf("a", "b"), groups.first { it.key == "project:p1" }.threads.map { it.id })
        val renamed = ProjectGroups.from(listOf(thread("a", "会话", "p1", "改名后的项目"))).single()
        assertEquals("project:p1", renamed.key)
        assertEquals("改名后的项目", renamed.name)
    }
    @Test fun readsSavedProjectLabelsAndPreservesExplicitNullMembership() {
        val payload = JSONObject().put("projects", JSONArray().put(JSONObject().put("projectId", "p1").put("label", "自定义项目名").put("path", "D:\\root")))
            .put("threads", JSONArray().put(JSONObject().put("id", "a").put("projectId", "p1").put("cwd", "D:/root/subfolder"))
                .put(JSONObject().put("id", "b").put("projectId", JSONObject.NULL).put("cwd", "D:/root"))
                .put(JSONObject().put("id", "c").put("cwd", "C:/Codex/ce")))
        val threads = ProjectGroups.parseThreads(payload)
        assertEquals("自定义项目名", threads[0].project)
        assertEquals("D:/root", threads[0].projectPath)
        assertNull(threads[1].projectId)
        assertNull(threads[2].projectId)
        val groups = ProjectGroups.from(threads)
        assertEquals(2, groups.size)
        assertEquals(2, groups.last().threads.size)
    }
    @Test fun searchesProjectNamesPathsTitlesAndUnassignedDirectories() {
        val threads = listOf(thread("a", "修复语音", "p1", "项目甲", "C:/root/subfolder", "C:/root"),
            thread("b", "发布", "p1", "项目甲", "D:/worktree", "C:/root"), thread("c", "测试", cwd = "C:/Codex/ce"))
        assertEquals(2, ProjectGroups.from(threads, "项目甲").single().threads.size)
        assertEquals(2, ProjectGroups.from(threads, "C:/root").single().threads.size)
        assertEquals(2, ProjectGroups.from(threads, "C:\\root").single().threads.size)
        assertEquals("a", ProjectGroups.from(threads, " 语音 ").single().threads.single().id)
        assertEquals("c", ProjectGroups.from(threads, "Codex/ce").single().threads.single().id)
        assertEquals("未分组", ProjectGroups.from(threads, "未分组").single().name)
        assertTrue(ProjectGroups.from(threads, "无匹配").isEmpty())
    }
    @Test fun olderServerPayloadStillUsesProjectIdentityAndNeverGuessesFromDirectory() {
        val payload = JSONObject().put("threads", JSONArray().put(JSONObject().put("id", "a").put("projectId", "p1").put("cwd", "D:/root"))
            .put(JSONObject().put("id", "b").put("cwd", "D:/root")))
        val groups = ProjectGroups.from(ProjectGroups.parseThreads(payload))
        assertEquals(2, groups.size)
        assertEquals("项目（名称暂不可用）", groups.first().name)
        assertEquals("未分组", groups.last().name)
        assertEquals("C:/", ProjectGroups.path("C:\\"))
        assertEquals("/", ProjectGroups.path("/"))
        assertEquals("", ProjectGroups.path("null"))
    }
}
