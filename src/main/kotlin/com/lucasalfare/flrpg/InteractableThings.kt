package com.lucasalfare.flrpg

@JvmInline
value class ThingId(val value: String) {
  init {
    require(value.isNotBlank()) { "ThingId cannot be blank" }
  }
}

interface ThingState

@JvmInline
value class CapabilityId(val value: String) {
  init {
    require(value.isNotBlank()) { "CapabilityId cannot be blank" }
  }
}

interface Capability {
  val id: CapabilityId
}

interface Thing {
  val id: ThingId
  val state: ThingState
  val capabilities: Set<Capability>
  fun updateState(newState: ThingState)
}

class BasicThing(
  override val id: ThingId,
  private var currentState: ThingState,
  override val capabilities: Set<Capability> = emptySet()
) : Thing {
  override val state: ThingState
    get() = currentState

  override fun updateState(newState: ThingState) {
    currentState = newState
  }
}

@JvmInline
value class ParticipantId(val value: String) {
  init {
    require(value.isNotBlank()) { "ParticipantId cannot be blank" }
  }
}

interface Participant {
  val id: ParticipantId
}

class BasicParticipant(
  override val id: ParticipantId
) : Participant