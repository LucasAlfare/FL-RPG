package com.lucasalfare.flrpg.server

import com.lucasalfare.flrpg.contentcompiler.*
import com.lucasalfare.flrpg.gamedomain.*

object ContentNucleusFactory {
  fun create(
    pack: ContentPack, nucleusId: NucleusId, realityNucleusId: RealityNucleusId
  ): Nucleus {
    val catalog = buildExplorationCatalog(pack, realityNucleusId)
    val nucleus = Nucleus(id = nucleusId, explorationCatalog = catalog)

    val world = pack.world
    if (world != null) {
      registerLocations(nucleus, world.locations)
      registerConnections(nucleus, world.connections)
    }

    return nucleus
  }

  private fun buildExplorationCatalog(
    pack: ContentPack, realityNucleusId: RealityNucleusId
  ): ExplorationCatalog {
    val areas = pack.exploration?.areas?.map { areaDef ->
      toExplorationArea(areaDef, realityNucleusId)
    } ?: emptyList()
    return ExplorationCatalog(areas)
  }

  private fun toExplorationArea(
    def: ExplorationAreaDefinition, realityNucleusId: RealityNucleusId
  ): ExplorationArea {
    val elements = def.elements.map { toExplorationElement(it) }
    val initialElementIds = def.initialElementIds.map { ExplorationElementId(it) }.toSet()
    val discoveryLinks = def.discoveryLinks.map {
      DiscoveryLink(
        from = ExplorationElementId(it.from), to = ExplorationElementId(it.to)
      )
    }

    return ExplorationArea(
      id = ExplorationAreaId(def.id),
      name = def.name,
      realityNucleusId = realityNucleusId,
      locationId = def.locationId,
      description = def.description,
      elements = elements,
      initialElementIds = initialElementIds,
      discoveryLinks = discoveryLinks
    )
  }

  private fun toExplorationElement(def: ExplorationElementDefinition): ExplorationElement {
    return ExplorationElement(
      id = ExplorationElementId(def.id),
      name = def.name,
      observationDescription = def.observationDescription,
      examinationDescription = def.examinationDescription
    )
  }

  private fun registerLocations(nucleus: Nucleus, locations: List<LocationDefinition>) {
    for (location in locations) {
      nucleus.registerLocation(location.id, location.displayName)
    }
  }

  private fun registerConnections(nucleus: Nucleus, connections: List<ConnectionDefinition>) {
    for (connection in connections) {
      nucleus.connectLocations(
        fromId = connection.from, toId = connection.to, connectionId = connection.id, distance = connection.distance
      )
    }
  }
}