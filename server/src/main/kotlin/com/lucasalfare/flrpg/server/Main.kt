package com.lucasalfare.flrpg.server

import com.lucasalfare.flrpg.communicationprotocol.*
import com.lucasalfare.flrpg.gamedomain.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import java.util.concurrent.ConcurrentHashMap

private const val SIMULATION_TICK_INTERVAL_MS: Long = 1_000L
private const val SIMULATION_TICKS_PER_INTERVAL: Long = 1L

private val DefaultPreferredInteractionShortcuts: Map<String, String> = mapOf(
  "move" to "M"
)

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

class PendingInteraction(
  val interaction: Interaction,
  val collectedArguments: MutableMap<String, Any?> = mutableMapOf(),
  val remainingArguments: MutableList<String> = interaction.requiredArgumentNames.toMutableList()
)

class SessionPendingRegistry {
  private val pendingMap = ConcurrentHashMap<String, PendingInteraction>()

  fun set(sessionId: SessionId, pending: PendingInteraction) {
    pendingMap[sessionId.value] = pending
  }

  fun find(sessionId: SessionId): PendingInteraction? = pendingMap[sessionId.value]

  fun remove(sessionId: SessionId) {
    pendingMap.remove(sessionId.value)
  }
}

fun interface ShortcutAssigner {
  fun assign(choices: List<Choice>): List<Choice>
}

class SequentialLetterShortcutAssigner : ShortcutAssigner {

  override fun assign(choices: List<Choice>): List<Choice> {
    if (choices.isEmpty()) return choices

    val used = mutableSetOf<String>()
    for (choice in choices) {
      val existing = choice.shortcut
      if (existing != null && choice.enabled) {
        require(used.add(existing)) {
          "Conflicting shortcut '$existing' among simultaneously selectable choices."
        }
      }
    }

    var nextIndex = 0
    return choices.map { choice ->
      if (choice.shortcut != null) {
        choice
      } else {
        val shortcut = nextAvailable(used, nextIndex)
        nextIndex = shortcut.second
        used.add(shortcut.first)
        choice.copy(shortcut = shortcut.first)
      }
    }.also { result ->
      validateNoConflicts(result)
    }
  }

  private fun nextAvailable(used: Set<String>, startIndex: Int): Pair<String, Int> {
    var index = startIndex
    while (true) {
      val candidate = indexToShortcut(index)
      if (candidate !in used) {
        return candidate to (index + 1)
      }
      index++
    }
  }

  private fun indexToShortcut(index: Int): String {
    require(index >= 0)
    var n = index
    val chars = mutableListOf<Char>()
    do {
      chars.add('A' + (n % 26))
      n = n / 26 - 1
    } while (n >= 0)
    return chars.reversed().joinToString("")
  }

  private fun validateNoConflicts(choices: List<Choice>) {
    val seen = mutableSetOf<String>()
    for (choice in choices) {
      val s = choice.shortcut
      if (s != null && choice.enabled) {
        require(seen.add(s)) {
          "Conflicting shortcut '$s' among simultaneously selectable choices."
        }
      }
    }
  }
}

class FixedShortcutAssigner(
  private val shortcutsByChoiceId: Map<String, String>
) : ShortcutAssigner {

  override fun assign(choices: List<Choice>): List<Choice> {
    val result = choices.map { choice ->
      val mapped = shortcutsByChoiceId[choice.id]
      if (mapped != null) {
        choice.copy(shortcut = mapped)
      } else {
        choice
      }
    }
    validateNoConflicts(result)
    return result
  }

  private fun validateNoConflicts(choices: List<Choice>) {
    val seen = mutableSetOf<String>()
    for (choice in choices) {
      val s = choice.shortcut
      if (s != null && choice.enabled) {
        require(seen.add(s)) {
          "Conflicting shortcut '$s' among simultaneously selectable choices."
        }
      }
    }
  }
}

data class OfferedOption(
  val choiceId: String, val interaction: Interaction
)

