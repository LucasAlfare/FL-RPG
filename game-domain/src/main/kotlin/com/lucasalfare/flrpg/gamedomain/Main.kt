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

@JvmInline
value class ActivityId(val value: String) {
  init {
    require(value.isNotBlank()) { "ActivityId must not be blank." }
  }
}

enum class ActivityType {
  MOVEMENT
}

enum class ActivityStatus {
  RUNNING, COMPLETED, CANCELLED, INTERRUPTED, FAILED
}

enum class ActivityMode {
  BLOCKING, NON_BLOCKING
}

data class Activity(
  val id: ActivityId,
  val characterId: CharacterId,
  val type: ActivityType,
  val description: String,
  val mode: ActivityMode,
  val startTime: SimulationSeconds,
  val duration: SimulationSeconds,
  val status: ActivityStatus = ActivityStatus.RUNNING
) {
  val isTerminal: Boolean
    get() = status != ActivityStatus.RUNNING
}

interface ActivityHandler {
  val activityType: ActivityType
  fun progress(activity: Activity, nucleus: Nucleus): List<GameFact>
  fun complete(activity: Activity, nucleus: Nucleus): List<GameFact>
  fun cancel(activity: Activity, nucleus: Nucleus): List<GameFact>
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

data class CharacterAttributes(
  val baseSpeed: SpeedCmPerSecond
) {
  val effectiveSpeed: SpeedCmPerSecond
    get() = baseSpeed

  companion object {
    val DEFAULT = CharacterAttributes(
      baseSpeed = SpeedCmPerSecond(Nucleus.DEFAULT_SPEED * LEGACY_SPEED_UNIT_CM_PER_SECOND)
    )
  }
}

data class Character(
  val id: CharacterId, val name: String, val attributes: CharacterAttributes = CharacterAttributes.DEFAULT
)

class Nucleus(
  val id: NucleusId, val world: World = World(WorldId(id.value))
) {
  internal val stateLock = Any()

  private val _characters = mutableMapOf<CharacterId, Character>()

  private val _locationDisplayNames = mutableMapOf<WorldLocationId, String>()

  private val _connectionDistances = mutableMapOf<WorldConnectionId, DistanceCm>()

  private val _activities = mutableMapOf<ActivityId, Activity>()

  private val _characterActiveActivities = mutableMapOf<CharacterId, ActivityId>()

  private val _movementActivities = mutableMapOf<WorldEntityId, ActivityId>()

  val characters: Set<CharacterId>
    get() = synchronized(stateLock) {
      _characters.keys.toSet()
    }

  fun registerCharacter(character: Character) {
    synchronized(stateLock) {
      _characters[character.id] = character
    }
  }

  fun registerCharacter(characterId: CharacterId) {
    registerCharacter(
      Character(
        id = characterId, name = characterId.value, attributes = CharacterAttributes.DEFAULT
      )
    )
  }

  fun unregisterCharacter(characterId: CharacterId) {
    synchronized(stateLock) {
      val entityId = WorldEntityId(characterId.value)
      world.removeEntity(entityId)
      _movementActivities.remove(entityId)
      _characters.remove(characterId)
      val activeActivityId = _characterActiveActivities.remove(characterId)
      if (activeActivityId != null) {
        _activities.remove(activeActivityId)
      }
    }
  }

  fun containsCharacter(characterId: CharacterId): Boolean = synchronized(stateLock) {
    _characters.containsKey(characterId)
  }

  fun getCharacter(characterId: CharacterId): Character? = synchronized(stateLock) {
    _characters[characterId]
  }

  fun getCharactersAtLocation(
    locationId: WorldLocationId
  ): List<CharacterId> = synchronized(stateLock) {
    _characters.values.filter { character ->
      world.state.getLocation(
        WorldEntityId(character.id.value)
      ) == locationId
    }.map { it.id }
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
    val character = _characters[characterId] ?: return DEFAULT_SPEED
    character.attributes.effectiveSpeed.value / LEGACY_SPEED_UNIT_CM_PER_SECOND
  }

  fun getCharacterSpeedCm(characterId: CharacterId): SpeedCmPerSecond = synchronized(stateLock) {
    val character = _characters[characterId] ?: CharacterAttributes.DEFAULT.effectiveSpeed
    val speed = (character as? Character)?.attributes?.effectiveSpeed ?: character as SpeedCmPerSecond
    speed
  }

  fun setCharacterSpeed(characterId: CharacterId, speed: Long) {
    require(speed > 0L) { "Character speed must be positive." }
    synchronized(stateLock) {
      val character = _characters[characterId] ?: return@synchronized
      val updatedAttributes = character.attributes.copy(
        baseSpeed = SpeedCmPerSecond(speed * LEGACY_SPEED_UNIT_CM_PER_SECOND)
      )
      _characters[characterId] = character.copy(attributes = updatedAttributes)
    }
  }

  fun registerMovementActivity(entityId: WorldEntityId, activity: Activity): Boolean = synchronized(stateLock) {
    val registered = registerActivityLocked(activity)
    if (registered) {
      _movementActivities[entityId] = activity.id
    }
    registered
  }

  fun getActivityForMovement(entityId: WorldEntityId): Activity? = synchronized(stateLock) {
    val activityId = _movementActivities[entityId] ?: return@synchronized null
    _activities[activityId]
  }

  fun registerActivity(activity: Activity): Boolean = synchronized(stateLock) {
    registerActivityLocked(activity)
  }

  private fun registerActivityLocked(activity: Activity): Boolean {
    val existingId = _characterActiveActivities[activity.characterId]
    if (existingId != null) {
      val existing = _activities[existingId]
      if (existing != null && !existing.isTerminal) {
        return false
      }
    }
    _activities[activity.id] = activity
    if (!activity.isTerminal) {
      _characterActiveActivities[activity.characterId] = activity.id
    }
    return true
  }

  fun getActivity(activityId: ActivityId): Activity? = synchronized(stateLock) {
    _activities[activityId]
  }

  fun getActiveActivityForCharacter(characterId: CharacterId): Activity? = synchronized(stateLock) {
    val activityId = _characterActiveActivities[characterId] ?: return@synchronized null
    _activities[activityId]
  }

  fun cancelActivity(characterId: CharacterId, activityId: ActivityId): GameActionResult = synchronized(stateLock) {
    val activity = _activities[activityId] ?: return GameActionResult.Rejected("Activity not found.")

    if (activity.characterId != characterId) {
      return GameActionResult.Rejected("Character '${characterId.value}' does not own activity '${activityId.value}'.")
    }

    if (activity.isTerminal) {
      return GameActionResult.Rejected("Activity '${activityId.value}' is already terminated.")
    }

    val entityId = WorldEntityId(characterId.value)
    val facts = mutableListOf<GameFact>()

    if (activity.type == ActivityType.MOVEMENT) {
      val movement = world.getMovement(entityId)
      if (movement != null && movement.isInProgress) {
        world.stopMovement(entityId)
        val currentLocationId = world.state.getLocation(entityId) ?: movement.origin
        val originName = getLocationDisplayName(movement.origin) ?: movement.origin.value
        val currentName = getLocationDisplayName(currentLocationId) ?: currentLocationId.value

        facts.add(
          GameFact(
            name = "character_movement_interrupted", arguments = mapOf(
              "characterId" to characterId.value,
              "originLocationId" to movement.origin.value,
              "locationId" to currentLocationId.value,
              "originLocationName" to originName,
              "locationName" to currentName
            )
          )
        )
      }
    }

    transitionActivityStatus(activityId, ActivityStatus.CANCELLED)

    facts.add(
      GameFact(
        name = "activity_cancelled", arguments = mapOf(
          "characterId" to characterId.value, "activityId" to activityId.value, "type" to activity.type.name
        )
      )
    )

    GameActionResult.Success(facts = facts)
  }

  fun transitionActivityStatus(activityId: ActivityId, newStatus: ActivityStatus): Boolean = synchronized(stateLock) {
    val activity = _activities[activityId] ?: return false
    if (activity.isTerminal) {
      return false
    }
    val updated = activity.copy(status = newStatus)
    _activities[activityId] = updated
    if (updated.isTerminal) {
      if (_characterActiveActivities[activity.characterId] == activityId) {
        _characterActiveActivities.remove(activity.characterId)
      }
      val entityId = WorldEntityId(activity.characterId.value)
      if (_movementActivities[entityId] == activityId) {
        _movementActivities.remove(entityId)
      }
    }
    return true
  }

  fun getActivityProgress(activityId: ActivityId, currentSimulationTime: SimulationSeconds): Double =
    synchronized(stateLock) {
      val activity = _activities[activityId] ?: return 0.0
      if (activity.duration.value == 0L) return 1.0
      val elapsed = currentSimulationTime.value - activity.startTime.value
      if (elapsed <= 0L) return 0.0
      if (elapsed >= activity.duration.value) return 1.0
      return elapsed.toDouble() / activity.duration.value.toDouble()
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

    val activeActivity = nucleus.getActiveActivityForCharacter(characterId)
    if (activeActivity != null && activeActivity.mode == ActivityMode.BLOCKING) {
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

  data class ActivityStarted(
    val activityId: ActivityId, override val facts: List<GameFact> = emptyList()
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

class CancelActivityHandler : GameActionHandler {
  override val actionName: String = "cancel_activity"

  override fun execute(action: GameAction, nucleus: Nucleus): GameActionResult {
    val activityIdValue = action.arguments["activityId"] as? String ?: return GameActionResult.Rejected(
      "Missing activityId argument"
    )
    return nucleus.cancelActivity(action.actorId, ActivityId(activityIdValue))
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
      id = ActivityId("activity_move_${action.actorId.value}_${currentSimulationTime.value}"),
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

      GameActionResult.ActivityStarted(
        activityId = activity.id, facts = listOf(fact)
      )
    } catch (ex: Exception) {
      nucleus.transitionActivityStatus(activity.id, ActivityStatus.FAILED)
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
      EnterWorldHandler(), MoveHandler(), CancelActivityHandler()
    )
  }

  fun getCharacterMovementProgressDetails(characterId: CharacterId): Triple<String, Double, SimulationSeconds?>? =
    synchronized(nucleus.stateLock) {
      val entityId = WorldEntityId(characterId.value)
      val movement = nucleus.world.getMovement(entityId) ?: return@synchronized null
      if (!movement.isInProgress) return@synchronized null

      val activity = nucleus.getActivityForMovement(entityId) ?: return@synchronized null
      val currentSimulationTime = SimulationSeconds(nucleus.world.clock.currentInstant.value)
      val progress = nucleus.getActivityProgress(activity.id, currentSimulationTime)

      val remainingTicks =
        (activity.duration.value - (currentSimulationTime.value - activity.startTime.value)).coerceAtLeast(0L)

      Triple(activity.description, progress, SimulationSeconds(remainingTicks))
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
    val activeActivity = nucleus.getActiveActivityForCharacter(action.actorId)
    if (activeActivity != null && activeActivity.mode == ActivityMode.BLOCKING && action.name != "cancel_activity") {
      return GameActionResult.Rejected(
        "Character '${action.actorId.value}' is busy with a blocking activity: ${activeActivity.description}"
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

          val activity = nucleus.getActivityForMovement(movement.entityId)
          if (activity != null) {
            nucleus.transitionActivityStatus(activity.id, ActivityStatus.COMPLETED)
          }

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

          val activity = nucleus.getActivityForMovement(movement.entityId)
          if (activity != null) {
            nucleus.transitionActivityStatus(activity.id, ActivityStatus.INTERRUPTED)
          }

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