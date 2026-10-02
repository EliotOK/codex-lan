package local.codex.lan

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class LanClientTest {
    private val token = "a".repeat(64)
    private val id = "01a0fc58-7db8-7c11-87c7-c76d3bbb610c"
    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun withServer(block: (MockWebServer, LanClient) -> Unit) {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val server = MockWebServer()
        server.useHttps(tls.sslSocketFactory(), false); server.start()
        try { block(server, LanClient("https://127.0.0.1:${server.port}", cert.certificatePem().toByteArray())) }
        finally { server.shutdown() }
    }
    @Test fun validatesEndpointAndRejectsOtherOrigins() {
        assertEquals("https://192.168.1.220:8787", LanClient.normalizeEndpoint(" https://192.168.1.220:8787/ "))
        assertEquals("https://172.31.3.4", LanClient.normalizeEndpoint("https://172.31.3.4"))
        listOf("http://192.168.1.2", "https://8.8.8.8", "https://192.168.1.2/api", "https://user@192.168.1.2", "https://192.168.1.2#fragment", "https://192.168.1.2:99999").forEach {
            assertThrows(IllegalArgumentException::class.java) { LanClient.normalizeEndpoint(it) }
        }
    }
    @Test fun pairsThenSendsCookieCsrfAndExactPrompt() = withServer { server, client ->
        server.enqueue(response("{\"csrf\":\"csrf123\"}").setHeader("Set-Cookie", "codex_lan=$token; HttpOnly; Secure; Path=/"))
        assertEquals(token, client.pair("12345678").token)
        assertEquals("/api/pair", server.takeRequest().path)
        server.enqueue(response("{\"state\":\"sent\"}"))
        val request = UUID.randomUUID().toString()
        assertEquals("sent", client.send(id, "手机上的消息\n第二行", request).getString("state"))
        val sent = server.takeRequest()
        assertEquals("codex_lan=$token", sent.getHeader("Cookie"))
        assertEquals("csrf123", sent.getHeader("X-CSRF-Token"))
        val body = JSONObject(sent.body.readUtf8())
        assertEquals("手机上的消息\n第二行", body.getString("prompt"))
        assertEquals(request, body.getString("requestId"))
    }
    @Test fun uploadsRawBytesThenSendsAttachmentIdsAndReadsAuthenticatedPreview() = withServer { server, client ->
        server.enqueue(response("{\"csrf\":\"csrf123\"}").setHeader("Set-Cookie","codex_lan=$token; Secure"))
        client.pair("12345678");server.takeRequest()
        val file=UUID.randomUUID().toString();val bytes=byteArrayOf(1,2,3)
        server.enqueue(response("{\"id\":\"$file\",\"name\":\"照片.png\",\"size\":3,\"image\":true}"))
        val uploaded=client.upload(id,file,"照片.png",bytes)
        val upload=server.takeRequest();assertArrayEquals(bytes,upload.body.readByteArray())
        assertEquals("csrf123",upload.getHeader("X-CSRF-Token"));assertEquals("codex_lan=$token",upload.getHeader("Cookie"))
        assertTrue(upload.path!!.contains("name=%E7%85%A7%E7%89%87.png"))
        server.enqueue(response("{\"state\":\"sent\"}"))
        client.send(id,"",UUID.randomUUID().toString(),attachments=listOf(uploaded))
        assertEquals(file,JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("attachments").getString(0))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        assertArrayEquals(bytes,client.imageBytes(requireNotNull(uploaded.preview(id))))
        assertEquals("codex_lan=$token",server.takeRequest().getHeader("Cookie"))
    }
    @Test fun redirectsAreRejectedAndSessionNotForwarded() = withServer { server, client ->
        server.enqueue(response("{\"csrf\":\"csrf123\"}").setHeader("Set-Cookie", "codex_lan=$token; Secure"))
        client.pair("12345678"); server.takeRequest()
        server.enqueue(response("{\"error\":\"moved\"}").setResponseCode(302).setHeader("Location", "https://192.168.1.2:443/api/threads"))
        val error = assertThrows(ApiException::class.java) { client.threads() }
        assertEquals(302, error.status)
        assertEquals(2, server.requestCount)
    }
    @Test fun doesNotRetryARequestAfterServerDisconnects() = withServer { server, client ->
        server.enqueue(response("{\"csrf\":\"csrf123\"}").setHeader("Set-Cookie", "codex_lan=$token; Secure"))
        client.pair("12345678"); server.takeRequest()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertThrows(java.io.IOException::class.java) { client.send(id, "single send", UUID.randomUUID().toString()) }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS))
        assertEquals(2, server.requestCount)
    }
    @Test fun reportsUnknownReceiptAndExpiredSession() = withServer { server, client ->
        server.enqueue(response("{\"csrf\":\"csrf123\"}").setHeader("Set-Cookie", "codex_lan=$token; Secure"))
        client.pair("12345678")
        server.enqueue(response("{\"state\":\"unknown\",\"error\":\"检查会话\"}").setResponseCode(502))
        val error = assertThrows(ApiException::class.java) { client.send(id, "sample", UUID.randomUUID().toString()) }
        assertEquals("unknown", error.receiptState)
        server.enqueue(response("{\"error\":\"请先配对\"}").setResponseCode(401))
        assertEquals(401, assertThrows(ApiException::class.java) { client.threads() }.status)
    }
    @Test fun trustsOnlySelectedCertificateAndChecksHostname() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("192.168.1.2").build()
        val other = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val server = MockWebServer(); server.useHttps(tls.sslSocketFactory(), false); server.start()
        try {
            server.enqueue(response("{}"))
            val sameCert = LanClient("https://127.0.0.1:${server.port}", cert.certificatePem().toByteArray())
            assertThrows(SSLException::class.java) { sameCert.pair("12345678") }
            val wrongCert = LanClient("https://127.0.0.1:${server.port}", other.certificatePem().toByteArray())
            assertThrows(SSLException::class.java) { wrongCert.pair("12345678") }
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun rendersProgressUserTextAndToolsFromDesktopSchema() {
        val t = JSONObject("""{"id":"turn1","status":"completed","items":[
          {"id":"u","type":"userMessage","content":[{"type":"text","text":"开始"}]},
          {"id":"p","type":"agentMessage","phase":"commentary","text":"正在执行"},
          {"id":"c","type":"commandExecution","command":"git status","status":"completed"},
          {"id":"f","type":"fileChange","changes":[{"path":"app/Main.kt"}],"status":"completed"},
          {"id":"a","type":"agentMessage","phase":"final","text":"完成"}]}""")
        val items = ChatViewModel.renderTurn(t)
        assertEquals(5, items.size)
        assertEquals("你", items[0].role)
        assertEquals("开始", items[0].text)
        assertEquals("Codex · 进度", items[1].role)
        assertTrue(items[2].detail)
        assertEquals("执行记录 · 2 项", items[2].role)
        assertTrue(items[2].text.contains("git status"))
        assertTrue(items[2].text.contains("app/Main.kt"))
        assertEquals(5, items.map { it.key }.distinct().size)
    }
    /** Optional read-only integration against the running desktop bridge. */
    @Test fun liveDesktopPairListAndRead() {
        val endpoint = System.getenv("LAN_TEST_ENDPOINT")
        assumeTrue("Live desktop test requires local environment configuration", endpoint != null)
        val cert = File(requireNotNull(System.getenv("LAN_TEST_CERT"))).readBytes()
        val client = LanClient(endpoint!!, cert)
        client.pair(requireNotNull(System.getenv("LAN_TEST_CODE")))
        try {
            assertTrue(client.status().getBoolean("connected"))
            val models=client.models()
            assertTrue(models.isNotEmpty())
            assertTrue(models.all { it.efforts.isNotEmpty() })
            val listing = client.threads()
            val threads = listing.getJSONArray("threads")
            val projects = listing.getJSONArray("projects")
            assertEquals("", listing.optString("projectsNotice"))
            val parsed = ProjectGroups.parseThreads(listing)
            val names = (0 until projects.length()).map { projects.getJSONObject(it) }.associate { it.getString("projectId") to it.getString("label") }
            for (i in 0 until threads.length()) {
                val raw = threads.getJSONObject(i)
                val expectedId = raw.optString("projectId").takeIf { it.isNotBlank() && it != "null" }
                val item = parsed.first { it.id == raw.getString("id") }
                assertEquals(expectedId, item.projectId)
                if (expectedId == null) assertEquals("", item.project)
                else names[expectedId]?.let { assertEquals(it, item.project) }
            }
            assertTrue(threads.length() > 0)
            val target = System.getenv("LAN_TEST_THREAD") ?: threads.getJSONObject(0).getString("id")
            val snapshot = client.read(target)
            assertEquals(target, snapshot.getJSONObject("thread").getString("id"))
            assertTrue(snapshot.has("turns"))
        } finally { client.logout() }
    }
}
