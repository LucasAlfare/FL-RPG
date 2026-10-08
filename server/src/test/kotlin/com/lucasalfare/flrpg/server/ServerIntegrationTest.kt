package com.lucasalfare.flrpg.server

import com.lucasalfare.flrpg.communicationprotocol.*
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerIntegrationTest {

  private fun createDummyClientHello(): ClientHello {
    return ClientHello(
      metadata = MessageMetadata(
        messageId = MessageId(UUID.randomUUID().toString()),
        sessionId = SessionId(UUID.randomUUID().toString()),
        sequence = Sequence.ZERO
      )
    )
  }

  @Test
  fun `should respond with OK on health check route`() = testApplication {
    application {
      module()
    }

    val response = client.get("/health")
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("OK", response.bodyAsText())
  }

  @Test
  fun `should establish session via WebSocket upon receiving ClientHello`() = testApplication {
    application {
      module()
    }

    val wsClient = createClient {
      install(WebSockets)
    }

    wsClient.webSocket("/game") {
      val clientHello = createDummyClientHello()
      send(Frame.Text(clientHello.toJson()))

      val incomingFrame = incoming.receive() as Frame.Text
      val responseText = incomingFrame.readText()

      assertTrue(
        responseText.contains("ServerWelcome"), "Expected ServerWelcome but received: $responseText"
      )
      assertTrue(
        responseText.contains("sessionId"), "Response must contain sessionId"
      )
    }
  }

  @Test
  fun `should accept and manage multiple concurrent clients`() = testApplication {
    val registry = ConnectionRegistry()

    application {
      module(connectionRegistry = registry)
    }

    val wsClient = createClient {
      install(WebSockets)
    }

    coroutineScope {
      val jobs = List(3) {
        async {
          wsClient.webSocket("/game") {
            val clientHello = createDummyClientHello()
            send(Frame.Text(clientHello.toJson()))

            val incomingFrame = incoming.receive() as Frame.Text
            val responseText = incomingFrame.readText()

            assertTrue(responseText.contains("ServerWelcome"))
          }
        }
      }

      jobs.awaitAll()
    }

    var attempts = 0
    while (registry.activeConnections().isNotEmpty() && attempts < 50) {
      delay(50)
      attempts++
    }

    assertEquals(0, registry.activeConnections().size)
  }

  @Test
  fun `should allow continuous bidirectional communication within established session`() = testApplication {
    val registry = ConnectionRegistry()

    application {
      module(connectionRegistry = registry)
    }

    val wsClient = createClient {
      install(WebSockets)
    }

    wsClient.webSocket("/game") {
      val clientHello = createDummyClientHello()
      send(Frame.Text(clientHello.toJson()))

      val welcomeFrame = incoming.receive() as Frame.Text
      val welcomeText = welcomeFrame.readText()
      assertTrue(welcomeText.contains("ServerWelcome"))

      val serverWelcome = welcomeText.toProtocolMessage<ServerWelcome>()
      val assignedSessionId = serverWelcome.metadata.sessionId
      assertNotNull(assignedSessionId)

      val connection = registry.findBySessionId(assignedSessionId)
      assertNotNull(connection, "Connection should be mapped to the established sessionId in registry")

      val subsequentMessage = ClientHello(
        metadata = MessageMetadata(
          messageId = MessageId(UUID.randomUUID().toString()),
          sessionId = assignedSessionId,
          sequence = Sequence.ZERO.next()
        )
      )
      send(Frame.Text(subsequentMessage.toJson()))

      delay(100)
      assertTrue(
        registry.activeConnections().contains(connection),
        "Connection must remain active after sending post-welcome message"
      )
    }
  }

  @Test
  fun `stress test - should handle high concurrent load of connections and messages safely`() = testApplication {
    val registry = ConnectionRegistry()

    application {
      module(connectionRegistry = registry)
    }

    val wsClient = createClient {
      install(WebSockets)
    }

    val concurrentClientsCount = 50

    coroutineScope {
      val jobs = List(concurrentClientsCount) {
        async {
          wsClient.webSocket("/game") {
            val clientHello = createDummyClientHello()
            send(Frame.Text(clientHello.toJson()))

            val welcomeFrame = incoming.receive() as Frame.Text
            val welcomeText = welcomeFrame.readText()
            assertTrue(welcomeText.contains("ServerWelcome"))

            val serverWelcome = welcomeText.toProtocolMessage<ServerWelcome>()
            val assignedSessionId = serverWelcome.metadata.sessionId

            val postWelcomeMessage = ClientHello(
              metadata = MessageMetadata(
                messageId = MessageId(UUID.randomUUID().toString()),
                sessionId = assignedSessionId,
                sequence = Sequence.ZERO.next()
              )
            )
            send(Frame.Text(postWelcomeMessage.toJson()))
            delay(20)
          }
        }
      }

      jobs.awaitAll()
    }

    var attempts = 0
    while (registry.activeConnections().isNotEmpty() && attempts < 100) {
      delay(50)
      attempts++
    }

    assertEquals(
      0, registry.activeConnections().size, "All connections should be cleaned up after stress test completes"
    )
  }
}