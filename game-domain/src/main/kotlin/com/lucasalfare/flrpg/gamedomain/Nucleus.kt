package com.lucasalfare.flrpg.gamedomain

import com.lucasalfare.flworld.*
import java.util.*

class Nucleus(
  val id: NucleusId,
  val world: World = World(WorldId(id.value)),
  explorationCatalog: ExplorationCatalog = ExplorationCatalog.EMPTY
) {
  internal val stateLock = Any()

  val explorationSystem = ExplorationSystem(explorationCatalog)

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

  fun canCancelActivity(characterId: CharacterId, activityId: ActivityId): Boolean = synchronized(stateLock) {
    val activity = getActivity(activityId) ?: return false
    if (activity.characterId != characterId || activity.isTerminal) {
      return false
    }
    if (!activity.cancellable) {
      return false
    }
    val handler = _activityHandlers[activity.type]
    return handler?.isCancellable(activity, this) ?: true
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
    val elapsedValue = (currentSimulationTime.value - activity.startTime.value).coerceAtLeast(0L)
    val elapsedTime = SimulationSeconds(elapsedValue)
    val progress: Double?
    val remainingTime: SimulationSeconds?
    if (activity.duration.isUnknown) {
      progress = null
      remainingTime = null
    } else {
      progress = getActivityProgress(activityId, currentSimulationTime)
      val remainingValue = (activity.duration.value - elapsedValue).coerceAtLeast(0L)
      remainingTime = SimulationSeconds(remainingValue)
    }
    ActivityProgressDetails(
      activityId = activity.id,
      characterId = activity.characterId,
      type = activity.type,
      description = activity.description,
      mode = activity.mode,
      progress = progress,
      remainingTime = remainingTime,
      elapsedTime = elapsedTime
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
      explorationSystem.removeCharacter(characterId)
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

    if (!canCancelActivity(characterId, activityId)) {
      return GameActionResult.Rejected("Activity '${activityId.value}' cannot be cancelled.")
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
      if (activity.duration.isUnknown) return 0.0
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