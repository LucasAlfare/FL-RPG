package com.lucasalfare.flrpg.gamedomain

import java.util.concurrent.ConcurrentHashMap

sealed interface ExplorationOperationResult

data class LookAroundSuccess(
  val area: ExplorationArea, val knownElements: List<ExplorationElement>, val newlyDiscovered: List<ExplorationElement>
) : ExplorationOperationResult

data class ExamineSuccess(
  val area: ExplorationArea, val element: ExplorationElement, val newlyDiscovered: List<ExplorationElement>
) : ExplorationOperationResult

sealed class ExplorationRejection : ExplorationOperationResult {
  data object NoAreaAtLocation : ExplorationRejection()
  data object ElementNotFound : ExplorationRejection()
  data object ElementNotDiscovered : ExplorationRejection()
  data object ElementNotInCurrentArea : ExplorationRejection()
}

data class TransientExplorationResult(
  val areaId: ExplorationAreaId, val locationId: String, val result: ExplorationOperationResult
)

class ExplorationSystem(private val catalog: ExplorationCatalog) {

  private val states = ConcurrentHashMap<CharacterId, CharacterState>()

  fun lookAround(characterId: CharacterId, locationId: String): ExplorationOperationResult {
    val area = catalog.areaByLocationId(locationId) ?: return ExplorationRejection.NoAreaAtLocation
    val state = states.computeIfAbsent(characterId) { CharacterState() }
    synchronized(state) {
      state.observedAreas.add(area.id)
      val newly = mutableListOf<ExplorationElement>()
      for (element in area.elements) {
        if (element.id in area.initialElementIds && state.discoveredElements.add(element.id)) {
          newly.add(element)
        }
      }
      val known = area.elements.filter { it.id in state.discoveredElements }
      val success = LookAroundSuccess(area = area, knownElements = known, newlyDiscovered = newly)
      state.lastResult = TransientExplorationResult(area.id, locationId, success)
      return success
    }
  }

  fun examine(
    characterId: CharacterId, locationId: String, elementId: ExplorationElementId
  ): ExplorationOperationResult {
    val area = catalog.areaByLocationId(locationId) ?: return ExplorationRejection.NoAreaAtLocation
    val element = area.element(elementId) ?: return if (catalog.containsElement(elementId)) {
      ExplorationRejection.ElementNotInCurrentArea
    } else {
      ExplorationRejection.ElementNotFound
    }
    val state = states.computeIfAbsent(characterId) { CharacterState() }
    synchronized(state) {
      if (elementId !in state.discoveredElements) {
        return ExplorationRejection.ElementNotDiscovered
      }
      state.examinedElements.add(elementId)
      val newIds = mutableSetOf<ExplorationElementId>()
      for (link in area.linksFrom(elementId)) {
        if (state.discoveredElements.add(link.to)) {
          newIds.add(link.to)
        }
      }
      val newly = area.elements.filter { it.id in newIds }
      val success = ExamineSuccess(area = area, element = element, newlyDiscovered = newly)
      state.lastResult = TransientExplorationResult(area.id, locationId, success)
      return success
    }
  }

  fun clearLastResult(characterId: CharacterId) {
    states[characterId]?.let { state ->
      synchronized(state) {
        state.lastResult = null
      }
    }
  }

  fun lastResult(characterId: CharacterId): TransientExplorationResult? {
    val state = states[characterId] ?: return null
    synchronized(state) {
      return state.lastResult
    }
  }

  fun lastResultForLocation(characterId: CharacterId, locationId: String): TransientExplorationResult? {
    val result = lastResult(characterId) ?: return null
    return if (result.locationId == locationId) result else null
  }

  fun removeCharacter(characterId: CharacterId) {
    states.remove(characterId)
  }

  fun hasObservedArea(characterId: CharacterId, areaId: ExplorationAreaId): Boolean {
    val state = states[characterId] ?: return false
    synchronized(state) {
      return areaId in state.observedAreas
    }
  }

  fun discoveredElements(characterId: CharacterId, areaId: ExplorationAreaId): List<ExplorationElement> {
    val area = catalog.area(areaId) ?: return emptyList()
    val state = states[characterId] ?: return emptyList()
    synchronized(state) {
      return area.elements.filter { it.id in state.discoveredElements }
    }
  }

  fun isElementDiscovered(characterId: CharacterId, elementId: ExplorationElementId): Boolean {
    val state = states[characterId] ?: return false
    synchronized(state) {
      return elementId in state.discoveredElements
    }
  }

  fun isElementExamined(characterId: CharacterId, elementId: ExplorationElementId): Boolean {
    val state = states[characterId] ?: return false
    synchronized(state) {
      return elementId in state.examinedElements
    }
  }

  fun areaByLocationId(locationId: String): ExplorationArea? = catalog.areaByLocationId(locationId)

  private class CharacterState {
    val observedAreas = mutableSetOf<ExplorationAreaId>()
    val discoveredElements = mutableSetOf<ExplorationElementId>()
    val examinedElements = mutableSetOf<ExplorationElementId>()
    var lastResult: TransientExplorationResult? = null
  }
}