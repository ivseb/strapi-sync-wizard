package it.sebi.client

import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.extensions.HashAlgorithm
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The OpenShift routers in front of the Strapi instances accept TLS 1.3 only and answer a TLS 1.2
 * ClientHello with a fatal ProtocolVersion alert. These tests run the real client against a local
 * TLS 1.3-only server with a self-signed certificate valid for "localhost" only.
 */
class StrapiClientTlsTest {

    private lateinit var server: SSLServerSocket
    private lateinit var serverThread: Thread

    @BeforeTest
    fun startServer() {
        val password = "test"
        val keyStore = buildKeyStore {
            certificate("strapi") {
                this.password = password
                hash = HashAlgorithm.SHA256
                keySizeInBits = 2048
                domains = listOf("localhost")
                ipAddresses = emptyList()
            }
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password.toCharArray())
        }
        val sslContext = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        server = (sslContext.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()) as SSLServerSocket).apply {
            enabledProtocols = arrayOf("TLSv1.3")
        }
        serverThread = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() as SSLSocket }.getOrNull() ?: break
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    private fun serve(socket: SSLSocket) = socket.use {
        runCatching {
            val reader = it.inputStream.bufferedReader()
            while (reader.readLine()?.isNotEmpty() == true) Unit
            val body = """{"protocol":"${it.session.protocol}"}"""
            it.outputStream.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray()
            )
            it.outputStream.flush()
        }
    }

    @AfterTest
    fun stopServer() {
        server.close()
        serverThread.join(1000)
    }

    @Test
    fun `negotiates TLS 1_3 and accepts a self-signed certificate`() = runBlocking {
        val url = "https://localhost:${server.localPort}/api/ping"
        val body = ClientSelector().getClientForUrl(url).get(url) { retry { noRetry() } }.bodyAsText()
        assertEquals("""{"protocol":"TLSv1.3"}""", body)
    }

    @Test
    fun `still rejects a certificate issued for another hostname`() = runBlocking {
        val url = "https://127.0.0.1:${server.localPort}/api/ping"
        try {
            ClientSelector().getClientForUrl(url).get(url) { retry { noRetry() } }
            fail("Expected hostname verification to fail")
        } catch (e: Exception) {
            assertTrue(
                generateSequence<Throwable>(e) { it.cause }.any { it is SSLPeerUnverifiedException },
                "Expected SSLPeerUnverifiedException, got $e"
            )
        }
    }
}
