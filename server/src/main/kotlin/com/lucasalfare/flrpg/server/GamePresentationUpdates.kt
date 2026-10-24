package com.lucasalfare.flrpg.server

import com.lucasalfare.flrpg.gamedomain.ActivityId
import com.lucasalfare.flrpg.gamedomain.GameActionProcessor
import com.lucasalfare.flrpg.gamedomain.GameFact
import kotlinx.coroutines.sync.withLock

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
  correlationRegistry: OperationCorrelationRegistry,
  pendingRegistry: SessionPendingRegistry
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
    val sessionId = connection.sessionId ?: continue
    val hasActiveActivities = actionProcessor.nucleus.getActiveActivitiesForCharacter(characterId).isNotEmpty()
    if (hasActiveActivities || characterId in affectedCharacterIds) {
      val pending = pendingRegistry.find(sessionId)
      if (pending != null) {
        val currentArgName = pending.remainingArguments.firstOrNull()
        if (currentArgName != null) {
          val locationName = actionProcessor.getCharacterLocationDisplayName(characterId)
          val movementDestinationName = actionProcessor.getCharacterMovementDestinationDisplayName(characterId)
          val activityProgressDetails = actionProcessor.nucleus.getActiveActivitiesProgressDetails(characterId)
          val cancelInteractions =
            actionProcessor.availableInteractions(characterId).filter { it.actionName == "cancel_activity" }
          val explorationResult = actionProcessor.currentExplorationResult(characterId)
          val offerState = offerRegistry.getOrCreate(sessionId)
          offerState.updateMutex.withLock {
            val preparedPrompt = presentationPreparer.preparePromptWithLocation(
              promptText = "Enter value for $currentArgName:",
              locationName = locationName,
              movementDestinationName = movementDestinationName,
              activityProgressDetails = activityProgressDetails,
              cancelInteractions = cancelInteractions,
              explorationResult = explorationResult,
              baseRevision = offerState.revision,
              existingShortcuts = offerState.getPersistentShortcuts()
            )
            offerState.replaceOffers(preparedPrompt.offered, preparedPrompt.document.revision, preparedPrompt.choices)
            val snapshot = presentationPreparer.toSnapshot(sessionId, preparedPrompt)
            connection.send(snapshot)
          }
        }
      } else {
        presentAvailableInteractions(
          connection = connection,
          actionProcessor = actionProcessor,
          offerRegistry = offerRegistry,
          preparer = presentationPreparer
        )
      }
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
  val explorationResult = actionProcessor.currentExplorationResult(characterId)

  val offerState = offerRegistry.getOrCreate(sessionId)
  offerState.updateMutex.withLock {
    val existingShortcuts = offerState.getPersistentShortcuts()
    val prepared = preparer.prepareWithLocation(
      interactions = interactions,
      locationName = locationName,
      movementDestinationName = movementDestinationName,
      activityProgressDetails = activityProgressDetails,
      explorationResult = explorationResult,
      baseRevision = offerState.revision,
      existingShortcuts = existingShortcuts
    )

    offerState.replaceOffers(prepared.offered, prepared.document.revision, prepared.choices)

    val snapshot = preparer.toSnapshot(sessionId, prepared)
    connection.send(snapshot)
  }
}

private fun isTerminalActivityLifecycleFact(name: String): Boolean =
  name == "activity_completed" || name == "activity_cancelled" || name == "activity_interrupted" || name == "activity_failed"