@file:Suppress("unused")

package com.lucasalfare.flrpg.gamedomain

import com.lucasalfare.flworld.*

@JvmInline
value class CharacterId(val value: String)

@JvmInline
value class NucleusId(val value: String)

@JvmInline
value class DistanceCm(val value: Long) {
  init {
    require(value >= 0L) { "Distance cannot be negative." }
  }
}

@JvmInline
value class SpeedCmPerSecond(val value: Long) {
  init {
    require(value > 0L) { "Speed must be positive." }
  }
}

@JvmInline
value class SimulationSeconds(val value: Long) {
  init {
    require(value >= 0L) { "Simulation duration cannot be negative." }
  }
}

fun calculateTravelDuration(distance: DistanceCm, speed: SpeedCmPerSecond): SimulationSeconds {
  if (distance.value == 0L) {
    return SimulationSeconds(0L)
  }
  val seconds = (distance.value + speed.value - 1L) / speed.value
  return SimulationSeconds(seconds.coerceAtLeast(1L))
}

fun SimulationSeconds.toWorldDuration(): WorldDuration = WorldDuration(value)

private const val LEGACY_DISTANCE_UNIT_CM = 100L
private const val LEGACY_SPEED_UNIT_CM_PER_SECOND = 100L

data class Character(
  val id: CharacterId, val name: String
)

