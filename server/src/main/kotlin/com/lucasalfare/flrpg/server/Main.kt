@file:Suppress("unused", "UnusedVariable")

package com.lucasalfare.flrpg.server

import com.lucasalfare.flrpg.communicationprotocol.*
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// domain
interface Connection {
  val id: String
  var sessionId: SessionId?
  suspend fun send(message: ServerMessage)
  suspend fun receive(): ClientMessage?
  suspend fun close()
}

class ConnectionRegistry {
  private val connections = ConcurrentHashMap<String, Connection>()

  fun register(connection: Connection) {
    connections[connection.id] = connection
  }

  fun unregister(id: String) {
    connections.remove(id)
  }

  fun findById(id: String): Connection? = connections[id]

  fun findBySessionId(sessionId: SessionId): Connection? = connections.values.firstOrNull { it.sessionId == sessionId }

  fun activeConnections(): List<Connection> = connections.values.toList()
}

suspend fun handleConnection(
  connection: Connection,
  registry: ConnectionRegistry,
  sessionLifecycleHandler: SessionLifecycleHandler = SessionLifecycleHandler(),
  structuralValidator: StructuralValidator = StructuralValidator(),
  protocolValidator: ProtocolValidator = ProtocolValidator()
) {
  registry.register(connection)
  var sequenceTracker: SessionSequenceTracker? = null

  try {
    while (true) {
      val message = connection.receive() ?: break

      if (structuralValidator.validate(message) !is ValidationResult.Valid) {
        continue
      }

      val currentSessionId = connection.sessionId
      if (currentSessionId == null) {
        if (protocolValidator.validateSessionState(SessionState.NON_EXISTENT, message) !is ValidationResult.Valid) {
          continue
        }

        if (message is ClientHello) {
          val replyMessageId = MessageId(UUID.randomUUID().toString())
          val (_, response) = synchronized(sessionLifecycleHandler) {
            sessionLifecycleHandler.processHello(message, replyMessageId)
          }
          if (response is ServerWelcome) {
            connection.sessionId = response.metadata.sessionId
            sequenceTracker = SessionSequenceTracker(response.metadata.sessionId)
          }
          connection.send(response)
        }
      } else {
        if (protocolValidator.validateSessionState(SessionState.ESTABLISHED, message) !is ValidationResult.Valid) {
          continue
        }

        if (message.sessionId != currentSessionId) {
          continue
        }

        val status = sequenceTracker?.processInbound(message)
        if (status != null && !status.isAccepted) {
          continue
        }
      }
    }
  } catch (_: Exception) {
  } finally {
    runCatching { connection.close() }
    registry.unregister(connection.id)
  }
}

// infrastructure
class KtorConnection(
  override val id: String = UUID.randomUUID().toString(),
  override var sessionId: SessionId? = null,
  private val session: DefaultWebSocketServerSession
) : Connection {
  override suspend fun send(message: ServerMessage) {
    runCatching {
      session.send(message.toJson())
    }
  }

  override suspend fun receive(): ClientMessage? {
    try {
      for (frame in session.incoming) {
        if (frame is Frame.Text) {
          val text = frame.readText()
          val protocolMessage = runCatching { text.toProtocolMessage<ProtocolMessage>() }.getOrNull()
          if (protocolMessage is ClientMessage) {
            return protocolMessage
          }
        }
      }
    } catch (_: Exception) {
      return null
    }
    return null
  }

  override suspend fun close() {
    runCatching {
      session.close()
    }
  }
}

// entry point
fun main() {
  val connectionRegistry = ConnectionRegistry()
  val sessionLifecycleHandler = SessionLifecycleHandler()

  embeddedServer(Netty, port = 8080) {
    install(WebSockets)

    routing {
      get("/health") {
        call.respondText("OK")
      }

      webSocket("/game") {
        val connection = KtorConnection(session = this)
        handleConnection(connection, connectionRegistry, sessionLifecycleHandler)
      }
    }
  }.start(wait = true)
}