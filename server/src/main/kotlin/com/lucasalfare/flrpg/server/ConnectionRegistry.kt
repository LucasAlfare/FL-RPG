package com.lucasalfare.flrpg.server

import com.lucasalfare.flrpg.communicationprotocol.ClientMessage
import com.lucasalfare.flrpg.communicationprotocol.ServerMessage
import com.lucasalfare.flrpg.communicationprotocol.SessionId
import com.lucasalfare.flrpg.gamedomain.CharacterId
import java.util.concurrent.ConcurrentHashMap

interface Connection {
  val id: String
  var sessionId: SessionId?
  var characterId: CharacterId?

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

  fun findBySessionId(
    sessionId: SessionId
  ): Connection? = connections.values.firstOrNull {
    it.sessionId == sessionId
  }

  fun findByCharacterId(
    characterId: CharacterId
  ): Connection? = connections.values.firstOrNull {
    it.characterId == characterId
  }

  fun bindCharacter(
    sessionId: SessionId, characterId: CharacterId
  ): Boolean {
    val connection = findBySessionId(sessionId) ?: return false

    connection.characterId = characterId
    return true
  }

  fun unbindCharacter(sessionId: SessionId) {
    findBySessionId(sessionId)?.characterId = null
  }

  fun findCharacterIdBySessionId(
    sessionId: SessionId
  ): CharacterId? = findBySessionId(sessionId)?.characterId

  fun activeConnections(): List<Connection> = connections.values.toList()
}