package com.lucasalfare.flrpg.gamedomain

import com.lucasalfare.flworld.WorldDuration
import com.lucasalfare.flworld.WorldEntityId
import com.lucasalfare.flworld.WorldLocationId

class GameActionProcessor(
  val nucleus: Nucleus,
  handlers: List<GameActionHandler> = defaultHandlers(),
  private val interactionProvider: InteractionProvider = DefaultInteractionProvider()
) {
  private val handlerRegistry: Map<String, GameActionHandler> = buildMap {
    for (handler in handlers) {
      require(!containsKey(handler.actionName)) {
        "Duplicate handler registered for action: ${handler.actionName}"
      }
      put(handler.actionName, handler)
    }
  }

  companion object {
    fun defaultHandlers(): List<GameActionHandler> = listOf(
      EnterWorldHandler(), MoveHandler(), CancelActivityHandler()
    )
  }

  fun getCharacterMovementProgressDetails(characterId: CharacterId): Triple<String, Double, SimulationSeconds?>? =
    synchronized(nucleus.stateLock) {
      val movementActivity =
        nucleus.getActiveActivitiesForCharacter(characterId).firstOrNull { it.type == ActivityType.MOVEMENT }
          ?: return@synchronized null
      val details = nucleus.getActivityProgressDetails(movementActivity.id) ?: return@synchronized null
      val progress = details.progress ?: return@synchronized null
      Triple(details.description, progress, details.remainingTime)
    }

  fun getCharacterLocationDisplayName(characterId: CharacterId): String? = synchronized(nucleus.stateLock) {
    val entityId = WorldEntityId(characterId.value)
    val locationId = nucleus.world.state.getLocation(entityId) ?: return@synchronized null
    nucleus.getLocationDisplayName(locationId) ?: locationId.value
  }

  fun getCharacterMovementDestinationDisplayName(characterId: CharacterId): String? = synchronized(nucleus.stateLock) {
    val entityId = WorldEntityId(characterId.value)
    val movement = nucleus.world.getMovement(entityId) ?: return@synchronized null
    if (!movement.isInProgress) return@synchronized null
    nucleus.getLocationDisplayName(movement.destination) ?: movement.destination.value
  }

  fun availableInteractions(characterId: CharacterId): List<Interaction> = synchronized(nucleus.stateLock) {
    interactionProvider.availableInteractions(characterId, nucleus)
  }

  fun process(action: GameAction): GameActionResult = synchronized(nucleus.stateLock) {
    processLocked(action)
  }

  fun processWithAudiences(
    action: GameAction
  ): GameActionExecution = synchronized(nucleus.stateLock) {
    val result = processLocked(action)

    GameActionExecution(
      result = result, factAudiences = findFactAudiencesLocked(result.facts)
    )
  }

  fun advanceTime(ticks: Long): List<GameFact> = synchronized(nucleus.stateLock) {
    advanceTimeLocked(ticks)
  }

  fun advanceTimeWithAudiences(
    ticks: Long
  ): List<GameFactAudience> = synchronized(nucleus.stateLock) {
    val facts = advanceTimeLocked(ticks)
    findFactAudiencesLocked(facts)
  }

  fun findInterestedCharacters(
    fact: GameFact
  ): List<CharacterId> = synchronized(nucleus.stateLock) {
    findInterestedCharactersLocked(fact)
  }

  fun findFactAudiences(
    facts: List<GameFact>
  ): List<GameFactAudience> = synchronized(nucleus.stateLock) {
    findFactAudiencesLocked(facts)
  }

  private fun processLocked(
    action: GameAction
  ): GameActionResult {
    if (nucleus.hasBlockingActivity(action.actorId) && action.name != "cancel_activity") {
      val blocking = nucleus.getActiveActivityForCharacter(action.actorId)
      return GameActionResult.Rejected(
        "Character '${action.actorId.value}' is busy with a blocking activity: ${blocking?.description ?: "unknown"}"
      )
    }

    val handler = handlerRegistry[action.name]
    if (handler != null) {
      return handler.execute(action, nucleus)
    }
    return GameActionResult.Rejected(
      "Unknown action: ${action.name}"
    )
  }

  private fun advanceTimeLocked(
    ticks: Long
  ): List<GameFact> {
    val activeActivities = nucleus.characters.flatMap { characterId ->
      nucleus.getActiveActivitiesForCharacter(characterId)
    }

    nucleus.world.advance(WorldDuration(ticks))

    val currentSimulationTime = SimulationSeconds(nucleus.world.clock.currentInstant.value)
    val facts = mutableListOf<GameFact>()

    for (activity in activeActivities) {
      val current = nucleus.getActivity(activity.id) ?: continue
      if (current.isTerminal) continue

      if (current.duration.isUnknown) {
        val handler = nucleus.getActivityHandler(current.type)
        if (handler != null) {
          facts.addAll(handler.progress(current, nucleus))
        }
        continue
      }

      val handler = nucleus.getActivityHandler(current.type) ?: continue
      val progress = nucleus.getActivityProgress(current.id, currentSimulationTime)

      val specificFacts = if (progress >= 1.0) {
        handler.complete(current, nucleus)
      } else {
        handler.progress(current, nucleus)
      }
      facts.addAll(specificFacts)

      val after = nucleus.getActivity(current.id) ?: continue
      if (after.isTerminal) {
        when (after.status) {
          ActivityStatus.COMPLETED -> facts.add(activityLifecycleFact("activity_completed", after, nucleus))
          ActivityStatus.INTERRUPTED -> facts.add(activityLifecycleFact("activity_interrupted", after, nucleus))
          ActivityStatus.FAILED -> facts.add(activityLifecycleFact("activity_failed", after, nucleus))
          ActivityStatus.CANCELLED -> facts.add(activityLifecycleFact("activity_cancelled", after, nucleus))
          else -> Unit
        }
      } else if (progress >= 1.0) {
        nucleus.transitionActivityStatus(current.id, ActivityStatus.COMPLETED)
        val completed = nucleus.getActivity(current.id) ?: current.copy(status = ActivityStatus.COMPLETED)
        facts.add(activityLifecycleFact("activity_completed", completed, nucleus))
      }
    }

    return facts
  }

  private fun findInterestedCharactersLocked(
    fact: GameFact
  ): List<CharacterId> {
    val locationIdValue = fact.arguments["locationId"] as? String ?: return emptyList()

    return nucleus.getCharactersAtLocation(
      WorldLocationId(locationIdValue)
    )
  }

  private fun findFactAudiencesLocked(
    facts: List<GameFact>
  ): List<GameFactAudience> = facts.map { fact ->
    GameFactAudience(
      fact = fact, characterIds = findInterestedCharactersLocked(fact)
    )
  }
}