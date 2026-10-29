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

    @Test fun hlsPluginDownloadsAndCombinesSegments() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/video.m3u8" -> MockResponse().setBody(
                    "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nfirst.ts\n" +
                        "#EXTINF:1,\nsecond.ts\n#EXT-X-ENDLIST\n"
                )
                "/first.ts" -> MockResponse().setBody("first")
                "/second.ts" -> MockResponse().setBody("second")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val bootstrap = TurboBootstrap.create(
            TurboConfig(workDir = temporaryFolder.newFolder("hls-work")),
            extraPlugins = listOf(HlsPlugin()),
        )
        try {
            val output = temporaryFolder.newFile("hls-output")
            val id = bootstrap.client.submit(DownloadRequest(
                url = server.url("/video.m3u8").toString(),
                destination = output,
                stableKey = "hls-test",
            ))
            withTimeout(30_000) { bootstrap.client.await(id) }.getOrThrow()
            assertArrayEquals("firstsecond".toByteArray(), output.readBytes())
        } finally {
            bootstrap.shutdown()
            server.shutdown()
        }
    }
}
