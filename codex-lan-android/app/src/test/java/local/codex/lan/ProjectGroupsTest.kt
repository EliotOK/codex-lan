package local.codex.lan

import org.junit.Assert.*
import org.junit.Test

class ProjectGroupsTest {
    private fun thread(id: String, title: String, path: String = "") = ThreadInfo(id, title, ProjectGroups.name(path), false, path)
    @Test fun groupsWindowsPathsAndPreservesOrderWithinEachProject() {
        val a = thread("a", "新会话", "C:\\work\\Alpha\\")
        val b = thread("b", "旧会话", "c:/work/alpha")
        val beta = thread("c", "另一个项目", "C:/work/Beta")
        val groups = ProjectGroups.from(listOf(beta, a, b, thread("none", "未归属会话")))
        assertEquals(listOf("Alpha", "Beta", "其他会话"), groups.map { it.name })
        assertEquals(listOf("a", "b"), groups[0].threads.map { it.id })
        assertEquals("C:/work/Alpha", groups[0].path)
    }
    @Test fun distinguishesSameNamedDirectoriesAndCaseSensitivePaths() {
        val groups = ProjectGroups.from(listOf(thread("a", "会话", "C:/one/app"), thread("b", "会话", "D:/two/app"),
            thread("c", "会话", "/work/APP"), thread("d", "会话", "/work/app")))
        assertEquals(4, groups.size)
        assertEquals(4, groups.map { it.key }.distinct().size)
    }
    @Test fun searchesTitlesProjectsAndPathsAndHandlesNoMatches() {
        val threads = listOf(thread("a", "修复语音", "C:/one/Alpha"), thread("b", "发布版本", "C:/one/Alpha"), thread("c", "测试", "C:/two/Beta"))
        assertEquals(listOf("a"), ProjectGroups.from(threads, "  语音  ").single().threads.map { it.id })
        assertEquals(2, ProjectGroups.from(threads, "alpha").single().threads.size)
        assertEquals("c", ProjectGroups.from(threads, "two").single().threads.single().id)
        assertTrue(ProjectGroups.from(threads, "不存在").isEmpty())
    }
    @Test fun handlesDriveRootsUncPathsAndMissingProjects() {
        assertEquals("C:/", ProjectGroups.path("C:\\"))
        assertEquals("/", ProjectGroups.path("/"))
        assertEquals("", ProjectGroups.path("null"))
        val groups = ProjectGroups.from(listOf(thread("a", "会话", "\\\\SERVER\\Share\\Project"), thread("b", "会话", "//server/share/project/")))
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().threads.size)
        assertEquals("其他会话", ProjectGroups.from(listOf(thread("c", "独立会话"))).single().name)
    }
}
