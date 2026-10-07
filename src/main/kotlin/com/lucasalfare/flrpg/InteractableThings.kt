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

@JvmInline
value class InteractionId(val value: String) {
  init {
    require(value.isNotBlank()) { "InteractionId cannot be blank" }
  }
}

interface InteractionContext {
  fun findThing(id: ThingId): Thing?
  fun findParticipant(id: ParticipantId): Participant?
}

fun interface InteractionCondition {
  fun isSatisfied(interaction: Interaction, context: InteractionContext): Boolean
}

class AllInteractionConditions(
  private val conditions: List<InteractionCondition>
) : InteractionCondition {
  override fun isSatisfied(interaction: Interaction, context: InteractionContext): Boolean {
    return conditions.all { it.isSatisfied(interaction, context) }
  }
}

class AnyInteractionConditions(
  private val conditions: List<InteractionCondition>
) : InteractionCondition {
  override fun isSatisfied(interaction: Interaction, context: InteractionContext): Boolean {
    return conditions.any { it.isSatisfied(interaction, context) }
  }
}

interface Interaction {
  val id: InteractionId
  val capabilityId: CapabilityId
  val targetId: ThingId
  val participantId: ParticipantId
  val conditions: Set<InteractionCondition>

  fun isAvailable(context: InteractionContext): Boolean {
    return conditions.all { it.isSatisfied(this, context) }
  }
}

class BasicInteraction(
  override val id: InteractionId,
  override val capabilityId: CapabilityId,
  override val targetId: ThingId,
  override val participantId: ParticipantId,
  override val conditions: Set<InteractionCondition> = emptySet()
) : Interaction