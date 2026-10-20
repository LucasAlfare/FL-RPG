package com.lucasalfare.flrpg.gamedomain

import com.lucasalfare.flworld.WorldEntityId

@JvmInline
value class InteractionId(val value: String) {
  init {
    require(value.isNotBlank()) { "InteractionId must not be blank." }
  }
}

data class Interaction(
  val id: InteractionId,
  val label: String,
  val actionName: String? = null,
  val fixedArguments: Map<String, Any?> = emptyMap(),
  val requiredArgumentNames: List<String> = emptyList(),
  val children: List<Interaction> = emptyList()
) {
  init {
    require(label.isNotBlank()) { "Interaction label must not be blank." }
    require(actionName == null || actionName.isNotBlank()) {
      "Action name must not be blank when present."
    }
    require(requiredArgumentNames.all { it.isNotBlank() }) {
      "Required argument names must not be blank."
    }
    require(children.map { it.id }.toSet().size == children.size) {
      "Child interaction identifiers must be unique within a menu."
    }
    if (children.isNotEmpty()) {
      require(actionName == null) {
        "A menu interaction must not declare a game action."
      }
      require(fixedArguments.isEmpty()) {
        "A menu interaction must not declare fixed arguments."
      }
      require(requiredArgumentNames.isEmpty()) {
        "A menu interaction must not declare required argument names."
      }
    }
  }

  val needsAdditionalArguments: Boolean
    get() = requiredArgumentNames.isNotEmpty()

  val isImmediate: Boolean
    get() = requiredArgumentNames.isEmpty()

  val isMenu: Boolean
    get() = children.isNotEmpty()

  val isExecutable: Boolean
    get() = !isMenu && actionName != null
}

fun interface InteractionProvider {
  fun availableInteractions(characterId: CharacterId, nucleus: Nucleus): List<Interaction>
}

class DefaultInteractionProvider : InteractionProvider {
  override fun availableInteractions(characterId: CharacterId, nucleus: Nucleus): List<Interaction> {
    if (!nucleus.containsCharacter(characterId)) {
      return listOf(
        Interaction(
          id = InteractionId("enter_world"),
          label = "Enter the world",
          actionName = "enter_world",
          fixedArguments = mapOf("locationId" to "spawn")
        )
      )
    }

    val cancelInteractions =
      nucleus.getActiveActivitiesForCharacter(characterId).filter { nucleus.canCancelActivity(characterId, it.id) }
        .map { activity ->
          Interaction(
            id = InteractionId("cancel_activity_${activity.id.value}"),
            label = "Cancel: ${activity.description}",
            actionName = "cancel_activity",
            fixedArguments = mapOf("activityId" to activity.id.value)
          )
        }

    if (nucleus.hasBlockingActivity(characterId)) {
      return cancelInteractions
    }

    val entityId = WorldEntityId(characterId.value)
    val currentLocationId = nucleus.world.state.getLocation(entityId) ?: return cancelInteractions

    val destinationInteractions =
      nucleus.world.graph.getOutgoingConnections(currentLocationId).distinctBy { it.to }.map { connection ->
        val destinationId = connection.to
        val displayName = nucleus.getLocationDisplayName(destinationId) ?: destinationId.value
        Interaction(
          id = InteractionId("move_to_${destinationId.value}"),
          label = displayName,
          actionName = "move",
          fixedArguments = mapOf("targetLocationId" to destinationId.value)
        )
      }

    if (destinationInteractions.isEmpty()) {
      return cancelInteractions
    }

    val moveInteraction = Interaction(
      id = InteractionId("move"), label = "Move to a location", children = destinationInteractions
    )

    return cancelInteractions + moveInteraction
  }
}