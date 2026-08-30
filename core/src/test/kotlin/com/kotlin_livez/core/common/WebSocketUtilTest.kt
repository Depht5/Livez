package com.kotlin_livez.core.common

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebSocketUtilTest {

    private val servers = mutableListOf<MockWebServer>()
    private val sockets = mutableListOf<WebSocketUtil>()

    @After
    fun tearDown() {
        sockets.forEach { it.close() }
        servers.forEach { runCatching { it.shutdown() } }
    }

    @Test
    fun initialHandshakeFailureIsRetried() {
        val server = MockWebServer().also {
            servers += it
            it.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            it.enqueue(webSocketResponse("connected-after-retry"))
            it.start()
        }
        val messageReceived = CountDownLatch(1)
        val socket = newSocket(
            url = server.url("/").toString(),
            onMessage = { if (it == "connected-after-retry") messageReceived.countDown() },
        )

        socket.connect()

        assertTrue("WebSocket should retry a failed initial handshake", messageReceived.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun establishedSocketFailureIsRetried() {
        val server = MockWebServer().also {
            servers += it
            it.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.close(1011, "test disconnect")
                    }
                })
            )
            it.enqueue(webSocketResponse("connected-again"))
            it.start()
        }
        val messageReceived = CountDownLatch(1)
        val socket = newSocket(
            url = server.url("/").toString(),
            onMessage = { if (it == "connected-again") messageReceived.countDown() },
        )

        socket.connect()

        assertTrue("WebSocket should retry after an established connection is lost", messageReceived.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun connectionWithoutFirstMessageIsRetried() {
        val server = MockWebServer().also {
            servers += it
            it.enqueue(webSocketResponse(null))
            it.enqueue(webSocketResponse("message-after-timeout"))
            it.start()
        }
        val messageReceived = CountDownLatch(1)
        val socket = newSocket(
            url = server.url("/").toString(),
            firstMessageTimeoutMs = 50,
            onMessage = { if (it == "message-after-timeout") messageReceived.countDown() },
        )

        socket.connect()

        assertTrue("WebSocket should retry when the server stays silent after open", messageReceived.await(3, TimeUnit.SECONDS))
    }

    private fun newSocket(
        url: String,
        firstMessageTimeoutMs: Long = 0,
        onMessage: (String) -> Unit,
    ): WebSocketUtil =
        WebSocketUtil(
            url = url,
            heartBeatTime = 0,
            onMessage = onMessage,
            reconnectDelayMs = 20,
            maxReconnectTime = 3,
            pingIntervalMs = 0,
            firstMessageTimeoutMs = firstMessageTimeoutMs,
        ).also { sockets += it }

    private fun webSocketResponse(message: String?): MockResponse =
        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (message != null) webSocket.send(message)
            }
        })
}
