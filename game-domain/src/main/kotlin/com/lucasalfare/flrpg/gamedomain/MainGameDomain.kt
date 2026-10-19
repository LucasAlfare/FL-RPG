@file:Suppress("unused")

package com.lucasalfare.flrpg.gamedomain

import com.lucasalfare.flworld.*
import java.util.*

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
    require(value >= -1L) { "Simulation duration cannot be negative." }
  }

  val isUnknown: Boolean
    get() = value == -1L

  companion object {
    val UNKNOWN = SimulationSeconds(-1L)
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
  init {
    if (status == ActivityStatus.RUNNING && !duration.isUnknown) {
      require(duration.value > 0L) {
        "Timed activities must have strictly positive duration."
      }
    }
  }

  val isTerminal: Boolean
    get() = status != ActivityStatus.RUNNING
}

data class ActivityProgressDetails(
  val activityId: ActivityId,
  val characterId: CharacterId,
  val type: ActivityType,
  val description: String,
  val mode: ActivityMode,
  val progress: Double,
  val remainingTime: SimulationSeconds
)

interface ActivityHandler {
  val activityType: ActivityType
  fun progress(activity: Activity, nucleus: Nucleus): List<GameFact>
  fun complete(activity: Activity, nucleus: Nucleus): List<GameFact>
  fun cancel(activity: Activity, nucleus: Nucleus): List<GameFact>
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

