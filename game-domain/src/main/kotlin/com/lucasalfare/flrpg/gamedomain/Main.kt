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
  internal val stateLock = Any()

  private val _characters = mutableSetOf<CharacterId>()

  val characters: Set<CharacterId>
    get() = synchronized(stateLock) {
      _characters.toSet()
    }

  fun registerCharacter(characterId: CharacterId) {
    synchronized(stateLock) {
      _characters.add(characterId)
    }
  }

  fun unregisterCharacter(characterId: CharacterId) {
    synchronized(stateLock) {
      _characters.remove(characterId)
    }
  }

  fun containsCharacter(characterId: CharacterId): Boolean = synchronized(stateLock) {
    _characters.contains(characterId)
  }

  fun getCharactersAtLocation(
    locationId: WorldLocationId
  ): List<CharacterId> = synchronized(stateLock) {
    _characters.filter { characterId ->
      world.state.getLocation(
        WorldEntityId(characterId.value)
      ) == locationId
    }
  }

  fun registerLocation(locationId: String) {
    synchronized(stateLock) {
      val id = WorldLocationId(locationId)

      if (world.graph.getLocation(id) == null) {
        world.graph.registerLocation(WorldLocation(id))
      }
    }
  }

  fun connectLocations(
    fromId: String, toId: String, connectionId: String = "conn_${fromId}_$toId"
  ) {
    synchronized(stateLock) {
      registerLocation(fromId)
      registerLocation(toId)

      val connection = WorldConnection(
        id = WorldConnectionId(connectionId), from = WorldLocationId(fromId), to = WorldLocationId(toId)
      )

      world.graph.registerConnection(connection)
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

data class GameFactAudience(
  val fact: GameFact, val characterIds: List<CharacterId>
)

data class GameActionExecution(
  val result: GameActionResult, val factAudiences: List<GameFactAudience>
)

interface GameActionHandler {
  val actionName: String
  fun execute(action: GameAction, nucleus: Nucleus): GameActionResult
}

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

    val fact = GameFact(
      name = "character_entered", arguments = mapOf(
        "characterId" to action.actorId.value, "locationId" to locationId.value
      )
    )

    return GameActionResult.Success(
      facts = listOf(fact)
    )
  }
}

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

    val path = UnweightedPathFinder().findPath(
      graph = nucleus.world.graph, from = currentLocationId, to = destinationId
    ) ?: return GameActionResult.Rejected(
      "No valid path to location '${destinationId.value}'"
    )

    val duration = WorldDuration(
      path.connections.size.coerceAtLeast(1).toLong()
    )

    val startInstant = nucleus.world.clock.currentInstant

    val movement = WorldMovement(
      entityId = entityId,
      origin = currentLocationId,
      destination = destinationId,
      path = path,
      startInstant = startInstant,
      duration = duration
    )

    return try {
      nucleus.world.startMovement(movement)

      val fact = GameFact(
        name = "character_moved", arguments = mapOf(
          "characterId" to action.actorId.value,
          "fromLocationId" to currentLocationId.value,
          "toLocationId" to destinationId.value,
          "locationId" to currentLocationId.value
        )
      )

      GameActionResult.Success(
        facts = listOf(fact)
      )
    } catch (ex: Exception) {
      GameActionResult.Rejected(
        "Failed to start movement: ${ex.message}"
      )
    }
  }
}

class GameActionProcessor(
  val nucleus: Nucleus,
  handlers: List<GameActionHandler> = defaultHandlers()
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
      EnterWorldHandler(),
      MoveHandler()
    )
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