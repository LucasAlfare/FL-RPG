package com.lucasalfare.flrpg.server

import com.lucasalfare.flrpg.communicationprotocol.ClientMessage
import com.lucasalfare.flrpg.communicationprotocol.ProtocolMessage
import com.lucasalfare.flrpg.communicationprotocol.ServerMessage
import com.lucasalfare.flrpg.communicationprotocol.toJson
import com.lucasalfare.flrpg.communicationprotocol.toProtocolMessage
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
import io.ktor.websocket.readText
import io.ktor.websocket.send

// domain
interface Connection {
  suspend fun send(message: ServerMessage)
  suspend fun receive(): ClientMessage?
}

suspend fun handleConnection(connection: Connection) {
  while (true) {
    val message = connection.receive() ?: break
  }
}

// infrastructure
class KtorConnection(
  private val session: DefaultWebSocketServerSession
) : Connection {
  override suspend fun send(message: ServerMessage) {
    session.send(message.toJson())
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
}

// entry point
fun main() {
  embeddedServer(Netty, port = 8080) {
    install(WebSockets)

    routing {
      get("/health") {
        call.respondText("OK")
      }

      webSocket("/game") {
        val connection = KtorConnection(this)
        handleConnection(connection)
      }
    }
  }.start(wait = true)
}