package com.lucasalfare.flrpg.gamedomain

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