package com.lucasalfare.flrpg.server

import com.lucasalfare.flrpg.communicationprotocol.*
import com.lucasalfare.flrpg.gamedomain.CharacterId
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*

class KtorConnection(
  override val id: String = UUID.randomUUID().toString(),
  override var sessionId: SessionId? = null,
  override var characterId: CharacterId? = null,
  private val session: DefaultWebSocketServerSession
) : Connection {
  private val sendMutex = Mutex()

  override suspend fun send(message: ServerMessage) {
    runCatching {
      sendMutex.withLock {
        session.send(message.toJson())
      }
    }
  }

  override suspend fun receive(): ClientMessage? {
    try {
      for (frame in session.incoming) {
        if (frame is Frame.Text) {
          val text = frame.readText()

          val protocolMessage = runCatching {
            text.toProtocolMessage<ProtocolMessage>()
          }.getOrNull()

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