data class PreparedPresentation(
  val choices: List<Choice>, val offered: List<OfferedOption>, val document: PresentationDocument
)

class SessionOfferState {
  @Volatile
  var revision: Revision = Revision.ZERO
    private set

  private val offeredByChoiceId = ConcurrentHashMap<String, Interaction>()

  fun replaceOffers(offered: List<OfferedOption>, newRevision: Revision) {
    offeredByChoiceId.clear()
    for (option in offered) {
      offeredByChoiceId[option.choiceId] = option.interaction
    }
    revision = newRevision
  }

  fun findInteraction(choiceId: String): Interaction? = offeredByChoiceId[choiceId]

  fun clear() {
    offeredByChoiceId.clear()
    revision = Revision.ZERO
  }

  fun offeredSnapshot(): Map<String, Interaction> = offeredByChoiceId.toMap()
}

class SessionOfferRegistry {
  private val states = ConcurrentHashMap<String, SessionOfferState>()

  fun getOrCreate(sessionId: SessionId): SessionOfferState = states.getOrPut(sessionId.value) { SessionOfferState() }

  fun remove(sessionId: SessionId) {
    states.remove(sessionId.value)?.clear()
  }

  fun find(sessionId: SessionId): SessionOfferState? = states[sessionId.value]
}

class InteractionPresentationPreparer(
  private val shortcutAssigner: ShortcutAssigner = SequentialLetterShortcutAssigner(),
  private val choicesBlockId: BlockId = BlockId("available_interactions"),
  private val preferredShortcutsByInteractionId: Map<String, String> = emptyMap()
) {

  fun prepare(interactions: List<Interaction>, baseRevision: Revision = Revision.ZERO): PreparedPresentation {
    val offered = interactions.map { interaction ->
      OfferedOption(
        choiceId = selectionIdFor(interaction), interaction = interaction
      )
    }

    val rawChoices = offered.map { option ->
      Choice(
        id = option.choiceId,
        text = option.interaction.label,
        shortcut = preferredShortcutsByInteractionId[option.interaction.id.value],
        enabled = true
      )
    }

    val choices = shortcutAssigner.assign(rawChoices)
    val newRevision = baseRevision.next()
    val document = PresentationDocument(
      revision = newRevision, blocks = listOf(
        PresentationBlock(
          id = choicesBlockId,
          content = BlockContent.Choices(choices = choices),
          title = "Available actions",
          region = PresentationRegion.MAIN
        )
      )
    )

    return PreparedPresentation(
      choices = choices, offered = offered, document = document
    )
  }

  fun prepareWithLocation(
    interactions: List<Interaction>,
    locationName: String?,
    movementDestinationName: String?,
    baseRevision: Revision = Revision.ZERO
  ): PreparedPresentation {
    val offered = interactions.map { interaction ->
      OfferedOption(
        choiceId = selectionIdFor(interaction), interaction = interaction
      )
    }

    val rawChoices = offered.map { option ->
      Choice(
        id = option.choiceId,
        text = option.interaction.label,
        shortcut = preferredShortcutsByInteractionId[option.interaction.id.value],
        enabled = true
      )
    }

    val choices = shortcutAssigner.assign(rawChoices)
    val newRevision = baseRevision.next()

    val contextBlocks = mutableListOf<PresentationBlock>()
    val contextText = when {
      movementDestinationName != null && locationName != null -> "Travelling from $locationName to $movementDestinationName."

      movementDestinationName != null -> "Travelling to $movementDestinationName."

      locationName != null -> "You are at $locationName."

      else -> null
    }

    if (contextText != null) {
      contextBlocks.add(
        PresentationBlock(
          id = BlockId("current_location"),
          content = BlockContent.Text(text = contextText, style = TextStyle.NORMAL),
          title = "Current location",
          region = PresentationRegion.MAIN
        )
      )
    }

    contextBlocks.add(
      PresentationBlock(
        id = choicesBlockId,
        content = BlockContent.Choices(choices = choices),
        title = "Available actions",
        region = PresentationRegion.MAIN
      )
    )

    val document = PresentationDocument(
      revision = newRevision, blocks = contextBlocks
    )

    return PreparedPresentation(
      choices = choices, offered = offered, document = document
    )
  }

  fun preparePrompt(promptText: String, baseRevision: Revision): PresentationDocument {
    val newRevision = baseRevision.next()
    return PresentationDocument(
      revision = newRevision, blocks = listOf(
        PresentationBlock(
          id = BlockId("prompt"),
          content = BlockContent.Text(text = promptText, style = TextStyle.HEADER),
          title = "Input required",
          region = PresentationRegion.MAIN
        )
      )
    )
  }

  fun toSnapshot(
    sessionId: SessionId, prepared: PreparedPresentation, sequence: Sequence = Sequence.ZERO
  ): PresentationSnapshot {
    return PresentationSnapshot(
      metadata = MessageMetadata(
        messageId = MessageId(UUID.randomUUID().toString()), sessionId = sessionId, sequence = sequence
      ), document = prepared.document
    )
  }

  private fun selectionIdFor(interaction: Interaction): String = "opt_${interaction.id.value}"
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

suspend fun advanceTimeAndDistributeAndRefreshContext(
  ticks: Long,
  registry: ConnectionRegistry,
  actionProcessor: GameActionProcessor,
  offerRegistry: SessionOfferRegistry,
  presentationPreparer: InteractionPresentationPreparer
): List<GameFact> {
  val audiences = actionProcessor.advanceTimeWithAudiences(ticks)

  distributeAudiences(audiences = audiences, registry = registry)

  val affectedCharacterIds = audiences.filter {
    it.fact.name == "character_movement_completed" || it.fact.name == "character_movement_interrupted"
  }.flatMap { it.characterIds }.toSet()

  for (characterId in affectedCharacterIds) {
    val connection = registry.findByCharacterId(characterId) ?: continue
    presentAvailableInteractions(
      connection = connection,
      actionProcessor = actionProcessor,
      offerRegistry = offerRegistry,
      preparer = presentationPreparer
    )
  }

  return audiences.map { it.fact }
}

suspend fun presentAvailableInteractions(
  connection: Connection,
  actionProcessor: GameActionProcessor,
  offerRegistry: SessionOfferRegistry,
  preparer: InteractionPresentationPreparer
) {
  val sessionId = connection.sessionId ?: return
  val characterId = connection.characterId ?: return

  val interactions = actionProcessor.availableInteractions(characterId)
  val locationName = actionProcessor.getCharacterLocationDisplayName(characterId)
  val movementDestinationName = actionProcessor.getCharacterMovementDestinationDisplayName(characterId)

  val offerState = offerRegistry.getOrCreate(sessionId)
  val prepared = preparer.prepareWithLocation(
    interactions = interactions,
    locationName = locationName,
    movementDestinationName = movementDestinationName,
    baseRevision = offerState.revision
  )

  offerState.replaceOffers(prepared.offered, prepared.document.revision)

  val snapshot = preparer.toSnapshot(sessionId, prepared)
  connection.send(snapshot)
}

suspend fun handleConnection(
  connection: Connection,
  registry: ConnectionRegistry,
  sessionLifecycleHandler: SessionLifecycleHandler = SessionLifecycleHandler(),
  structuralValidator: StructuralValidator = StructuralValidator(),
  protocolValidator: ProtocolValidator = ProtocolValidator(),
  actionProcessor: GameActionProcessor? = null,
  offerRegistry: SessionOfferRegistry = SessionOfferRegistry(),
  presentationPreparer: InteractionPresentationPreparer = InteractionPresentationPreparer(
    preferredShortcutsByInteractionId = DefaultPreferredInteractionShortcuts
  ),
  pendingRegistry: SessionPendingRegistry = SessionPendingRegistry()
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

          if (response is ServerWelcome && actionProcessor != null) {
            presentAvailableInteractions(
              connection = connection,
              actionProcessor = actionProcessor,
              offerRegistry = offerRegistry,
              preparer = presentationPreparer
            )
          }
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

        if (message is ClientSelection && actionProcessor != null) {
          val characterId = registry.findCharacterIdBySessionId(currentSessionId)

          if (characterId != null) {
            val offerState = offerRegistry.find(currentSessionId)
            val interaction = offerState?.findInteraction(message.choiceId)

            val correlationId = CorrelationId(message.metadata.messageId)

            val responseMessage = when {
              interaction == null -> {
                ServerRejected(
                  metadata = MessageMetadata(
                    messageId = MessageId(UUID.randomUUID().toString()),
                    sessionId = currentSessionId,
                    sequence = Sequence.ZERO,
                    correlationId = correlationId
                  ), reason = "Unknown or expired choice: ${message.choiceId}"
                )
              }

              interaction.needsAdditionalArguments -> {
                val pending = PendingInteraction(
                  interaction = interaction,
                  collectedArguments = mutableMapOf(),
                  remainingArguments = interaction.requiredArgumentNames.toMutableList()
                )
                pendingRegistry.set(currentSessionId, pending)

                val firstArg = pending.remainingArguments.first()
                val promptDoc =
                  presentationPreparer.preparePrompt("Enter value for $firstArg:", baseRevision = offerState.revision)
                offerState.replaceOffers(emptyList(), promptDoc.revision)

                val snapshot = PresentationSnapshot(
                  metadata = MessageMetadata(
                    messageId = MessageId(UUID.randomUUID().toString()),
                    sessionId = currentSessionId,
                    sequence = Sequence.ZERO
                  ), document = promptDoc
                )
                connection.send(snapshot)

                ServerAccepted(
                  metadata = MessageMetadata(
                    messageId = MessageId(UUID.randomUUID().toString()),
                    sessionId = currentSessionId,
                    sequence = Sequence.ZERO,
                    correlationId = correlationId
                  ), detail = "Interaction requires additional arguments. Please provide: $firstArg"
                )
              }

              interaction.isMenu -> {
                val locationName = actionProcessor.getCharacterLocationDisplayName(characterId)
                val movementDestinationName = actionProcessor.getCharacterMovementDestinationDisplayName(characterId)

                val preparedMenu = presentationPreparer.prepareWithLocation(
                  interactions = interaction.children,
                  locationName = locationName,
                  movementDestinationName = movementDestinationName,
                  baseRevision = offerState.revision
                )
                offerState.replaceOffers(preparedMenu.offered, preparedMenu.document.revision)

                val menuSnapshot = presentationPreparer.toSnapshot(
                  sessionId = currentSessionId, prepared = preparedMenu
                )
                connection.send(menuSnapshot)

                ServerAccepted(
                  metadata = MessageMetadata(
                    messageId = MessageId(UUID.randomUUID().toString()),
                    sessionId = currentSessionId,
                    sequence = Sequence.ZERO,
                    correlationId = correlationId
                  ), detail = "Menu '${interaction.label}' opened."
                )
              }

              interaction.actionName == null -> {
                ServerRejected(
                  metadata = MessageMetadata(
                    messageId = MessageId(UUID.randomUUID().toString()),
                    sessionId = currentSessionId,
                    sequence = Sequence.ZERO,
                    correlationId = correlationId
                  ), reason = "Interaction has no associated action"
                )
              }

              else -> {
                val gameAction = GameAction(
                  name = interaction.actionName!!, actorId = characterId, arguments = interaction.fixedArguments
                )

                val execution = actionProcessor.processWithAudiences(gameAction)
                when (val result = execution.result) {
                  is GameActionResult.Success -> {
                    val protocolFacts = result.facts.map { fact ->
                      ProtocolFact(
                        name = fact.name, arguments = fact.arguments.mapValues { (_, value) ->
                          value?.toString() ?: ""
                        })
                    }

                    ServerCompleted(
                      metadata = MessageMetadata(
                        messageId = MessageId(UUID.randomUUID().toString()),
                        sessionId = currentSessionId,
                        sequence = Sequence.ZERO,
                        correlationId = correlationId
                      ), facts = protocolFacts
                    ).also {
                      distributeAudiences(
                        audiences = execution.factAudiences, registry = registry, excludeCharacterId = characterId
                      )
                      presentAvailableInteractions(
                        connection = connection,
                        actionProcessor = actionProcessor,
                        offerRegistry = offerRegistry,
                        preparer = presentationPreparer
                      )
                    }
                  }

                  is GameActionResult.Rejected -> {
                    ServerRejected(
                      metadata = MessageMetadata(
                        messageId = MessageId(UUID.randomUUID().toString()),
                        sessionId = currentSessionId,
                        sequence = Sequence.ZERO,
                        correlationId = correlationId
                      ), reason = result.reason
                    )
                  }
                }
              }
            }

            connection.send(responseMessage)
          }
        }

        if (message is ClientTextCommand && actionProcessor != null) {
          val characterId = registry.findCharacterIdBySessionId(currentSessionId)
          if (characterId != null) {
            val pending = pendingRegistry.find(currentSessionId)
            val correlationId = CorrelationId(message.metadata.messageId)

            val responseMessage = if (pending == null) {
              ServerRejected(
                metadata = MessageMetadata(
                  messageId = MessageId(UUID.randomUUID().toString()),
                  sessionId = currentSessionId,
                  sequence = Sequence.ZERO,
                  correlationId = correlationId
                ), reason = "No pending interaction awaiting input"
              )
            } else {
              val currentArgName = pending.remainingArguments.firstOrNull()
              if (currentArgName == null) {
                pendingRegistry.remove(currentSessionId)
                ServerRejected(
                  metadata = MessageMetadata(
                    messageId = MessageId(UUID.randomUUID().toString()),
                    sessionId = currentSessionId,
                    sequence = Sequence.ZERO,
                    correlationId = correlationId
                  ), reason = "No argument expected"
                )
              } else {
                val inputVal = message.rawText.trim()
                if (inputVal.isBlank()) {
                  ServerRejected(
                    metadata = MessageMetadata(
                      messageId = MessageId(UUID.randomUUID().toString()),
                      sessionId = currentSessionId,
                      sequence = Sequence.ZERO,
                      correlationId = correlationId
                    ), reason = "Argument cannot be blank"
                  )
                } else {
                  pending.collectedArguments[currentArgName] = inputVal
                  pending.remainingArguments.removeAt(0)

                  if (pending.remainingArguments.isNotEmpty()) {
                    val nextArg = pending.remainingArguments.first()
                    val offerState = offerRegistry.getOrCreate(currentSessionId)
                    val promptDoc = presentationPreparer.preparePrompt(
                      "Enter value for $nextArg:", baseRevision = offerState.revision
                    )
                    offerState.replaceOffers(emptyList(), promptDoc.revision)

                    val snapshot = PresentationSnapshot(
                      metadata = MessageMetadata(
                        messageId = MessageId(UUID.randomUUID().toString()),
                        sessionId = currentSessionId,
                        sequence = Sequence.ZERO
                      ), document = promptDoc
                    )
                    connection.send(snapshot)

                    ServerAccepted(
                      metadata = MessageMetadata(
                        messageId = MessageId(UUID.randomUUID().toString()),
                        sessionId = currentSessionId,
                        sequence = Sequence.ZERO,
                        correlationId = correlationId
                      ), detail = "Argument accepted. Next: $nextArg"
                    )
                  } else {
                    pendingRegistry.remove(currentSessionId)
                    val finalArgs = pending.interaction.fixedArguments + pending.collectedArguments
                    val gameAction = GameAction(
                      name = pending.interaction.actionName!!, actorId = characterId, arguments = finalArgs
                    )

                    val execution = actionProcessor.processWithAudiences(gameAction)
                    when (val result = execution.result) {
                      is GameActionResult.Success -> {
                        val protocolFacts = result.facts.map { fact ->
                          ProtocolFact(
                            name = fact.name, arguments = fact.arguments.mapValues { (_, value) ->
                              value?.toString() ?: ""
                            })
                        }

                        ServerCompleted(
                          metadata = MessageMetadata(
                            messageId = MessageId(UUID.randomUUID().toString()),
                            sessionId = currentSessionId,
                            sequence = Sequence.ZERO,
                            correlationId = correlationId
                          ), facts = protocolFacts
                        ).also {
                          distributeAudiences(
                            audiences = execution.factAudiences, registry = registry, excludeCharacterId = characterId
                          )
                          presentAvailableInteractions(
                            connection = connection,
                            actionProcessor = actionProcessor,
                            offerRegistry = offerRegistry,
                            preparer = presentationPreparer
                          )
                        }
                      }

                      is GameActionResult.Rejected -> {
                        ServerRejected(
                          metadata = MessageMetadata(
                            messageId = MessageId(UUID.randomUUID().toString()),
                            sessionId = currentSessionId,
                            sequence = Sequence.ZERO,
                            correlationId = correlationId
                          ), reason = result.reason
                        ).also {
                          presentAvailableInteractions(
                            connection = connection,
                            actionProcessor = actionProcessor,
                            offerRegistry = offerRegistry,
                            preparer = presentationPreparer
                          )
                        }
                      }
                    }
                  }
                }
              }
            }
            connection.send(responseMessage)
          }
        }

        if (message is ClientCancel && actionProcessor != null) {
          pendingRegistry.remove(currentSessionId)
          val correlationId = CorrelationId(message.metadata.messageId)
          val response = ServerAccepted(
            metadata = MessageMetadata(
              messageId = MessageId(UUID.randomUUID().toString()),
              sessionId = currentSessionId,
              sequence = Sequence.ZERO,
              correlationId = correlationId
            ), detail = "Cancelled pending interaction"
          )
          connection.send(response)
          presentAvailableInteractions(
            connection = connection,
            actionProcessor = actionProcessor,
            offerRegistry = offerRegistry,
            preparer = presentationPreparer
          )
        }

        if (message is ClientAction && actionProcessor != null) {
          // pass
        }
      }
    }
  } catch (_: Exception) {
    // Connection failures terminate this connection.
  } finally {
    val sessionId = connection.sessionId
    val characterId = connection.characterId

    runCatching {
      connection.close()
    }

    sessionId?.let {
      offerRegistry.remove(it)
      pendingRegistry.remove(it)
    }

    registry.unregister(connection.id)

    if (characterId != null) {
      actionProcessor?.nucleus?.unregisterCharacter(characterId)
    }

    connection.characterId = null
    connection.sessionId = null
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
  actionProcessor: GameActionProcessor? = null,
  offerRegistry: SessionOfferRegistry = SessionOfferRegistry(),
  presentationPreparer: InteractionPresentationPreparer = InteractionPresentationPreparer(
    preferredShortcutsByInteractionId = DefaultPreferredInteractionShortcuts
  ),
  pendingRegistry: SessionPendingRegistry = SessionPendingRegistry()
) {
  val activeActionProcessor = actionProcessor ?: GameActionProcessor(
    Nucleus(NucleusId("main_nucleus"))
  )

  install(WebSockets)

  launch {
    while (isActive) {
      delay(SIMULATION_TICK_INTERVAL_MS)
      advanceTimeAndDistributeAndRefreshContext(
        ticks = SIMULATION_TICKS_PER_INTERVAL,
        registry = connectionRegistry,
        actionProcessor = activeActionProcessor,
        offerRegistry = offerRegistry,
        presentationPreparer = presentationPreparer
      )
    }
  }

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
        actionProcessor = activeActionProcessor,
        offerRegistry = offerRegistry,
        presentationPreparer = presentationPreparer,
        pendingRegistry = pendingRegistry
      )
    }
  }
}