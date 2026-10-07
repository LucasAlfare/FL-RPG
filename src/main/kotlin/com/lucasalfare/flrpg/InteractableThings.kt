package com.lucasalfare.flrpg

@JvmInline
value class ThingId(val value: String) {
  init {
    require(value.isNotBlank()) { "ThingId cannot be blank" }
  }
}

interface ThingState

interface Thing {
  val id: ThingId
  val state: ThingState
  fun updateState(newState: ThingState)
}

class BasicThing(
  override val id: ThingId,
  private var currentState: ThingState
) : Thing {
  override val state: ThingState
    get() = currentState

  override fun updateState(newState: ThingState) {
    currentState = newState
  }
}