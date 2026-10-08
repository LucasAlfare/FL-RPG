package com.lucasalfare.flrpg.server

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
  suspend fun send(message: String)
  suspend fun receive(): String?
}

suspend fun handleConnection(connection: Connection) {
  while (true) {
    val message = connection.receive() ?: break
    connection.send("echo: $message")
  }
}

// infrastructure
class KtorConnection(
  private val session: DefaultWebSocketServerSession
) : Connection {
  override suspend fun send(message: String) {
    session.send(message)
  }

  override suspend fun receive(): String? {
    try {
      for (frame in session.incoming) {
        if (frame is Frame.Text) {
          return frame.readText()
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