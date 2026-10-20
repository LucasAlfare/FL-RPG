package com.lucasalfare.flrpg.server

import com.lucasalfare.flrpg.communicationprotocol.*
import com.lucasalfare.flrpg.gamedomain.CharacterId
import com.lucasalfare.flrpg.gamedomain.GameAction
import com.lucasalfare.flrpg.gamedomain.GameActionProcessor
import com.lucasalfare.flrpg.gamedomain.GameActionResult
import kotlinx.coroutines.sync.withLock
import java.util.*

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
                val locationName = actionProcessor.getCharacterLocationDisplayName(characterId)
                val movementDestinationName = actionProcessor.getCharacterMovementDestinationDisplayName(characterId)
                val activityProgressDetails = actionProcessor.nucleus.getActiveActivitiesProgressDetails(characterId)
                val cancelInteractions =
                  actionProcessor.availableInteractions(characterId).filter { it.actionName == "cancel_activity" }

                offerState.updateMutex.withLock {
                  val preparedPrompt = presentationPreparer.preparePromptWithLocation(
                    promptText = "Enter value for $firstArg:",
                    locationName = locationName,
                    movementDestinationName = movementDestinationName,
                    activityProgressDetails = activityProgressDetails,
                    cancelInteractions = cancelInteractions,
                    baseRevision = offerState.revision,
                    existingShortcuts = offerState.getPersistentShortcuts()
                  )
                  offerState.replaceOffers(
                    preparedPrompt.offered, preparedPrompt.document.revision, preparedPrompt.choices
                  )

                  val snapshot = presentationPreparer.toSnapshot(
                    sessionId = currentSessionId, prepared = preparedPrompt
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
                  correlationId = correlationId,
                  gameActionName = interaction.actionName
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
                    val locationName = actionProcessor.getCharacterLocationDisplayName(characterId)
                    val movementDestinationName =
                      actionProcessor.getCharacterMovementDestinationDisplayName(characterId)
                    val activityProgressDetails =
                      actionProcessor.nucleus.getActiveActivitiesProgressDetails(characterId)
                    val cancelInteractions =
                      actionProcessor.availableInteractions(characterId).filter { it.actionName == "cancel_activity" }

                    val offerState = offerRegistry.getOrCreate(currentSessionId)
                    offerState.updateMutex.withLock {
                      val preparedPrompt = presentationPreparer.preparePromptWithLocation(
                        promptText = "Enter value for $nextArg:",
                        locationName = locationName,
                        movementDestinationName = movementDestinationName,
                        activityProgressDetails = activityProgressDetails,
                        cancelInteractions = cancelInteractions,
                        baseRevision = offerState.revision,
                        existingShortcuts = offerState.getPersistentShortcuts()
                      )
                      offerState.replaceOffers(
                        preparedPrompt.offered, preparedPrompt.document.revision, preparedPrompt.choices
                      )

                      val snapshot = presentationPreparer.toSnapshot(
                        sessionId = currentSessionId, prepared = preparedPrompt
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
                      correlationId = correlationId,
                      gameActionName = pending.interaction.actionName
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
                  val gameAction = GameAction(
                    name = "cancel_activity",
                    actorId = characterId,
                    arguments = mapOf("activityId" to targetActivityId.value)
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
                    correlationId = correlationId,
                    gameActionName = "cancel_activity"
                  )
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