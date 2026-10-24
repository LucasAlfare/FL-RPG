package com.lucasalfare.flrpg.gamedomain

import com.lucasalfare.flworld.*
import java.util.*

const val LEGACY_DISTANCE_UNIT_CM = 100L
const val LEGACY_SPEED_UNIT_CM_PER_SECOND = 100L

class MoveHandler : GameActionHandler {
  override val actionName: String = "move"

  override fun execute(action: GameAction, nucleus: Nucleus): GameActionResult {
    if (!nucleus.containsCharacter(action.actorId)) {
      return GameActionResult.Rejected(
        "Character '${action.actorId.value}' is not in the world"
      )
    }

    val targetLocationIdValue = (action.arguments["targetLocationId"] ?: action.arguments["destination"]
    ?: action.arguments["locationId"]) as? String ?: return GameActionResult.Rejected(
      "Missing target location argument"
    )

    val entityId = WorldEntityId(action.actorId.value)

    val currentLocationId = nucleus.world.state.getLocation(entityId) ?: return GameActionResult.Rejected(
      "Character '${action.actorId.value}' has no location in the world"
    )

    val destinationId = WorldLocationId(targetLocationIdValue)

    if (nucleus.world.graph.getLocation(destinationId) == null) {
      return GameActionResult.Rejected(
        "Destination location '${destinationId.value}' does not exist"
      )
    }

    if (currentLocationId == destinationId) {
      return GameActionResult.Rejected(
        "Character is already at location '${destinationId.value}'"
      )
    }

    val costEvaluator = WorldNavigationCost { connection, _ ->
      nucleus.getConnectionDistance(connection.id).toDouble()
    }

    val path = DijkstraPathFinder(costEvaluator).findPath(
      graph = nucleus.world.graph, from = currentLocationId, to = destinationId
    ) ?: return GameActionResult.Rejected(
      "No valid path to location '${destinationId.value}'"
    )

    val totalDistanceLegacy = path.connections.sumOf { connection ->
      nucleus.getConnectionDistance(connection.id)
    }

    val totalDistance = DistanceCm(totalDistanceLegacy * LEGACY_DISTANCE_UNIT_CM)

    val speed = nucleus.getCharacterSpeedCm(action.actorId)

    val simulationDuration = calculateTravelDuration(totalDistance, speed)
    val duration = simulationDuration.toWorldDuration()

    val startInstant = nucleus.world.clock.currentInstant

    val movement = WorldMovement(
      entityId = entityId,
      origin = currentLocationId,
      destination = destinationId,
      path = path,
      startInstant = startInstant,
      duration = duration
    )

    val currentSimulationTime = SimulationSeconds(nucleus.world.clock.currentInstant.value)

    val activity = Activity(
      id = ActivityId(UUID.randomUUID().toString()),
      characterId = action.actorId,
      type = ActivityType.MOVEMENT,
      description = "Moving to ${nucleus.getLocationDisplayName(destinationId) ?: destinationId.value}",
      mode = ActivityMode.BLOCKING,
      startTime = currentSimulationTime,
      duration = simulationDuration
    )

    val registered = nucleus.registerMovementActivity(entityId, activity)
    if (!registered) {
      return GameActionResult.Rejected(
        "Character '${action.actorId.value}' already has an active activity"
      )
    }

    return try {
      nucleus.world.startMovement(movement)

      nucleus.explorationSystem.clearLastResult(action.actorId)

      val originDisplayName = nucleus.getLocationDisplayName(currentLocationId) ?: currentLocationId.value
      val destinationDisplayName = nucleus.getLocationDisplayName(destinationId) ?: destinationId.value

      val movedFact = GameFact(
        name = "character_moved", arguments = mapOf(
          "characterId" to action.actorId.value,
          "fromLocationId" to currentLocationId.value,
          "toLocationId" to destinationId.value,
          "locationId" to currentLocationId.value,
          "fromLocationName" to originDisplayName,
          "toLocationName" to destinationDisplayName
        )
      )

      val startedFact = activityLifecycleFact("activity_started", activity, nucleus)

      GameActionResult.ActivityStarted(
        activityId = activity.id, facts = listOf(movedFact, startedFact)
      )
    } catch (ex: Exception) {
      nucleus.transitionActivityStatus(activity.id, ActivityStatus.FAILED)
      val failed = nucleus.getActivity(activity.id) ?: activity.copy(status = ActivityStatus.FAILED)
      GameActionResult.Rejected(
        reason = "Failed to start movement: ${ex.message}",
        facts = listOf(activityLifecycleFact("activity_failed", failed, nucleus))
      )
    }
  }
}