    if (movement != null && currentLocation == movement.destination) {
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

    val currentLocationId = currentLocation ?: movement?.origin ?: return emptyList()
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

  private val _terminalActivitiesHistory = ArrayDeque<Activity>()

  private val _characterActiveActivities = mutableMapOf<CharacterId, MutableSet<ActivityId>>()

  private val _movementActivities = mutableMapOf<WorldEntityId, ActivityId>()

  private val _activityHandlers = mutableMapOf<ActivityType, ActivityHandler>(
    ActivityType.MOVEMENT to MovementActivityHandler()
  )

  val characters: Set<CharacterId>
    get() = synchronized(stateLock) {
      _characters.keys.toSet()
    }

  fun hasBlockingActivity(characterId: CharacterId): Boolean = synchronized(stateLock) {
    getActiveActivitiesForCharacter(characterId).any { it.mode == ActivityMode.BLOCKING }
  }

  fun getActivityProgressDetails(
    activityId: ActivityId,
    currentSimulationTime: SimulationSeconds = SimulationSeconds(world.clock.currentInstant.value)
  ): ActivityProgressDetails? = synchronized(stateLock) {
    val activity = _activities[activityId] ?: return null
    if (activity.isTerminal) return null
    val progress = getActivityProgress(activityId, currentSimulationTime)
    val elapsed = (currentSimulationTime.value - activity.startTime.value).coerceAtLeast(0L)
    val remaining = (activity.duration.value - elapsed).coerceAtLeast(0L)
    ActivityProgressDetails(
      activityId = activity.id,
      characterId = activity.characterId,
      type = activity.type,
      description = activity.description,
      mode = activity.mode,
      progress = progress,
      remainingTime = SimulationSeconds(remaining)
    )
  }

  fun getActiveActivitiesProgressDetails(
    characterId: CharacterId,
    currentSimulationTime: SimulationSeconds = SimulationSeconds(world.clock.currentInstant.value)
  ): List<ActivityProgressDetails> = synchronized(stateLock) {
    getActiveActivitiesForCharacter(characterId).mapNotNull { activity ->
      getActivityProgressDetails(activity.id, currentSimulationTime)
    }
  }

  fun registerActivityHandler(handler: ActivityHandler) {
    synchronized(stateLock) {
      require(!_activityHandlers.containsKey(handler.activityType)) {
        "Activity handler already registered for type: ${handler.activityType}"
      }
      _activityHandlers[handler.activityType] = handler
    }
  }

  fun getActivityHandler(type: ActivityType): ActivityHandler? = synchronized(stateLock) {
    _activityHandlers[type]
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
      val activeIds = _characterActiveActivities.remove(characterId)
      if (activeIds != null) {
        for (activityId in activeIds) {
          _activities.remove(activityId)
        }
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
    getActivity(activityId)
  }

  fun registerActivity(activity: Activity): Boolean = synchronized(stateLock) {
    registerActivityLocked(activity)
  }

  private fun registerActivityLocked(activity: Activity): Boolean {
    if (_activities.containsKey(activity.id) || _terminalActivitiesHistory.any { it.id == activity.id }) {
      return false
    }

    if (activity.isTerminal) {
      addTerminalActivityLocked(activity)
      return true
    }

    val activeIds = _characterActiveActivities.getOrPut(activity.characterId) { mutableSetOf() }

    if (activity.mode == ActivityMode.BLOCKING) {
      val hasBlocking = activeIds.any { id ->
        val existing = _activities[id]
        existing != null && !existing.isTerminal && existing.mode == ActivityMode.BLOCKING
      }
      if (hasBlocking) {
        return false
      }
    }

    _activities[activity.id] = activity
    activeIds.add(activity.id)
    return true
  }

  fun getActivity(activityId: ActivityId): Activity? = synchronized(stateLock) {
    _activities[activityId] ?: _terminalActivitiesHistory.firstOrNull { it.id == activityId }
  }

  fun getActiveActivityForCharacter(characterId: CharacterId): Activity? = synchronized(stateLock) {
    val activeIds = _characterActiveActivities[characterId] ?: return@synchronized null
    val active = activeIds.mapNotNull { id ->
      val a = _activities[id]
      if (a != null && !a.isTerminal) a else null
    }
    if (active.isEmpty()) return@synchronized null
    active.firstOrNull { it.mode == ActivityMode.BLOCKING } ?: active.singleOrNull()
  }

  fun getActiveActivitiesForCharacter(characterId: CharacterId): List<Activity> = synchronized(stateLock) {
    val activeIds = _characterActiveActivities[characterId] ?: return@synchronized emptyList()
    activeIds.mapNotNull { id ->
      val a = _activities[id]
      if (a != null && !a.isTerminal) a else null
    }
  }

  fun cancelActivity(characterId: CharacterId, activityId: ActivityId): GameActionResult = synchronized(stateLock) {
    val activity = getActivity(activityId) ?: return GameActionResult.Rejected("Activity not found.")

    if (activity.characterId != characterId) {
      return GameActionResult.Rejected("Character '${characterId.value}' does not own activity '${activityId.value}'.")
    }

    if (activity.isTerminal) {
      return GameActionResult.Rejected("Activity '${activityId.value}' is already terminated.")
    }

    val handler = _activityHandlers[activity.type]
    val specificFacts = handler?.cancel(activity, this) ?: emptyList()

    transitionActivityStatus(activityId, ActivityStatus.CANCELLED)

    val cancelled = getActivity(activityId) ?: activity.copy(status = ActivityStatus.CANCELLED)
    val facts = specificFacts + activityLifecycleFact("activity_cancelled", cancelled, this)

    GameActionResult.Success(facts = facts)
  }

  fun transitionActivityStatus(activityId: ActivityId, newStatus: ActivityStatus): Boolean = synchronized(stateLock) {
    val activity = _activities[activityId] ?: return false
    if (activity.isTerminal) {
      return false
    }
    val updated = activity.copy(status = newStatus)
    if (updated.isTerminal) {
      _activities.remove(activityId)
      addTerminalActivityLocked(updated)

      val activeIds = _characterActiveActivities[activity.characterId]
      if (activeIds != null) {
        activeIds.remove(activityId)
        if (activeIds.isEmpty()) {
          _characterActiveActivities.remove(activity.characterId)
        }
      }
      val entityId = WorldEntityId(activity.characterId.value)
      if (_movementActivities[entityId] == activityId) {
        _movementActivities.remove(entityId)
      }
    } else {
      _activities[activityId] = updated
    }
    return true
  }

  private fun addTerminalActivityLocked(activity: Activity) {
    if (_terminalActivitiesHistory.size >= MAX_TERMINAL_ACTIVITIES) {
      _terminalActivitiesHistory.removeFirst()
    }
    _terminalActivitiesHistory.addLast(activity)
  }

  fun getActivityProgress(activityId: ActivityId, currentSimulationTime: SimulationSeconds): Double =
    synchronized(stateLock) {
      val activity = getActivity(activityId) ?: return 0.0
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
    private const val MAX_TERMINAL_ACTIVITIES = 256
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

    if (nucleus.hasBlockingActivity(characterId)) {
      return emptyList()
    }

    val entityId = WorldEntityId(characterId.value)
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
      val movementActivity =
        nucleus.getActiveActivitiesForCharacter(characterId).firstOrNull { it.type == ActivityType.MOVEMENT }
          ?: return@synchronized null
      val details = nucleus.getActivityProgressDetails(movementActivity.id) ?: return@synchronized null
      Triple(details.description, details.progress, details.remainingTime)
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
    if (nucleus.hasBlockingActivity(action.actorId) && action.name != "cancel_activity") {
      val blocking = nucleus.getActiveActivityForCharacter(action.actorId)
      return GameActionResult.Rejected(
        "Character '${action.actorId.value}' is busy with a blocking activity: ${blocking?.description ?: "unknown"}"
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
    val activeActivities = nucleus.characters.flatMap { characterId ->
      nucleus.getActiveActivitiesForCharacter(characterId)
    }

    nucleus.world.advance(WorldDuration(ticks))

    val currentSimulationTime = SimulationSeconds(nucleus.world.clock.currentInstant.value)
    val facts = mutableListOf<GameFact>()

    for (activity in activeActivities) {
      val current = nucleus.getActivity(activity.id) ?: continue
      if (current.isTerminal) continue

      val handler = nucleus.getActivityHandler(current.type) ?: continue
      val progress = nucleus.getActivityProgress(current.id, currentSimulationTime)

      val specificFacts = if (progress >= 1.0) {
        handler.complete(current, nucleus)
      } else {
        handler.progress(current, nucleus)
      }
      facts.addAll(specificFacts)

      val after = nucleus.getActivity(current.id) ?: continue
      if (after.isTerminal) {
        when (after.status) {
          ActivityStatus.COMPLETED -> facts.add(activityLifecycleFact("activity_completed", after, nucleus))
          ActivityStatus.INTERRUPTED -> facts.add(activityLifecycleFact("activity_interrupted", after, nucleus))
          ActivityStatus.FAILED -> facts.add(activityLifecycleFact("activity_failed", after, nucleus))
          ActivityStatus.CANCELLED -> facts.add(activityLifecycleFact("activity_cancelled", after, nucleus))
          else -> Unit
        }
      } else if (progress >= 1.0) {
        nucleus.transitionActivityStatus(current.id, ActivityStatus.COMPLETED)
        val completed = nucleus.getActivity(current.id) ?: current.copy(status = ActivityStatus.COMPLETED)
        facts.add(activityLifecycleFact("activity_completed", completed, nucleus))
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