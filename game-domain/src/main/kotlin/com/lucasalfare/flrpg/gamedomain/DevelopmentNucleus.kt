package com.lucasalfare.flrpg.gamedomain

fun buildDevelopmentNucleus(
  id: NucleusId = NucleusId("development")
): Nucleus {
  val tree = ExplorationElement(
    id = ExplorationElementId("tree"),
    name = "Tree",
    observationDescription = "A tall oak stands near the path, its bark rough and weathered.",
    examinationDescription = "Looking closer at the base of the tree, you notice thick roots breaking through the soil."
  )
  val stones = ExplorationElement(
    id = ExplorationElementId("stones"),
    name = "Stones",
    observationDescription = "A cluster of moss-covered stones lies a few paces from the tree.",
    examinationDescription = "Between the stones you spot a narrow gap that seems to lead somewhere."
  )
  val exposedRoots = ExplorationElement(
    id = ExplorationElementId("exposed_roots"),
    name = "Exposed Roots",
    observationDescription = "Thick roots push up through the earth around the oak.",
    examinationDescription = "Parting the roots reveals a dark opening in the ground beneath them."
  )
  val hiddenOpening = ExplorationElement(
    id = ExplorationElementId("hidden_opening"),
    name = "Hidden Opening",
    observationDescription = "A narrow opening in the ground, partially concealed.",
    examinationDescription = "The opening descends into darkness. A cool draft rises from below."
  )

  val spawnArea = ExplorationArea(
    id = ExplorationAreaId("spawn_area"),
    name = "Starting Point",
    realityNucleusId = RealityNucleusId("earth_2026"),
    locationId = "spawn",
    description = "You stand at a quiet clearing where a dirt path begins. An old oak and a scatter of stones mark the spot.",
    elements = listOf(tree, stones, exposedRoots, hiddenOpening),
    initialElementIds = setOf(tree.id, stones.id),
    discoveryLinks = listOf(
      DiscoveryLink(from = tree.id, to = exposedRoots.id),
      DiscoveryLink(from = exposedRoots.id, to = hiddenOpening.id),
      DiscoveryLink(from = stones.id, to = hiddenOpening.id)
    )
  )

  val catalog = ExplorationCatalog(listOf(spawnArea))

  val nucleus = Nucleus(id, explorationCatalog = catalog)

  nucleus.registerLocation("spawn", "Starting Point")
  nucleus.registerLocation("path", "Winding Path")
  nucleus.registerLocation("plaza", "Central Plaza")
  nucleus.registerLocation("city", "Distant City")

  nucleus.connectLocations("spawn", "path", distance = 1L)
  nucleus.connectLocations("path", "spawn", distance = 1L)

  nucleus.connectLocations("path", "plaza", distance = 1L)
  nucleus.connectLocations("plaza", "path", distance = 1L)

  nucleus.connectLocations("plaza", "city", distance = 30L)
  nucleus.connectLocations("city", "plaza", distance = 30L)

  return nucleus
}