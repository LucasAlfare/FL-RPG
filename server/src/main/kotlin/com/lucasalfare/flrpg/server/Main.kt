package com.lucasalfare.flrpg.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

// entry point
fun main() {
  embeddedServer(Netty, port = 8080) {
    routing {
      get("/health") {
        call.respondText("OK")
      }
    }
  }.start(wait = true)
}