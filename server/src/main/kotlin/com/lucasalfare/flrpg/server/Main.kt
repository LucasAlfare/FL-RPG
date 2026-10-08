package com.lucasalfare.flrpg.server

import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send

// infrastructure
fun main() {
  embeddedServer(Netty, port = 8080) {
    install(WebSockets)

    routing {
      get("/health") {
        call.respondText("OK")
      }

      webSocket("/game") {
        for (frame in incoming) {
          if (frame is Frame.Text) {
            val text = frame.readText()
            send("echo: $text")
          }
        }
      }
    }
  }.start(wait = true)
}