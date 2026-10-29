package com.yunx.app.data.download

import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.TurboConfig
import dev.turbodl.core.TaskState
import dev.turbodl.plugin.bootstrap.TurboBootstrap
import dev.turbodl.plugin.hls.HlsPlugin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
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
import java.util.concurrent.TimeUnit

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

    @Test fun pauseAndResumePreservesDownloadContent() = runBlocking {
        val bytes = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        val resuming = AtomicBoolean(false)
        val sawResumedRange = AtomicBoolean(false)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                val match = range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
                val start = match?.groupValues?.get(1)?.toInt() ?: 0
                if (resuming.get() && start > 0) sawResumedRange.set(true)
                val end = match?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(bytes.lastIndex)
                    ?: bytes.lastIndex
                val response = MockResponse()
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("ETag", "\"fpan-test\"")
                    .setBody(Buffer().write(bytes, start, end - start + 1))
                    .throttleBody(16 * 1024, 10, TimeUnit.MILLISECONDS)
                if (match != null) {
                    response.setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                }
                return response
            }
        }
        server.start()
        val bootstrap = TurboBootstrap.create(TurboConfig(workDir = temporaryFolder.newFolder("resume-work")))
        try {
            val output = temporaryFolder.newFile("resume-output")
            val request = DownloadRequest(
                url = server.url("/file").toString(),
                destination = output,
                connectionsOverride = 1,
                stableKey = "resume-test",
            )
            val first = bootstrap.client.submit(request)
            withTimeout(30_000) {
                while ((bootstrap.client.progress.value[first]?.downloadedBytes ?: 0) < 128 * 1024) delay(50)
            }
            bootstrap.client.pause(first)
            withTimeout(30_000) {
                while (bootstrap.client.progress.value[first]?.state != TaskState.PAUSED) delay(50)
            }
            resuming.set(true)
            val resumed = bootstrap.client.submit(request)
            withTimeout(45_000) { bootstrap.client.await(resumed) }.getOrThrow()
            assertArrayEquals(bytes, output.readBytes())
            assertTrue("expected a nonzero range after resume", sawResumedRange.get())
        } finally {
            bootstrap.shutdown()
            server.shutdown()
        }
    }
}
