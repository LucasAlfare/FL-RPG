package com.lucasalfare.flrpg.gamedomain

@JvmInline
value class RealityNucleusId(val value: String) {
  init {
    require(value.isNotBlank()) { "RealityNucleusId cannot be blank." }
  }
}

@JvmInline
value class ExplorationAreaId(val value: String) {
  init {
    require(value.isNotBlank()) { "ExplorationAreaId cannot be blank." }
  }
}

@JvmInline
value class ExplorationElementId(val value: String) {
  init {
    require(value.isNotBlank()) { "ExplorationElementId cannot be blank." }
  }
}

data class ExplorationElement(
  val id: ExplorationElementId, val name: String, val observationDescription: String, val examinationDescription: String
) {
  init {
    require(name.isNotBlank()) { "ExplorationElement name cannot be blank." }
    require(observationDescription.isNotBlank()) { "ExplorationElement observationDescription cannot be blank." }
    require(examinationDescription.isNotBlank()) { "ExplorationElement examinationDescription cannot be blank." }
  }
}

data class DiscoveryLink(
  val from: ExplorationElementId, val to: ExplorationElementId
) {
  init {
    require(from != to) { "DiscoveryLink cannot point to itself." }
  }
}

data class ExplorationArea(
  val id: ExplorationAreaId,
  val name: String,
  val realityNucleusId: RealityNucleusId,
  val locationId: String,
  val description: String,
  val elements: List<ExplorationElement>,
  val initialElementIds: Set<ExplorationElementId>,
  val discoveryLinks: List<DiscoveryLink>
) {
  init {
    require(name.isNotBlank()) { "ExplorationArea name cannot be blank." }
    require(locationId.isNotBlank()) { "ExplorationArea locationId cannot be blank." }
    require(description.isNotBlank()) { "ExplorationArea description cannot be blank." }

    val elementIds = elements.map { it.id }
    require(elementIds.size == elementIds.toSet().size) {
      "ExplorationArea '${id.value}' has duplicate element ids."
    }

    val elementIdSet = elementIds.toSet()
    require(initialElementIds.all { it in elementIdSet }) {
      "ExplorationArea '${id.value}' has initial element ids not present in elements."
    }

    val linkSet = mutableSetOf<Pair<ExplorationElementId, ExplorationElementId>>()
    for (link in discoveryLinks) {
      require(link.from in elementIdSet) {
        "ExplorationArea '${id.value}' has discovery link from unknown element '${link.from.value}'."
      }
      require(link.to in elementIdSet) {
        "ExplorationArea '${id.value}' has discovery link to unknown element '${link.to.value}'."
      }
      val key = link.from to link.to
      require(key !in linkSet) {
        "ExplorationArea '${id.value}' has duplicate discovery link ${link.from.value} -> ${link.to.value}."
      }
      linkSet.add(key)
    }

    if (elementIdSet.isNotEmpty()) {
      require(initialElementIds.isNotEmpty()) {
        "ExplorationArea '${id.value}' has elements but no initial elements."
      }
      val reachable = reachableElementIds(initialElementIds, discoveryLinks)
      val unreachable = elementIdSet - reachable
      require(unreachable.isEmpty()) {
        "ExplorationArea '${id.value}' has unreachable elements: ${unreachable.joinToString { it.value }}."
      }
    }
  }

  val elementsById: Map<ExplorationElementId, ExplorationElement> = elements.associateBy { it.id }

  fun element(id: ExplorationElementId): ExplorationElement? = elementsById[id]

  fun linksFrom(id: ExplorationElementId): List<DiscoveryLink> = discoveryLinks.filter { it.from == id }

  companion object {
    private fun reachableElementIds(
      initials: Set<ExplorationElementId>, links: List<DiscoveryLink>
    ): Set<ExplorationElementId> {
      val adjacency = links.groupBy({ it.from }, { it.to })
      val visited = mutableSetOf<ExplorationElementId>()
      val queue = ArrayDeque<ExplorationElementId>()
      initials.forEach {
        if (visited.add(it)) queue.add(it)
      }
      while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        for (next in adjacency[current].orEmpty()) {
          if (visited.add(next)) queue.add(next)
        }
      }
      return visited
    }
  }
}

class ExplorationCatalog(areas: List<ExplorationArea> = emptyList()) {
  private val areasById: Map<ExplorationAreaId, ExplorationArea>
  private val areasByLocationId: Map<String, ExplorationArea>
  private val elementsById: Map<ExplorationElementId, ExplorationElement>
  private val areaOfElement: Map<ExplorationElementId, ExplorationAreaId>
  private val orderedAreas: List<ExplorationArea>

  init {
    val areaIds = areas.map { it.id }
    require(areaIds.size == areaIds.toSet().size) { "ExplorationCatalog has duplicate area ids." }

    val locationIds = areas.map { it.locationId }
    require(locationIds.size == locationIds.toSet().size) {
      "ExplorationCatalog has duplicate location ids."
    }

    val allElementIds = areas.flatMap { area -> area.elements.map { it.id } }
    require(allElementIds.size == allElementIds.toSet().size) {
      "ExplorationCatalog has duplicate element ids across areas."
    }

    orderedAreas = areas
    areasById = areas.associateBy { it.id }
    areasByLocationId = areas.associateBy { it.locationId }
    elementsById = areas.flatMap { it.elements }.associateBy { it.id }
    areaOfElement = areas.flatMap { area ->
      area.elements.map { it.id to area.id }
    }.toMap()
  }

  val isEmpty: Boolean get() = orderedAreas.isEmpty()

  val areaCount: Int get() = orderedAreas.size

  fun areas(): List<ExplorationArea> = orderedAreas

  fun area(id: ExplorationAreaId): ExplorationArea? = areasById[id]

  fun areaByLocationId(locationId: String): ExplorationArea? = areasByLocationId[locationId]

  fun element(id: ExplorationElementId): ExplorationElement? = elementsById[id]

  fun areaIdOf(elementId: ExplorationElementId): ExplorationAreaId? = areaOfElement[elementId]

  fun containsArea(id: ExplorationAreaId): Boolean = areasById.containsKey(id)

  fun containsElement(id: ExplorationElementId): Boolean = elementsById.containsKey(id)

  companion object {
    val EMPTY = ExplorationCatalog()
  }
}