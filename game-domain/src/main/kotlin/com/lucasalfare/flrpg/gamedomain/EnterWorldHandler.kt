package com.lucasalfare.flrpg.gamedomain

import com.lucasalfare.flworld.WorldEntity
import com.lucasalfare.flworld.WorldEntityId
import com.lucasalfare.flworld.WorldLocationId

class EnterWorldHandler : GameActionHandler {
  override val actionName: String = "enter_world"

  override fun execute(action: GameAction, nucleus: Nucleus): GameActionResult {
    if (nucleus.containsCharacter(action.actorId)) {
      return GameActionResult.Rejected(
        "Character '${action.actorId.value}' is already in the world"
      )
    }

    val locationIdValue = action.arguments["locationId"] as? String ?: "spawn"

    val locationId = WorldLocationId(locationIdValue)
    nucleus.registerLocation(locationId.value)

    val entityId = WorldEntityId(action.actorId.value)

    nucleus.world.registerEntity(
      WorldEntity(entityId)
    )

    nucleus.world.state.setLocation(
      entityId, locationId
    )

    nucleus.registerCharacter(action.actorId)

    val locationDisplayName = nucleus.getLocationDisplayName(locationId) ?: locationId.value

    val fact = GameFact(
      name = "character_entered", arguments = mapOf(
        "characterId" to action.actorId.value, "locationId" to locationId.value, "locationName" to locationDisplayName
      )
    )

    return GameActionResult.Success(
      facts = listOf(fact)
    )
  }
}