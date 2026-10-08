@file:Suppress("unused")

package com.lucasalfare.flrpg.gamedomain

import com.lucasalfare.flworld.UnweightedPathFinder
import com.lucasalfare.flworld.World
import com.lucasalfare.flworld.WorldConnection
import com.lucasalfare.flworld.WorldConnectionId
import com.lucasalfare.flworld.WorldDuration
import com.lucasalfare.flworld.WorldEntity
import com.lucasalfare.flworld.WorldEntityId
import com.lucasalfare.flworld.WorldId
import com.lucasalfare.flworld.WorldLocation
import com.lucasalfare.flworld.WorldLocationId
import com.lucasalfare.flworld.WorldMovement

@JvmInline
value class CharacterId(val value: String)

@JvmInline
value class NucleusId(val value: String)

data class Character(
  val id: CharacterId, val name: String
)

class Nucleus(
  val id: NucleusId, val world: World = World(WorldId(id.value))
) {
  private val _characters = mutableSetOf<CharacterId>()

  val characters: Set<CharacterId>
    get() = _characters

  fun registerCharacter(characterId: CharacterId) {
    _characters.add(characterId)
  }

  fun unregisterCharacter(characterId: CharacterId) {
    _characters.remove(characterId)
  }

  fun containsCharacter(characterId: CharacterId): Boolean {
    return _characters.contains(characterId)
  }

  fun getCharactersAtLocation(locationId: WorldLocationId): List<CharacterId> {
    return _characters.filter { characterId ->
      world.state.getLocation(WorldEntityId(characterId.value)) == locationId
    }
  }

  fun registerLocation(locationId: String) {
    val id = WorldLocationId(locationId)
    if (world.graph.getLocation(id) == null) {
      world.graph.registerLocation(WorldLocation(id))
    }
  }

  fun connectLocations(fromId: String, toId: String, connectionId: String = "conn_${fromId}_$toId") {
    registerLocation(fromId)
    registerLocation(toId)
    val conn = WorldConnection(
      id = WorldConnectionId(connectionId), from = WorldLocationId(fromId), to = WorldLocationId(toId)
    )
    world.graph.registerConnection(conn)
  }
}

data class GameAction(
  val name: String, val actorId: CharacterId, val arguments: Map<String, Any?> = emptyMap()
)

data class GameFact(
  val name: String, val arguments: Map<String, Any?> = emptyMap()
)

sealed interface GameActionResult {
  val facts: List<GameFact>

  data class Success(
    override val facts: List<GameFact> = emptyList()
  ) : GameActionResult

  data class Rejected(
    val reason: String, override val facts: List<GameFact> = emptyList()
  ) : GameActionResult
}

class GameActionProcessor(val nucleus: Nucleus) {
  fun process(action: GameAction): GameActionResult {
    return when (action.name) {
      "enter_world" -> processEnterWorld(action)
      "move" -> processMove(action)
      else -> GameActionResult.Rejected("Unknown action: ${action.name}")
    }
  }

  fun advanceTime(ticks: Long): List<GameFact> {
    val activeBefore = nucleus.world.getActiveMovements()
    nucleus.world.advance(WorldDuration(ticks))
    val facts = mutableListOf<GameFact>()

    for (movement in activeBefore) {
      val currentMovement = nucleus.world.getMovement(movement.entityId)
      if (currentMovement == null || !currentMovement.isInProgress) {
        val currentLocation = nucleus.world.state.getLocation(movement.entityId)
        if (currentLocation == movement.destination) {
          facts.add(
            GameFact(
              name = "character_movement_completed", arguments = mapOf(
                "characterId" to movement.entityId.value,
                "originLocationId" to movement.origin.value,
                "locationId" to movement.destination.value
              )
            )
          )
        } else {
          facts.add(
            GameFact(
              name = "character_movement_interrupted", arguments = mapOf(
                "characterId" to movement.entityId.value,
                "originLocationId" to movement.origin.value,
                "locationId" to (currentLocation?.value ?: movement.origin.value)
              )
            )
          )
        }
      }
    }

    return facts
  }

  fun findInterestedCharacters(fact: GameFact): List<CharacterId> {
    val locationIdStr = fact.arguments["locationId"] as? String ?: return emptyList()
    val locationId = WorldLocationId(locationIdStr)
    return nucleus.getCharactersAtLocation(locationId)
  }

  private fun processEnterWorld(action: GameAction): GameActionResult {
    if (nucleus.containsCharacter(action.actorId)) {
      return GameActionResult.Rejected("Character '${action.actorId.value}' is already in the world")
    }

    val locationIdStr = action.arguments["locationId"] as? String ?: "spawn"
    val locationId = WorldLocationId(locationIdStr)

    nucleus.registerLocation(locationId.value)

    val entityId = WorldEntityId(action.actorId.value)
    nucleus.world.registerEntity(WorldEntity(entityId))
    nucleus.world.state.setLocation(entityId, locationId)
    nucleus.registerCharacter(action.actorId)

    val fact = GameFact(
      name = "character_entered", arguments = mapOf(
        "characterId" to action.actorId.value, "locationId" to locationId.value
      )
    )

    return GameActionResult.Success(facts = listOf(fact))
  }

  private fun processMove(action: GameAction): GameActionResult {
    if (!nucleus.containsCharacter(action.actorId)) {
      return GameActionResult.Rejected("Character '${action.actorId.value}' is not in the world")
    }

    val targetLocationIdStr = (action.arguments["targetLocationId"] ?: action.arguments["destination"]
    ?: action.arguments["locationId"]) as? String
      ?: return GameActionResult.Rejected("Missing target location argument")

    val entityId = WorldEntityId(action.actorId.value)
    val currentLocationId = nucleus.world.state.getLocation(entityId)
      ?: return GameActionResult.Rejected("Character '${action.actorId.value}' has no location in the world")

    val destinationId = WorldLocationId(targetLocationIdStr)
    if (nucleus.world.graph.getLocation(destinationId) == null) {
      return GameActionResult.Rejected("Destination location '${destinationId.value}' does not exist")
    }

    if (currentLocationId == destinationId) {
      return GameActionResult.Rejected("Character is already at location '${destinationId.value}'")
    }

    val path = UnweightedPathFinder().findPath(
      graph = nucleus.world.graph, from = currentLocationId, to = destinationId
    ) ?: return GameActionResult.Rejected("No valid path to location '${destinationId.value}'")

    val duration = WorldDuration(path.connections.size.coerceAtLeast(1).toLong())
    val startInstant = nucleus.world.clock.currentInstant
    val movement = WorldMovement(
      entityId = entityId,
      origin = currentLocationId,
      destination = destinationId,
      path = path,
      startInstant = startInstant,
      duration = duration
    )

    return runCatching {
      nucleus.world.startMovement(movement)
    }.fold(onSuccess = {
      val fact = GameFact(
        name = "character_moved", arguments = mapOf(
          "characterId" to action.actorId.value,
          "fromLocationId" to currentLocationId.value,
          "toLocationId" to destinationId.value,
          "locationId" to currentLocationId.value
        )
      )
      GameActionResult.Success(facts = listOf(fact))
    }, onFailure = { ex ->
      GameActionResult.Rejected("Failed to start movement: ${ex.message}")
    })
  }
}