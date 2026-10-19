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

private const val SIMULATION_TICK_INTERVAL_MS: Long = 1_000 * 1L
private const val SIMULATION_TICKS_PER_INTERVAL: Long = 1L

private val DefaultPreferredInteractionShortcuts: Map<String, String> = mapOf(
  "move" to "M"
)

private fun isTerminalActivityLifecycleFact(name: String): Boolean =
  name == "activity_completed" || name == "activity_cancelled" || name == "activity_interrupted" || name == "activity_failed"

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

class OperationCorrelationRegistry {
  private val messageToActivityMap = ConcurrentHashMap<String, ActivityId>()
  private val activityToSessionMap = ConcurrentHashMap<ActivityId, SessionId>()

  fun register(messageId: MessageId, activityId: ActivityId, sessionId: SessionId) {
    messageToActivityMap[messageId.value] = activityId
    activityToSessionMap[activityId] = sessionId
  }

  fun findActivityId(messageId: MessageId): ActivityId? = messageToActivityMap[messageId.value]

  fun findSessionId(activityId: ActivityId): SessionId? = activityToSessionMap[activityId]

  fun removeByActivityId(activityId: ActivityId) {
    val sessionId = activityToSessionMap.remove(activityId)
    if (sessionId != null) {
      messageToActivityMap.entries.removeAll { it.value == activityId }
    }
  }

  fun removeBySessionId(sessionId: SessionId) {
    val removedActivities = activityToSessionMap.filterValues { sessionId == it }.keys
    for (activityId in removedActivities) {
      activityToSessionMap.remove(activityId)
    }
    messageToActivityMap.entries.removeAll { it.value in removedActivities }
  }
}

class SessionInteractionOperationRegistry {
  private val map = ConcurrentHashMap<String, MessageId>()

  fun set(sessionId: SessionId, messageId: MessageId) {
    map[sessionId.value] = messageId
  }

  fun find(sessionId: SessionId): MessageId? = map[sessionId.value]

  fun remove(sessionId: SessionId) {
    map.remove(sessionId.value)
  }
}

interface ShortcutAssigner {
  fun assign(choices: List<Choice>, existingShortcuts: Map<String, String> = emptyMap()): List<Choice>
}

class SequentialLetterShortcutAssigner : ShortcutAssigner {