class Nucleus(
  val id: NucleusId, val world: World = World(WorldId(id.value))
) {
  internal val stateLock = Any()

  private val _characters = mutableSetOf<CharacterId>()

  private val _locationDisplayNames = mutableMapOf<WorldLocationId, String>()

  private val _connectionDistances = mutableMapOf<WorldConnectionId, DistanceCm>()

  private val _characterSpeeds = mutableMapOf<CharacterId, Long>()

  val characters: Set<CharacterId>
    get() = synchronized(stateLock) {
      _characters.toSet()
    }

  fun registerCharacter(characterId: CharacterId) {
    synchronized(stateLock) {
      _characters.add(characterId)
      _characterSpeeds.putIfAbsent(characterId, DEFAULT_SPEED)
    }
  }

  fun unregisterCharacter(characterId: CharacterId) {
    synchronized(stateLock) {
      world.removeEntity(WorldEntityId(characterId.value))
      _characters.remove(characterId)
      _characterSpeeds.remove(characterId)
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

  fun registerLocation(locationId: String, displayName: String = locationId) {
    synchronized(stateLock) {
      registerLocationNodeLocked(locationId)
      _locationDisplayNames[WorldLocationId(locationId)] = displayName
    }
  }

  fun getLocationDisplayName(locationId: WorldLocationId): String? = synchronized(stateLock) {
    _locationDisplayNames[locationId]
  }

  fun getLocationDisplayName(locationId: String): String? = getLocationDisplayName(WorldLocationId(locationId))

  fun connectLocations(
    fromId: String, toId: String, connectionId: String = "conn_${fromId}_$toId", distance: Long = DEFAULT_DISTANCE
  ) {
    require(distance > 0L) { "Connection distance must be positive." }

    synchronized(stateLock) {
      registerLocationNodeLocked(fromId)
      registerLocationNodeLocked(toId)

      val connection = WorldConnection(
        id = WorldConnectionId(connectionId), from = WorldLocationId(fromId), to = WorldLocationId(toId)
      )

      world.graph.registerConnection(connection)
      _connectionDistances[connection.id] = DistanceCm(distance * LEGACY_DISTANCE_UNIT_CM)
    }
  }

  fun getConnectionDistance(connectionId: WorldConnectionId): Long = synchronized(stateLock) {
    val stored = _connectionDistances[connectionId]
    if (stored != null) {
      stored.value / LEGACY_DISTANCE_UNIT_CM
    } else {
      DEFAULT_DISTANCE
    }
  }

  fun getConnectionDistance(connectionId: String): Long = getConnectionDistance(WorldConnectionId(connectionId))

  fun getConnectionDistanceCm(connectionId: WorldConnectionId): DistanceCm = synchronized(stateLock) {
    _connectionDistances[connectionId] ?: DistanceCm(DEFAULT_DISTANCE * LEGACY_DISTANCE_UNIT_CM)
  }

  fun getCharacterSpeed(characterId: CharacterId): Long = synchronized(stateLock) {
    _characterSpeeds[characterId] ?: DEFAULT_SPEED
  }

  fun setCharacterSpeed(characterId: CharacterId, speed: Long) {
    require(speed > 0L) { "Character speed must be positive." }
    synchronized(stateLock) {
      _characterSpeeds[characterId] = speed
    }
  }

  private fun registerLocationNodeLocked(locationId: String) {
    val id = WorldLocationId(locationId)

    if (world.graph.getLocation(id) == null) {
      world.graph.registerLocation(WorldLocation(id))
    }

    _locationDisplayNames.putIfAbsent(id, locationId)
  }

  companion object {
    const val DEFAULT_DISTANCE: Long = 1L
    const val DEFAULT_SPEED: Long = 1L
  }
}

data class GameAction(
  val name: String, val actorId: CharacterId, val arguments: Map<String, Any?> = emptyMap()
)

data class GameFact(
  val name: String, val arguments: Map<String, Any?> = emptyMap()
)

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

    val entityId = WorldEntityId(characterId.value)

    if (nucleus.world.getMovement(entityId)?.isInProgress == true) {
      return emptyList()
    }

    val currentLocationId = nucleus.world.state.getLocation(entityId) ?: return emptyList()

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
      return emptyList()
    }

    return listOf(
      Interaction(
        id = InteractionId("move"), label = "Move to a location", children = destinationInteractions
      )
    )
  }
}

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

    val speedLegacy = nucleus.getCharacterSpeed(action.actorId).coerceAtLeast(Nucleus.DEFAULT_SPEED)
    val speed = SpeedCmPerSecond(speedLegacy * LEGACY_SPEED_UNIT_CM_PER_SECOND)

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

    return try {
      nucleus.world.startMovement(movement)

      val originDisplayName = nucleus.getLocationDisplayName(currentLocationId) ?: currentLocationId.value
      val destinationDisplayName = nucleus.getLocationDisplayName(destinationId) ?: destinationId.value

      val fact = GameFact(
        name = "character_moved", arguments = mapOf(
          "characterId" to action.actorId.value,
          "fromLocationId" to currentLocationId.value,
          "toLocationId" to destinationId.value,
          "locationId" to currentLocationId.value,
          "fromLocationName" to originDisplayName,
          "toLocationName" to destinationDisplayName
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
      EnterWorldHandler(), MoveHandler()
    )
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
          val originName = nucleus.getLocationDisplayName(movement.origin) ?: movement.origin.value
          val destinationName = nucleus.getLocationDisplayName(movement.destination) ?: movement.destination.value

          facts.add(
            GameFact(
              name = "character_movement_completed", arguments = mapOf(
                "characterId" to movement.entityId.value,
                "originLocationId" to movement.origin.value,
                "locationId" to movement.destination.value,
                "originLocationName" to originName,
                "locationName" to destinationName
              )
            )
          )
        } else {
          val currentLocationId = currentLocation ?: movement.origin
          val originName = nucleus.getLocationDisplayName(movement.origin) ?: movement.origin.value
          val currentName = nucleus.getLocationDisplayName(currentLocationId) ?: currentLocationId.value

          facts.add(
            GameFact(
              name = "character_movement_interrupted", arguments = mapOf(
                "characterId" to movement.entityId.value,
                "originLocationId" to movement.origin.value,
                "locationId" to currentLocationId.value,
                "originLocationName" to originName,
                "locationName" to currentName
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

fun buildDevelopmentNucleus(
  id: NucleusId = NucleusId("development")
): Nucleus {
  val nucleus = Nucleus(id)

  nucleus.registerLocation("spawn", "Starting Point")
  nucleus.registerLocation("path", "Winding Path")
  nucleus.registerLocation("plaza", "Central Plaza")
  nucleus.registerLocation("city", "Distant City")

  nucleus.connectLocations("spawn", "path", distance = 1L)
  nucleus.connectLocations("path", "spawn", distance = 1L)

  nucleus.connectLocations("path", "plaza", distance = 1L)
  nucleus.connectLocations("plaza", "path", distance = 1L)

  nucleus.connectLocations("plaza", "city", distance = 30L)
  nucleus.connectLocations("city", "plaza", distance = 30L)

  return nucleus
}