package com.kotlin_livez.core.common

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test
    fun immediateFirstMessageIsNotOverwrittenByReadyInitialization() {
        val server = MockWebServer().also {
            servers += it
            it.enqueue(webSocketResponse("immediate-business"))
            it.enqueue(webSocketResponse("unexpected-reconnect"))
            it.start()
        }
        val messageReceived = CountDownLatch(1)
        val socket = newSocket(
            url = server.url("/").toString(),
            firstMessageTimeoutMs = 80,
            onMessage = { if (it == "immediate-business") messageReceived.countDown() },
        )

        socket.connect()

        assertTrue("The immediate frame should arrive", messageReceived.await(1, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertEquals("ready must not re-arm the first-message watchdog", 1, server.requestCount)
    }

    @Test
    fun controlFrameDoesNotSatisfyBusinessWatchdog() {
        val server = MockWebServer().also {
            servers += it
            it.enqueue(webSocketResponse("control-frame"))
            it.enqueue(webSocketResponse("business-frame"))
            it.start()
        }
        val controlReceived = CountDownLatch(1)
        val businessReceived = CountDownLatch(1)
        lateinit var socket: WebSocketUtil
        socket = newSocket(
            url = server.url("/").toString(),
            firstMessageTimeoutMs = 80,
            autoMarkHealthyOnMessage = false,
            onMessage = { message ->
                when (message) {
                    "control-frame" -> controlReceived.countDown()
                    "business-frame" -> {
                        socket.markMessageHealthy()
                        businessReceived.countDown()
                    }
                }
            },
        )

        socket.connect()

        assertTrue("The control frame should arrive", controlReceived.await(1, TimeUnit.SECONDS))
        assertTrue("A control-only socket should be replaced", businessReceived.await(3, TimeUnit.SECONDS))
        assertTrue("The watchdog should create a second connection", server.requestCount >= 2)
    }

    @Test
    fun businessIdleTimeoutIsRollingAndReconnects() {
        val server = MockWebServer().also {
            servers += it
            it.enqueue(webSocketResponse("business-1"))
            it.enqueue(webSocketResponse("business-2"))
            it.start()
        }
        val secondBusinessReceived = CountDownLatch(1)
        lateinit var socket: WebSocketUtil
        socket = newSocket(
            url = server.url("/").toString(),
            messageIdleTimeoutMs = 80,
            autoMarkHealthyOnMessage = false,
            onMessage = { message ->
                socket.markMessageHealthy()
                if (message == "business-2") secondBusinessReceived.countDown()
            },
        )

        socket.connect()

        assertTrue("Business silence should replace the stale connection", secondBusinessReceived.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun closeCancelsScheduledReconnectAndCannotBeRevived() {
        val server = MockWebServer().also {
            servers += it
            it.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.close(1011, "test disconnect")
                    }
                }),
            )
            it.enqueue(webSocketResponse("must-not-connect"))
            it.start()
        }
        val reconnecting = CountDownLatch(1)
        val socket = newSocket(
            url = server.url("/").toString(),
            reconnectDelayMs = 250,
            onReconnect = { reconnecting.countDown() },
            onMessage = {},
        )
        socket.connect()
        assertTrue("The failed socket should schedule a reconnect", reconnecting.await(1, TimeUnit.SECONDS))

        socket.close()
        socket.connect()
        Thread.sleep(400)

        assertEquals("close must make the utility permanently one-shot", 1, server.requestCount)
    }

    private fun newSocket(
        url: String,
        firstMessageTimeoutMs: Long = 0,
        messageIdleTimeoutMs: Long = 0,
        autoMarkHealthyOnMessage: Boolean = true,
        reconnectDelayMs: Long = 20,
        onReconnect: (() -> Unit)? = null,
        onMessage: (String) -> Unit,
    ): WebSocketUtil =
        WebSocketUtil(
            url = url,
            heartBeatTime = 0,
            onMessage = onMessage,
            onReconnect = onReconnect,
            reconnectDelayMs = reconnectDelayMs,
            maxReconnectTime = 3,
            pingIntervalMs = 0,
            firstMessageTimeoutMs = firstMessageTimeoutMs,
            messageIdleTimeoutMs = messageIdleTimeoutMs,
            autoMarkHealthyOnMessage = autoMarkHealthyOnMessage,
        ).also { sockets += it }

    private fun webSocketResponse(message: String?): MockResponse =
        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (message != null) webSocket.send(message)
            }
        })
}
