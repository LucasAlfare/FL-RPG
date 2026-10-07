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

fun interface InteractionEffect {
  fun apply(interaction: Interaction, context: InteractionContext)
}

class CompositeInteractionEffect(
  private val effects: List<InteractionEffect>
) : InteractionEffect {
  override fun apply(interaction: Interaction, context: InteractionContext) {
    effects.forEach { it.apply(interaction, context) }
  }
}

class ChangeThingStateEffect(
  private val targetId: ThingId, private val newState: ThingState
) : InteractionEffect {
  override fun apply(interaction: Interaction, context: InteractionContext) {
    context.findThing(targetId)?.updateState(newState)
  }
}

sealed interface InteractionResult {
  data class Success(
    val interactionId: InteractionId, val modifiedThingIds: Set<ThingId> = emptySet()
  ) : InteractionResult

  data class Refused(
    val interactionId: InteractionId
  ) : InteractionResult

  data class Invalid(
    val interactionId: InteractionId
  ) : InteractionResult
}

interface Interaction {
  val id: InteractionId
  val capabilityId: CapabilityId
  val targetId: ThingId
  val participantId: ParticipantId
  val conditions: Set<InteractionCondition>
  val effects: Set<InteractionEffect>

  fun isAvailable(context: InteractionContext): Boolean {
    return conditions.all { it.isSatisfied(this, context) }
  }

  fun execute(context: InteractionContext): InteractionResult {
    if (context.findThing(targetId) == null || context.findParticipant(participantId) == null) {
      return InteractionResult.Invalid(id)
    }
    if (!isAvailable(context)) {
      return InteractionResult.Refused(id)
    }
    effects.forEach { it.apply(this, context) }
    return InteractionResult.Success(
      interactionId = id, modifiedThingIds = setOf(targetId)
    )
  }
}

class BasicInteraction(
  override val id: InteractionId,
  override val capabilityId: CapabilityId,
  override val targetId: ThingId,
  override val participantId: ParticipantId,
  override val conditions: Set<InteractionCondition> = emptySet(),
  override val effects: Set<InteractionEffect> = emptySet()
) : Interaction