  override fun assign(choices: List<Choice>, existingShortcuts: Map<String, String>): List<Choice> {
    if (choices.isEmpty()) return choices

    val used = mutableSetOf<String>()
    val preAssigned = mutableMapOf<String, String>()

    for (choice in choices) {
      val existing = choice.shortcut ?: existingShortcuts[choice.id]
      if (existing != null && choice.enabled) {
        require(used.add(existing)) {
          "Conflicting shortcut '$existing' among simultaneously selectable choices."
        }
        preAssigned[choice.id] = existing
      }
    }

    var nextIndex = 0
    return choices.map { choice ->
      val shortcut = preAssigned[choice.id] ?: run {
        val assigned = nextAvailable(used, nextIndex)
        nextIndex = assigned.second
        used.add(assigned.first)
        assigned.first
      }
      choice.copy(shortcut = shortcut)
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

  override fun assign(choices: List<Choice>, existingShortcuts: Map<String, String>): List<Choice> {
    val combined = existingShortcuts + shortcutsByChoiceId
    val result = choices.map { choice ->
      val mapped = combined[choice.id] ?: choice.shortcut
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
  val updateMutex = Mutex()

  @Volatile
  var revision: Revision = Revision.ZERO
    private set

  private val offeredByChoiceId = ConcurrentHashMap<String, Interaction>()
  private val persistentShortcuts = ConcurrentHashMap<String, String>()

  fun replaceOffers(offered: List<OfferedOption>, newRevision: Revision, choices: List<Choice> = emptyList()) {
    offeredByChoiceId.clear()
    for (option in offered) {
      offeredByChoiceId[option.choiceId] = option.interaction
    }
    if (choices.isNotEmpty()) {
      persistentShortcuts.clear()
      for (choice in choices) {
        if (choice.shortcut != null) {
          persistentShortcuts[choice.id] = choice.shortcut!!
        }
      }
    }
    revision = newRevision
  }

  fun findInteraction(choiceId: String): Interaction? = offeredByChoiceId[choiceId]

  fun clear() {
    offeredByChoiceId.clear()
    persistentShortcuts.clear()
    revision = Revision.ZERO
  }

  fun offeredSnapshot(): Map<String, Interaction> = offeredByChoiceId.toMap()

  fun getPersistentShortcuts(): Map<String, String> = persistentShortcuts.toMap()
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

  fun prepare(
    interactions: List<Interaction>,
    baseRevision: Revision = Revision.ZERO,
    existingShortcuts: Map<String, String> = emptyMap()
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
        shortcut = preferredShortcutsByInteractionId[option.interaction.id.value] ?: existingShortcuts[option.choiceId],
        enabled = true
      )
    }

    val choices = shortcutAssigner.assign(rawChoices, existingShortcuts)
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
    activityProgressDetails: List<ActivityProgressDetails> = emptyList(),
    baseRevision: Revision = Revision.ZERO,
    existingShortcuts: Map<String, String> = emptyMap()
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
        shortcut = preferredShortcutsByInteractionId[option.interaction.id.value] ?: existingShortcuts[option.choiceId],
        enabled = true
      )
    }

    val choices = shortcutAssigner.assign(rawChoices, existingShortcuts)
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

    if (activityProgressDetails.isNotEmpty()) {
      val protocolActivities = activityProgressDetails.map { details ->
        val cancelOption = offered.firstOrNull { option ->
          option.interaction.actionName == "cancel_activity" && option.interaction.fixedArguments["activityId"] == details.activityId.value
        }
        val matchingChoice = cancelOption?.let { opt -> choices.firstOrNull { it.id == opt.choiceId } }

        ProtocolActivity(
          activityId = details.activityId.value,
          description = details.description,
          status = "RUNNING",
          mode = details.mode.name,
          progress = details.progress,
          remainingTimeSeconds = details.remainingTime?.value,
          elapsedTimeSeconds = details.elapsedTime.value,
          cancellable = cancelOption != null,
          cancelChoiceId = cancelOption?.choiceId,
          cancelShortcut = matchingChoice?.shortcut
        )
      }

      contextBlocks.add(
        PresentationBlock(
          id = BlockId("activities"),
          content = BlockContent.Activities(activities = protocolActivities),
          title = "Current activities",
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

suspend fun handleGameActionExecution(
  execution: GameActionExecution,
  connection: Connection,
  registry: ConnectionRegistry,
  actionProcessor: GameActionProcessor,
  offerRegistry: SessionOfferRegistry,
  presentationPreparer: InteractionPresentationPreparer,
  correlationRegistry: OperationCorrelationRegistry,
  originatingMessageId: MessageId,
  sessionId: SessionId,
  characterId: CharacterId,
  correlationId: CorrelationId
): ServerMessage {
  return when (val result = execution.result) {
    is GameActionResult.Success -> {
      val protocolFacts = result.facts.map { fact ->
        ProtocolFact(
          name = fact.name, arguments = fact.arguments.mapValues { (_, value) -> value?.toString() ?: "" })
      }
      ServerCompleted(
        metadata = MessageMetadata(
          messageId = MessageId(UUID.randomUUID().toString()),
          sessionId = sessionId,
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

    is GameActionResult.ActivityStarted -> {
      correlationRegistry.register(originatingMessageId, result.activityId, sessionId)
      distributeAudiences(
        audiences = execution.factAudiences, registry = registry, excludeCharacterId = characterId
      )
      presentAvailableInteractions(
        connection = connection,
        actionProcessor = actionProcessor,
        offerRegistry = offerRegistry,
        preparer = presentationPreparer
      )
      ServerAccepted(
        metadata = MessageMetadata(
          messageId = MessageId(UUID.randomUUID().toString()),
          sessionId = sessionId,
          sequence = Sequence.ZERO,
          correlationId = correlationId
        ), detail = "Activity started successfully."
      )
    }

    is GameActionResult.Rejected -> {
      ServerRejected(
        metadata = MessageMetadata(
          messageId = MessageId(UUID.randomUUID().toString()),
          sessionId = sessionId,
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
  presentationPreparer: InteractionPresentationPreparer,
  correlationRegistry: OperationCorrelationRegistry
): List<GameFact> {
  val audiences = actionProcessor.advanceTimeWithAudiences(ticks)

  distributeAudiences(audiences = audiences, registry = registry)

  for (audience in audiences) {
    val fact = audience.fact
    val activityIdStr = fact.arguments["activityId"] as? String
    if (activityIdStr != null && isTerminalActivityLifecycleFact(fact.name)) {
      correlationRegistry.removeByActivityId(ActivityId(activityIdStr))
    }
  }

  val affectedCharacterIds =
    audiences.filter { isTerminalActivityLifecycleFact(it.fact.name) || it.fact.name.startsWith("character_movement_") }
      .flatMap { it.characterIds }.toSet()

  for (connection in registry.activeConnections()) {
    val characterId = connection.characterId ?: continue
    val hasActiveActivities = actionProcessor.nucleus.getActiveActivitiesForCharacter(characterId).isNotEmpty()
    if (hasActiveActivities || characterId in affectedCharacterIds) {
      presentAvailableInteractions(
        connection = connection,
        actionProcessor = actionProcessor,
        offerRegistry = offerRegistry,
        preparer = presentationPreparer
      )
    }
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
  val activityProgressDetails = actionProcessor.nucleus.getActiveActivitiesProgressDetails(characterId)

  val offerState = offerRegistry.getOrCreate(sessionId)
  offerState.updateMutex.withLock {
    val existingShortcuts = offerState.getPersistentShortcuts()
    val prepared = preparer.prepareWithLocation(
      interactions = interactions,
      locationName = locationName,
      movementDestinationName = movementDestinationName,
      activityProgressDetails = activityProgressDetails,
      baseRevision = offerState.revision,
      existingShortcuts = existingShortcuts
    )

    offerState.replaceOffers(prepared.offered, prepared.document.revision, prepared.choices)

    val snapshot = preparer.toSnapshot(sessionId, prepared)
    connection.send(snapshot)
  }
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
  pendingRegistry: SessionPendingRegistry = SessionPendingRegistry(),
  correlationRegistry: OperationCorrelationRegistry = OperationCorrelationRegistry(),
  interactionOperationRegistry: SessionInteractionOperationRegistry = SessionInteractionOperationRegistry()
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
                interactionOperationRegistry.set(currentSessionId, message.metadata.messageId)

                val firstArg = pending.remainingArguments.first()
                offerState.updateMutex.withLock {
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
                }

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
                val activityProgressDetails = actionProcessor.nucleus.getActiveActivitiesProgressDetails(characterId)

                offerState.updateMutex.withLock {
                  val preparedMenu = presentationPreparer.prepareWithLocation(
                    interactions = interaction.children,
                    locationName = locationName,
                    movementDestinationName = movementDestinationName,
                    activityProgressDetails = activityProgressDetails,
                    baseRevision = offerState.revision,
                    existingShortcuts = offerState.getPersistentShortcuts()
                  )
                  offerState.replaceOffers(preparedMenu.offered, preparedMenu.document.revision, preparedMenu.choices)

                  val menuSnapshot = presentationPreparer.toSnapshot(
                    sessionId = currentSessionId, prepared = preparedMenu
                  )
                  connection.send(menuSnapshot)
                }

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
                handleGameActionExecution(
                  execution = execution,
                  connection = connection,
                  registry = registry,
                  actionProcessor = actionProcessor,
                  offerRegistry = offerRegistry,
                  presentationPreparer = presentationPreparer,
                  correlationRegistry = correlationRegistry,
                  originatingMessageId = message.metadata.messageId,
                  sessionId = currentSessionId,
                  characterId = characterId,
                  correlationId = correlationId
                )
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
                interactionOperationRegistry.remove(currentSessionId)
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
                    offerState.updateMutex.withLock {
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
                    }

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
                    interactionOperationRegistry.remove(currentSessionId)
                    val finalArgs = pending.interaction.fixedArguments + pending.collectedArguments
                    val gameAction = GameAction(
                      name = pending.interaction.actionName!!, actorId = characterId, arguments = finalArgs
                    )

                    val execution = actionProcessor.processWithAudiences(gameAction)
                    handleGameActionExecution(
                      execution = execution,
                      connection = connection,
                      registry = registry,
                      actionProcessor = actionProcessor,
                      offerRegistry = offerRegistry,
                      presentationPreparer = presentationPreparer,
                      correlationRegistry = correlationRegistry,
                      originatingMessageId = message.metadata.messageId,
                      sessionId = currentSessionId,
                      characterId = characterId,
                      correlationId = correlationId
                    )
                  }
                }
              }
            }
            connection.send(responseMessage)
          }
        }

        if (message is ClientCancel && actionProcessor != null) {
          val correlationId = CorrelationId(message.metadata.messageId)
          val targetMsgId = message.targetMessageId

          val responseMessage = run {
            val pendingMsgId = interactionOperationRegistry.find(currentSessionId)
            if (pendingMsgId != null && pendingMsgId == targetMsgId) {
              pendingRegistry.remove(currentSessionId)
              interactionOperationRegistry.remove(currentSessionId)
              ServerAccepted(
                metadata = MessageMetadata(
                  messageId = MessageId(UUID.randomUUID().toString()),
                  sessionId = currentSessionId,
                  sequence = Sequence.ZERO,
                  correlationId = correlationId
                ), detail = "Cancelled pending interaction successfully"
              )
            } else {
              val targetActivityId = correlationRegistry.findActivityId(targetMsgId)
              if (targetActivityId != null) {
                val characterId = registry.findCharacterIdBySessionId(currentSessionId)
                if (characterId != null) {
                  when (val cancelResult = actionProcessor.nucleus.cancelActivity(characterId, targetActivityId)) {
                    is GameActionResult.Success -> {
                      correlationRegistry.removeByActivityId(targetActivityId)
                      val protocolFacts = cancelResult.facts.map { fact ->
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
                        distributeFacts(cancelResult.facts, registry, actionProcessor, excludeCharacterId = characterId)
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
                        ), reason = cancelResult.reason
                      )
                    }

                    else -> {
                      ServerRejected(
                        metadata = MessageMetadata(
                          messageId = MessageId(UUID.randomUUID().toString()),
                          sessionId = currentSessionId,
                          sequence = Sequence.ZERO,
                          correlationId = correlationId
                        ), reason = "Could not cancel activity."
                      )
                    }
                  }
                } else {
                  ServerRejected(
                    metadata = MessageMetadata(
                      messageId = MessageId(UUID.randomUUID().toString()),
                      sessionId = currentSessionId,
                      sequence = Sequence.ZERO,
                      correlationId = correlationId
                    ), reason = "Character not bound to session."
                  )
                }
              } else {
                ServerRejected(
                  metadata = MessageMetadata(
                    messageId = MessageId(UUID.randomUUID().toString()),
                    sessionId = currentSessionId,
                    sequence = Sequence.ZERO,
                    correlationId = correlationId
                  ), reason = "Unknown or foreign targetMessageId for cancellation."
                )
              }
            }
          }

          connection.send(responseMessage)
          if (responseMessage is ServerCompleted) {
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
  } catch (_: Exception) {
    // Connection failures terminate this connection.
  } finally {
    val sessionId = connection.sessionId
    val characterId = connection.characterId

    runCatching {
      connection.close()
    }

    if (characterId != null && actionProcessor != null) {
      val activeActivities = actionProcessor.nucleus.getActiveActivitiesForCharacter(characterId)
      for (activeActivity in activeActivities) {
        val cancelResult = actionProcessor.nucleus.cancelActivity(characterId, activeActivity.id)
        if (cancelResult is GameActionResult.Success) {
          runCatching {
            distributeFacts(cancelResult.facts, registry, actionProcessor, excludeCharacterId = characterId)
          }
        }
      }
    }

    sessionId?.let {
      offerRegistry.remove(it)
      pendingRegistry.remove(it)
      interactionOperationRegistry.remove(it)
      correlationRegistry.removeBySessionId(it)
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
  pendingRegistry: SessionPendingRegistry = SessionPendingRegistry(),
  correlationRegistry: OperationCorrelationRegistry = OperationCorrelationRegistry(),
  interactionOperationRegistry: SessionInteractionOperationRegistry = SessionInteractionOperationRegistry()
) {
  val activeActionProcessor = actionProcessor ?: GameActionProcessor(
    buildDevelopmentNucleus(NucleusId("main_nucleus"))
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
        presentationPreparer = presentationPreparer,
        correlationRegistry = correlationRegistry
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
        pendingRegistry = pendingRegistry,
        correlationRegistry = correlationRegistry,
        interactionOperationRegistry = interactionOperationRegistry
      )
    }
  }
}