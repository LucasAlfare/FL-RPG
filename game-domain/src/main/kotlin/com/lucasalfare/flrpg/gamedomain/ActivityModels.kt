package com.lucasalfare.flrpg.gamedomain

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
  val status: ActivityStatus = ActivityStatus.RUNNING,
  val cancellable: Boolean = true
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
  val progress: Double?,
  val remainingTime: SimulationSeconds?,
  val elapsedTime: SimulationSeconds
)