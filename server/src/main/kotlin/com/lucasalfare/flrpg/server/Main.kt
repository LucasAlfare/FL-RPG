package com.lucasalfare.flrpg.server

import com.lucasalfare.flrpg.communicationprotocol.*
import com.lucasalfare.flrpg.gamedomain.CharacterId
import com.lucasalfare.flrpg.gamedomain.GameAction
import com.lucasalfare.flrpg.gamedomain.GameActionProcessor
import com.lucasalfare.flrpg.gamedomain.GameActionResult
import com.lucasalfare.flrpg.gamedomain.GameFact
import com.lucasalfare.flrpg.gamedomain.GameFactAudience
import com.lucasalfare.flrpg.gamedomain.Nucleus
import com.lucasalfare.flrpg.gamedomain.NucleusId
import io.ktor.server.application.Application
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

suspend fun distributeAudiences(
  audiences: List<GameFactAudience>, registry: ConnectionRegistry, excludeCharacterId: CharacterId? = null
) {
  for (audience in audiences) {
    val fact = audience.fact
    val protocolFact = ProtocolFact(
      name = fact.name, arguments = fact.arguments.mapValues { (_, value) -> value?.toString() ?: "" })

    for (interestedId in audience.characterIds) {
      if (interestedId == excludeCharacterId) {
        continue
      }

      val targetConnection = registry.findByCharacterId(interestedId) ?: continue

      val targetSessionId = targetConnection.sessionId ?: continue

      val eventMessage = ServerEvent(
        metadata = MessageMetadata(
          messageId = MessageId(UUID.randomUUID().toString()), sessionId = targetSessionId, sequence = Sequence.ZERO
        ), type = protocolFact.name, payload = protocolFact.arguments
      )

      targetConnection.send(eventMessage)
    }
  }
}

suspend fun distributeFacts(
  facts: List<GameFact>,
  registry: ConnectionRegistry,
  actionProcessor: GameActionProcessor,
  excludeCharacterId: CharacterId? = null
) {
  val audiences = actionProcessor.findFactAudiences(facts)

  distributeAudiences(
    audiences = audiences, registry = registry, excludeCharacterId = excludeCharacterId
  )
}

suspend fun advanceTimeAndDistribute(
  ticks: Long, registry: ConnectionRegistry, actionProcessor: GameActionProcessor
): List<GameFact> {
  val audiences = actionProcessor.advanceTimeWithAudiences(ticks)

  distributeAudiences(
    audiences = audiences, registry = registry
  )

  return audiences.map { it.fact }
}

suspend fun handleConnection(
  connection: Connection,
  registry: ConnectionRegistry,
  sessionLifecycleHandler: SessionLifecycleHandler = SessionLifecycleHandler(),
  structuralValidator: StructuralValidator = StructuralValidator(),
  protocolValidator: ProtocolValidator = ProtocolValidator(),
  actionProcessor: GameActionProcessor? = null
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
        if (protocolValidator.validateSessionState(
            SessionState.NON_EXISTENT, message
          ) !is ValidationResult.Valid
        ) {
          continue
        }

        if (message is ClientHello) {
          val replyMessageId = MessageId(UUID.randomUUID().toString())

          val (_, response) = synchronized(
            sessionLifecycleHandler
          ) {
            sessionLifecycleHandler.processHello(
              message, replyMessageId
            )
          }

          if (response is ServerWelcome) {
            connection.sessionId = response.metadata.sessionId

            connection.characterId = CharacterId(UUID.randomUUID().toString())

            sequenceTracker = SessionSequenceTracker(
              response.metadata.sessionId
            )
          }

          connection.send(response)
        }
      } else {
        if (protocolValidator.validateSessionState(
            SessionState.ESTABLISHED, message
          ) !is ValidationResult.Valid
        ) {
          continue
        }

        if (message.sessionId != currentSessionId) {
          continue
        }

        val status = sequenceTracker?.processInbound(message)

        if (status != null && !status.isAccepted) {
          continue
        }

        if (message is ClientAction && actionProcessor != null) {
          val characterId = registry.findCharacterIdBySessionId(
            currentSessionId
          )

          if (characterId != null) {
            val gameAction = GameAction(
              name = message.actionId, actorId = characterId, arguments = message.parameters
            )

            val execution = actionProcessor.processWithAudiences(
              gameAction
            )

            val result = execution.result

            val correlationId = CorrelationId(message.metadata.messageId)

            val responseMessage = when (result) {
              is GameActionResult.Success -> {
                val protocolFacts = result.facts.map { fact ->
                  ProtocolFact(
                    name = fact.name, arguments = fact.arguments.mapValues { (_, value) ->
                      value?.toString() ?: ""
                    })
                }

                ServerCompleted(
                  metadata = MessageMetadata(
                    messageId = MessageId(
                      UUID.randomUUID().toString()
                    ), sessionId = currentSessionId, sequence = Sequence.ZERO, correlationId = correlationId
                  ), facts = protocolFacts
                )
              }

              is GameActionResult.Rejected -> {
                ServerRejected(
                  metadata = MessageMetadata(
                    messageId = MessageId(
                      UUID.randomUUID().toString()
                    ), sessionId = currentSessionId, sequence = Sequence.ZERO, correlationId = correlationId
                  ), reason = result.reason
                )
              }
            }

            connection.send(responseMessage)

            if (result is GameActionResult.Success) {
              distributeAudiences(
                audiences = execution.factAudiences, registry = registry, excludeCharacterId = characterId
              )
            }
          }
        }
      }
    }
  } catch (_: Exception) {
    // Connection failures terminate this connection.
  } finally {
    runCatching {
      connection.close()
    }

    registry.unregister(connection.id)
  }
}

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

fun main() {
  embeddedServer(
    Netty, port = 8080, module = Application::module
  ).start(wait = true)
}

fun Application.module(
  connectionRegistry: ConnectionRegistry = ConnectionRegistry(),
  sessionLifecycleHandler: SessionLifecycleHandler = SessionLifecycleHandler(),
  actionProcessor: GameActionProcessor? = null
) {
  val activeActionProcessor = actionProcessor ?: GameActionProcessor(
    Nucleus(NucleusId("main_nucleus"))
  )

  install(WebSockets)

  routing {
    get("/health") {
      call.respondText("OK")
    }

    webSocket("/game") {
      val connection = KtorConnection(session = this)

      handleConnection(
        connection = connection,
        registry = connectionRegistry,
        sessionLifecycleHandler = sessionLifecycleHandler,
        actionProcessor = activeActionProcessor
      )
    }
  }
}