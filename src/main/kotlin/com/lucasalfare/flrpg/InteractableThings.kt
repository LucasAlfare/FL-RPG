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
  fun getContainmentRegistry(): ContainmentRegistry? = null
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

class ContainmentRegistry {
  private val parentToChildren = mutableMapOf<ThingId, MutableSet<ThingId>>()
  private val childToParent = mutableMapOf<ThingId, ThingId>()

  fun add(containerId: ThingId, contentId: ThingId): Boolean {
    if (containerId == contentId) return false
    if (isDescendantOf(containerId, contentId)) return false

    removeFromParent(contentId)

    parentToChildren.getOrPut(containerId) { mutableSetOf() }.add(contentId)
    childToParent[contentId] = containerId
    return true
  }

  fun remove(contentId: ThingId): Boolean {
    return removeFromParent(contentId)
  }

  fun move(contentId: ThingId, newContainerId: ThingId): Boolean {
    return add(newContainerId, contentId)
  }

  fun findContainerOf(contentId: ThingId): ThingId? {
    return childToParent[contentId]
  }

  fun findContentsOf(containerId: ThingId): Set<ThingId> {
    return parentToChildren[containerId]?.toSet() ?: emptySet()
  }

  private fun removeFromParent(contentId: ThingId): Boolean {
    val currentContainer = childToParent.remove(contentId) ?: return false
    parentToChildren[currentContainer]?.remove(contentId)
    if (parentToChildren[currentContainer]?.isEmpty() == true) {
      parentToChildren.remove(currentContainer)
    }
    return true
  }

  private fun isDescendantOf(targetId: ThingId, potentialAncestorId: ThingId): Boolean {
    var current = childToParent[targetId]
    while (current != null) {
      if (current == potentialAncestorId) return true
      current = childToParent[current]
    }
    return false
  }
}

data class CompositeThingInitializer(
  val thing: Thing, val initialContents: List<CompositeThingInitializer> = emptyList()
) {
  fun build(registry: ContainmentRegistry): Thing {
    fun registerNode(node: CompositeThingInitializer) {
      node.initialContents.forEach { childNode ->
        registry.add(node.thing.id, childNode.thing.id)
        registerNode(childNode)
      }
    }
    registerNode(this)
    return thing
  }
}

enum class ExposureStatus {
  EXPOSED, UNEXPOSED
}

fun interface ExposureRule {
  fun evaluateExposure(contentId: ThingId, context: InteractionContext): ExposureStatus
}

class ContainerStateExposureRule(
  private val isContainerExposing: (ThingState) -> Boolean
) : ExposureRule {
  override fun evaluateExposure(contentId: ThingId, context: InteractionContext): ExposureStatus {
    val registry = context.getContainmentRegistry() ?: return ExposureStatus.EXPOSED
    val containerId = registry.findContainerOf(contentId) ?: return ExposureStatus.EXPOSED
    val container = context.findThing(containerId) ?: return ExposureStatus.EXPOSED

    return if (isContainerExposing(container.state)) {
      ExposureStatus.EXPOSED
    } else {
      ExposureStatus.UNEXPOSED
    }
  }
}

@JvmInline
value class InformationId(val value: String) {
  init {
    require(value.isNotBlank()) { "InformationId cannot be blank" }
  }
}

enum class InformationLevel {
  BASIC, DETAILED, THOROUGH
}

interface ObservableInformation {
  val id: InformationId
  val level: InformationLevel
}

data class BasicObservableInformation(
  override val id: InformationId, override val level: InformationLevel
) : ObservableInformation

interface InformationHolder {
  val informationEntries: Set<ObservableInformation>

  fun getInformationForLevel(level: InformationLevel): Set<ObservableInformation> {
    return informationEntries.filter { it.level == level }.toSet()
  }
}

interface ObservationContext {
  val observerParticipantId: ParticipantId
  val observerThingId: ThingId?
  val range: Int
  fun getInteractionContext(): InteractionContext
}

data class BasicObservationContext(
  override val observerParticipantId: ParticipantId,
  override val observerThingId: ThingId? = null,
  override val range: Int = 0,
  private val interactionContext: InteractionContext
) : ObservationContext {
  override fun getInteractionContext(): InteractionContext = interactionContext
}