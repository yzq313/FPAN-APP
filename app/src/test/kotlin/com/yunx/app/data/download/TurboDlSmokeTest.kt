package com.yunx.app.data.download

import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.TurboConfig
import dev.turbodl.plugin.bootstrap.TurboBootstrap
import dev.turbodl.plugin.hls.HlsPlugin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.util.concurrent.atomic.AtomicBoolean

class TurboDlSmokeTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun downloadsWithRequestHeaders() = runBlocking {
        val bytes = "FPAN TurboDL test".toByteArray()
        val sawHeader = AtomicBoolean(false)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("X-Test-Token") == "expected") {
                    sawHeader.set(true)
                }
                return MockResponse().setBody(Buffer().write(bytes))
            }
        }
        server.start()
        val bootstrap = TurboBootstrap.create(
            TurboConfig(workDir = temporaryFolder.newFolder("work")),
            extraPlugins = listOf(HlsPlugin()),
        )
        try {
            val output = temporaryFolder.newFile("output")
            val id = bootstrap.client.submit(DownloadRequest(
                url = server.url("/file").toString(),
                destination = output,
                headers = mapOf("X-Test-Token" to "expected"),
                connectionsOverride = 1,
                stableKey = "test",
            ))
            withTimeout(30_000) { bootstrap.client.await(id) }.getOrThrow()
            assertTrue(sawHeader.get())
            assertArrayEquals(bytes, output.readBytes())
        } finally {
            bootstrap.shutdown()
            server.shutdown()
        }
    }
}
