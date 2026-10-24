package com.lucasalfare.flrpg.gamedomain

import com.lucasalfare.flworld.WorldDuration
import com.lucasalfare.flworld.WorldEntityId

interface ActivityHandler {
  val activityType: ActivityType
  fun progress(activity: Activity, nucleus: Nucleus): List<GameFact>
  fun complete(activity: Activity, nucleus: Nucleus): List<GameFact>
  fun cancel(activity: Activity, nucleus: Nucleus): List<GameFact>
  fun isCancellable(activity: Activity, nucleus: Nucleus): Boolean = true
}

class MovementActivityHandler : ActivityHandler {
  override val activityType: ActivityType = ActivityType.MOVEMENT

  override fun progress(activity: Activity, nucleus: Nucleus): List<GameFact> {
    val entityId = WorldEntityId(activity.characterId.value)
    val movement = nucleus.world.getMovement(entityId)
    if (movement == null || !movement.isInProgress) {
      val currentLocationId = nucleus.world.state.getLocation(entityId) ?: movement?.origin ?: return emptyList()
      val origin = movement?.origin ?: currentLocationId
      val originName = nucleus.getLocationDisplayName(origin) ?: origin.value
      val currentName = nucleus.getLocationDisplayName(currentLocationId) ?: currentLocationId.value

      nucleus.transitionActivityStatus(activity.id, ActivityStatus.INTERRUPTED)

      return listOf(
        GameFact(
          name = "character_movement_interrupted", arguments = mapOf(
            "characterId" to activity.characterId.value,
            "originLocationId" to origin.value,
            "locationId" to currentLocationId.value,
            "originLocationName" to originName,
            "locationName" to currentName
          )
        )
      )
    }
    return emptyList()
  }

  override fun complete(activity: Activity, nucleus: Nucleus): List<GameFact> {
    val entityId = WorldEntityId(activity.characterId.value)
    val movement = nucleus.world.getMovement(entityId)
    val currentLocation = nucleus.world.state.getLocation(entityId)

    if (movement != null && (currentLocation == movement.destination || !movement.isInProgress)) {
      val originName = nucleus.getLocationDisplayName(movement.origin) ?: movement.origin.value
      val destinationName = nucleus.getLocationDisplayName(movement.destination) ?: movement.destination.value

      return listOf(
        GameFact(
          name = "character_movement_completed", arguments = mapOf(
            "characterId" to activity.characterId.value,
            "originLocationId" to movement.origin.value,
            "locationId" to movement.destination.value,
            "originLocationName" to originName,
            "locationName" to destinationName
          )
        )
      )
    }

    if (movement != null) {
      val currentLocationId = currentLocation ?: movement.origin
      val originName = nucleus.getLocationDisplayName(movement.origin) ?: movement.origin.value
      val currentName = nucleus.getLocationDisplayName(currentLocationId) ?: currentLocationId.value

      nucleus.transitionActivityStatus(activity.id, ActivityStatus.INTERRUPTED)

      return listOf(
        GameFact(
          name = "character_movement_interrupted", arguments = mapOf(
            "characterId" to activity.characterId.value,
            "originLocationId" to movement.origin.value,
            "locationId" to currentLocationId.value,
            "originLocationName" to originName,
            "locationName" to currentName
          )
        )
      )
    }

    return emptyList()
  }

  override fun cancel(activity: Activity, nucleus: Nucleus): List<GameFact> {
    val entityId = WorldEntityId(activity.characterId.value)
    val movement = nucleus.world.getMovement(entityId)
    if (movement == null || !movement.isInProgress) {
      return emptyList()
    }

    nucleus.world.stopMovement(entityId)
    val currentLocationId = nucleus.world.state.getLocation(entityId) ?: movement.origin
    val originName = nucleus.getLocationDisplayName(movement.origin) ?: movement.origin.value
    val currentName = nucleus.getLocationDisplayName(currentLocationId) ?: currentLocationId.value

    return listOf(
      GameFact(
        name = "character_movement_interrupted", arguments = mapOf(
          "characterId" to activity.characterId.value,
          "originLocationId" to movement.origin.value,
          "locationId" to currentLocationId.value,
          "originLocationName" to originName,
          "locationName" to currentName
        )
      )
    )
  }
}

fun calculateTravelDuration(distance: DistanceCm, speed: SpeedCmPerSecond): SimulationSeconds {
  if (distance.value == 0L) {
    return SimulationSeconds(0L)
  }
  val seconds = (distance.value + speed.value - 1L) / speed.value
  return SimulationSeconds(seconds.coerceAtLeast(1L))
}

fun activityLifecycleFact(
  name: String, activity: Activity, nucleus: Nucleus
): GameFact {
  val arguments = mutableMapOf<String, Any?>(
    "activityId" to activity.id.value, "characterId" to activity.characterId.value, "type" to activity.type.name
  )
  val entityId = WorldEntityId(activity.characterId.value)
  val locationId = nucleus.world.state.getLocation(entityId)
  if (locationId != null) {
    arguments["locationId"] = locationId.value
    arguments["locationName"] = nucleus.getLocationDisplayName(locationId) ?: locationId.value
  }
  return GameFact(name = name, arguments = arguments)
}

fun SimulationSeconds.toWorldDuration(): WorldDuration = WorldDuration(value)