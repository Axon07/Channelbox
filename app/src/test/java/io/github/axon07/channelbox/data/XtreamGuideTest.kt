package io.github.axon07.channelbox.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/** The Xtream server's own per-channel guide (get_simple_data_table), used when xmltv.php misses channels. */
class XtreamGuideTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun login() = XtreamClient.Login(server.url("/").toString().trimEnd('/'), "user", "pass")
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    @Test
    fun readsBase64Listings() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"epg_listings":[
                  {"id":"1","title":"${b64("Evening News")}","description":"${b64("Héadlines")}","start":"2026-11-22 18:00:00","end":"2026-11-22 19:00:00","start_timestamp":"1795370400","stop_timestamp":"1795374000"},
                  {"id":"2","title":"${b64("Hockey")}","description":"","start_timestamp":1795374000,"stop_timestamp":1795384800},
                  {"id":"3","title":"${b64("Broken")}","start_timestamp":"1795384800","stop_timestamp":"1795384800"}
                ]}"""
            )
        )
        val list = XtreamClient(http).guide(login(), "177398")
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("action=get_simple_data_table"))
        assertTrue(req.path!!.contains("stream_id=177398"))
        assertEquals(2, list.size)
        assertEquals("Evening News", list[0].title)
        assertEquals("Héadlines", list[0].description)
        assertEquals(1795370400_000L, list[0].start)
        assertEquals(1795374000_000L, list[0].stop)
        assertEquals("Hockey", list[1].title)
        assertNull(list[1].description)
    }

    @Test
    fun plainTitlesStayAsTheyAre() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"epg_listings":[{"title":"Live: Jets vs Oilers","start_timestamp":"100","stop_timestamp":"200"}]}"""))
        assertEquals("Live: Jets vs Oilers", XtreamClient(http).guide(login(), "1").single().title)
    }

    @Test
    fun noGuideIsEmpty() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"epg_listings":[]}"""))
        server.enqueue(MockResponse().setBody("[]"))
        assertTrue(XtreamClient(http).guide(login(), "1").isEmpty())
        assertTrue(XtreamClient(http).guide(login(), "1").isEmpty())
    }
}
