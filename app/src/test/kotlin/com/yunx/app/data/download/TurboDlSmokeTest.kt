package com.yunx.app.data.download

import com.sun.net.httpserver.HttpServer
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
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

class TurboDlSmokeTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun downloadsWithRequestHeaders() = runBlocking {
        val bytes = "FPAN TurboDL test".toByteArray()
        val sawHeader = AtomicBoolean(false)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { exchange ->
            if (exchange.requestHeaders.getFirst("X-Test-Token") == "expected") {
                sawHeader.set(true)
            }
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val bootstrap = TurboBootstrap.create(
            TurboConfig(workDir = temporaryFolder.newFolder("work")),
            extraPlugins = listOf(HlsPlugin()),
        )
        try {
            val output = temporaryFolder.newFile("output")
            val id = bootstrap.client.submit(DownloadRequest(
                url = "http://127.0.0.1:${server.address.port}/file",
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
            server.stop(0)
        }
    }
}
