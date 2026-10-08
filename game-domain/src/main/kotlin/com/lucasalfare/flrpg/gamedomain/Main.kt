package com.lucasalfare.flrpg.gamedomain

import com.lucasalfare.flworld.World
import com.lucasalfare.flworld.WorldEntity
import com.lucasalfare.flworld.WorldEntityId
import com.lucasalfare.flworld.WorldLocation
import com.lucasalfare.flworld.WorldLocationId

@JvmInline
value class CharacterId(val value: String)

@JvmInline
value class NucleusId(val value: String)

data class Character(
  val id: CharacterId, val name: String
)

class Nucleus(
  val id: NucleusId, val world: World
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

    if (nucleus.world.graph.getLocation(locationId) == null) {
      nucleus.world.graph.registerLocation(WorldLocation(locationId))
    }

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
    return GameActionResult.Rejected("Action 'move' not yet implemented")
  }
}