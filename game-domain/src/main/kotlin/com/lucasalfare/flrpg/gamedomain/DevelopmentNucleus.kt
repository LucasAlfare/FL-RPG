package com.lucasalfare.flrpg.gamedomain

fun buildDevelopmentNucleus(
  id: NucleusId = NucleusId("development")
): Nucleus {
  val nucleus = Nucleus(id)

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