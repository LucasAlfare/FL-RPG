package com.lucasalfare.flrpg.gamedomain

import com.lucasalfare.flworld.WorldEntityId

class LookAroundHandler : GameActionHandler {
  override val actionName: String = "look_around"

  override fun execute(action: GameAction, nucleus: Nucleus): GameActionResult {
    if (!nucleus.containsCharacter(action.actorId)) {
      return GameActionResult.Rejected(
        "Character '${action.actorId.value}' is not in the world"
      )
    }

    val entityId = WorldEntityId(action.actorId.value)
    val locationId = nucleus.world.state.getLocation(entityId) ?: return GameActionResult.Rejected(
      "Character '${action.actorId.value}' has no location in the world"
    )

    return when (val result = nucleus.explorationSystem.lookAround(action.actorId, locationId.value)) {
      is LookAroundSuccess -> {
        val facts = mutableListOf(
          GameFact(
            name = "exploration_area_observed", arguments = mapOf(
              "areaId" to result.area.id.value, "areaName" to result.area.name
            )
          )
        )
        for (element in result.newlyDiscovered) {
          facts.add(
            GameFact(
              name = "exploration_element_discovered", arguments = mapOf(
                "areaId" to result.area.id.value,
                "areaName" to result.area.name,
                "elementId" to element.id.value,
                "elementName" to element.name
              )
            )
          )
        }
        GameActionResult.Success(facts = facts)
      }

      is ExplorationRejection.NoAreaAtLocation -> GameActionResult.Rejected(
        "No exploration area at current location"
      )

      else -> GameActionResult.Rejected("Look around failed")
    }
  }
}

class ExamineHandler : GameActionHandler {
  override val actionName: String = "examine"

  override fun execute(action: GameAction, nucleus: Nucleus): GameActionResult {
    if (!nucleus.containsCharacter(action.actorId)) {
      return GameActionResult.Rejected(
        "Character '${action.actorId.value}' is not in the world"
      )
    }

    val elementIdValue =
      action.arguments["elementId"] as? String ?: return GameActionResult.Rejected("Missing elementId argument")

    if (elementIdValue.isBlank()) {
      return GameActionResult.Rejected("elementId cannot be blank")
    }

    val elementId = ExplorationElementId(elementIdValue)

    val entityId = WorldEntityId(action.actorId.value)
    val locationId = nucleus.world.state.getLocation(entityId) ?: return GameActionResult.Rejected(
      "Character '${action.actorId.value}' has no location in the world"
    )

    return when (val result = nucleus.explorationSystem.examine(action.actorId, locationId.value, elementId)) {
      is ExamineSuccess -> {
        val facts = mutableListOf(
          GameFact(
            name = "exploration_element_examined", arguments = mapOf(
              "areaId" to result.area.id.value,
              "areaName" to result.area.name,
              "elementId" to result.element.id.value,
              "elementName" to result.element.name
            )
          )
        )
        for (discovered in result.newlyDiscovered) {
          facts.add(
            GameFact(
              name = "exploration_element_discovered", arguments = mapOf(
                "areaId" to result.area.id.value,
                "areaName" to result.area.name,
                "elementId" to discovered.id.value,
                "elementName" to discovered.name,
                "sourceElementId" to result.element.id.value,
                "sourceElementName" to result.element.name
              )
            )
          )
        }
        GameActionResult.Success(facts = facts)
      }

      is ExplorationRejection.NoAreaAtLocation -> GameActionResult.Rejected(
        "No exploration area at current location"
      )

      is ExplorationRejection.ElementNotFound -> GameActionResult.Rejected(
        "Element '${elementId.value}' not found"
      )

      is ExplorationRejection.ElementNotDiscovered -> GameActionResult.Rejected(
        "Element '${elementId.value}' has not been discovered"
      )

      is ExplorationRejection.ElementNotInCurrentArea -> GameActionResult.Rejected(
        "Element '${elementId.value}' is not in the current area"
      )

      else -> GameActionResult.Rejected("Examine failed")
    }
  }
}