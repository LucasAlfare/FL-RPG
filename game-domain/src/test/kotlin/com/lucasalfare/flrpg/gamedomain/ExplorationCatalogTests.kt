package com.lucasalfare.flrpg.gamedomain

import kotlin.test.*

class ExplorationCatalogTests {

  private val earth = RealityNucleusId("earth_2026")
  private val treeId = ExplorationElementId("tree")
  private val rocksId = ExplorationElementId("rocks")
  private val rootsId = ExplorationElementId("roots")
  private val openingId = ExplorationElementId("hidden_opening")
  private val areaId = ExplorationAreaId("spawn_area")

  private fun element(
    id: ExplorationElementId,
    name: String = id.value,
    observation: String = "Observation of ${id.value}.",
    examination: String = "Examination of ${id.value}."
  ) = ExplorationElement(id, name, observation, examination)

  private fun area(
    id: ExplorationAreaId = areaId,
    name: String = "Spawn Grove",
    reality: RealityNucleusId = earth,
    locationId: String = "spawn",
    description: String = "A quiet grove.",
    elements: List<ExplorationElement> = listOf(
      element(treeId, "Tree"),
      element(rocksId, "Rocks"),
      element(rootsId, "Exposed Roots"),
      element(openingId, "Hidden Opening")
    ),
    initialElementIds: Set<ExplorationElementId> = setOf(treeId, rocksId),
    discoveryLinks: List<DiscoveryLink> = listOf(
      DiscoveryLink(treeId, rootsId), DiscoveryLink(rootsId, openingId), DiscoveryLink(rocksId, openingId)
    )
  ) = ExplorationArea(id, name, reality, locationId, description, elements, initialElementIds, discoveryLinks)

  // --- valid catalogs ---

  @Test
  fun emptyCatalogIsValid() {
    val catalog = ExplorationCatalog()
    assertTrue(catalog.isEmpty)
    assertEquals(0, catalog.areaCount)
    assertEquals(emptyList(), catalog.areas())
    assertNull(catalog.area(areaId))
    assertNull(catalog.areaByLocationId("spawn"))
    assertNull(catalog.element(treeId))
    assertFalse(catalog.containsArea(areaId))
    assertFalse(catalog.containsElement(treeId))
    assertEquals(ExplorationCatalog.EMPTY.areaCount, catalog.areaCount)
  }

  @Test
  fun validConvergingGraphIsAccepted() {
    val a = area()
    val catalog = ExplorationCatalog(listOf(a))
    assertFalse(catalog.isEmpty)
    assertEquals(1, catalog.areaCount)
    assertEquals(listOf(a), catalog.areas())
    assertEquals(a, catalog.area(areaId))
    assertEquals(a, catalog.areaByLocationId("spawn"))
    assertEquals(a.elementsById[treeId], catalog.element(treeId))
    assertEquals(areaId, catalog.areaIdOf(openingId))
    assertTrue(catalog.containsArea(areaId))
    assertTrue(catalog.containsElement(rootsId))
  }

  @Test
  fun validCyclicGraphIsAccepted() {
    val a = element(ExplorationElementId("a"), "A")
    val b = element(ExplorationElementId("b"), "B")
    val c = element(ExplorationElementId("c"), "C")
    val area = ExplorationArea(
      id = ExplorationAreaId("cycle_area"),
      name = "Cycle",
      realityNucleusId = earth,
      locationId = "cycle_loc",
      description = "A cycle.",
      elements = listOf(a, b, c),
      initialElementIds = setOf(a.id),
      discoveryLinks = listOf(
        DiscoveryLink(a.id, b.id), DiscoveryLink(b.id, c.id), DiscoveryLink(c.id, a.id)
      )
    )
    val catalog = ExplorationCatalog(listOf(area))
    assertEquals(1, catalog.areaCount)
    assertNotNull(catalog.area(area.id))
    assertTrue(catalog.containsElement(c.id))
  }

  @Test
  fun multipleAreasAreAcceptedWhenIdsAndLocationsAreUnique() {
    val area1 = area(
      id = ExplorationAreaId("area_1"),
      locationId = "loc_1",
      elements = listOf(element(treeId, "Tree")),
      initialElementIds = setOf(treeId),
      discoveryLinks = emptyList()
    )
    val area2 = area(
      id = ExplorationAreaId("area_2"),
      locationId = "loc_2",
      elements = listOf(element(rocksId, "Rocks")),
      initialElementIds = setOf(rocksId),
      discoveryLinks = emptyList()
    )
    val catalog = ExplorationCatalog(listOf(area1, area2))
    assertEquals(2, catalog.areaCount)
    assertEquals(area1, catalog.areaByLocationId("loc_1"))
    assertEquals(area2, catalog.areaByLocationId("loc_2"))
    assertEquals(area1.id, catalog.areaIdOf(treeId))
    assertEquals(area2.id, catalog.areaIdOf(rocksId))
  }

  // --- identity / blank validation ---

  @Test
  fun blankRealityNucleusIdIsRejected() {
    assertFailsWith<IllegalArgumentException> { RealityNucleusId("") }
    assertFailsWith<IllegalArgumentException> { RealityNucleusId("   ") }
  }

  @Test
  fun blankAreaIdIsRejected() {
    assertFailsWith<IllegalArgumentException> { ExplorationAreaId("") }
  }

  @Test
  fun blankElementIdIsRejected() {
    assertFailsWith<IllegalArgumentException> { ExplorationElementId("") }
  }

  @Test
  fun blankElementTextsAreRejected() {
    assertFailsWith<IllegalArgumentException> {
      ExplorationElement(treeId, "", "obs", "exam")
    }
    assertFailsWith<IllegalArgumentException> {
      ExplorationElement(treeId, "Tree", "", "exam")
    }
    assertFailsWith<IllegalArgumentException> {
      ExplorationElement(treeId, "Tree", "obs", "")
    }
  }

  @Test
  fun blankAreaTextsAreRejected() {
    assertFailsWith<IllegalArgumentException> {
      area(name = "")
    }
    assertFailsWith<IllegalArgumentException> {
      area(locationId = "")
    }
    assertFailsWith<IllegalArgumentException> {
      area(description = "")
    }
  }

  // --- area-level structural validation ---

  @Test
  fun duplicateElementIdsInSameAreaAreRejected() {
    assertFailsWith<IllegalArgumentException> {
      area(
        elements = listOf(element(treeId), element(treeId, "Other Tree")),
        initialElementIds = setOf(treeId),
        discoveryLinks = emptyList()
      )
    }
  }

  @Test
  fun initialElementIdsNotInElementsAreRejected() {
    assertFailsWith<IllegalArgumentException> {
      area(
        elements = listOf(element(treeId)), initialElementIds = setOf(treeId, rocksId), discoveryLinks = emptyList()
      )
    }
  }

  @Test
  fun discoveryLinkFromUnknownElementIsRejected() {
    assertFailsWith<IllegalArgumentException> {
      area(
        elements = listOf(element(treeId), element(rootsId)),
        initialElementIds = setOf(treeId),
        discoveryLinks = listOf(DiscoveryLink(rocksId, rootsId))
      )
    }
  }

  @Test
  fun discoveryLinkToUnknownElementIsRejected() {
    assertFailsWith<IllegalArgumentException> {
      area(
        elements = listOf(element(treeId), element(rootsId)),
        initialElementIds = setOf(treeId),
        discoveryLinks = listOf(DiscoveryLink(treeId, openingId))
      )
    }
  }

  @Test
  fun duplicateDiscoveryLinksAreRejected() {
    assertFailsWith<IllegalArgumentException> {
      area(
        elements = listOf(element(treeId), element(rootsId)),
        initialElementIds = setOf(treeId),
        discoveryLinks = listOf(
          DiscoveryLink(treeId, rootsId), DiscoveryLink(treeId, rootsId)
        )
      )
    }
  }

  @Test
  fun selfLinkIsRejected() {
    assertFailsWith<IllegalArgumentException> {
      DiscoveryLink(treeId, treeId)
    }
  }

  @Test
  fun elementsWithoutInitialElementsAreRejected() {
    assertFailsWith<IllegalArgumentException> {
      area(
        elements = listOf(element(treeId)), initialElementIds = emptySet(), discoveryLinks = emptyList()
      )
    }
  }

  @Test
  fun unreachableElementsAreRejected() {
    assertFailsWith<IllegalArgumentException> {
      area(
        elements = listOf(
          element(treeId), element(rootsId), element(openingId)
        ), initialElementIds = setOf(treeId), discoveryLinks = listOf(DiscoveryLink(treeId, rootsId))
      )
    }
  }

  @Test
  fun emptyElementsWithEmptyInitialsIsAccepted() {
    val emptyArea = ExplorationArea(
      id = ExplorationAreaId("empty_area"),
      name = "Empty",
      realityNucleusId = earth,
      locationId = "empty_loc",
      description = "Nothing here.",
      elements = emptyList(),
      initialElementIds = emptySet(),
      discoveryLinks = emptyList()
    )
    val catalog = ExplorationCatalog(listOf(emptyArea))
    assertEquals(1, catalog.areaCount)
    assertEquals(emptyArea, catalog.area(emptyArea.id))
  }

  // --- catalog-level structural validation ---

  @Test
  fun duplicateAreaIdsAreRejected() {
    val a1 = area(id = ExplorationAreaId("same"), locationId = "loc_a")
    val a2 = area(
      id = ExplorationAreaId("same"),
      locationId = "loc_b",
      elements = listOf(element(ExplorationElementId("other"))),
      initialElementIds = setOf(ExplorationElementId("other")),
      discoveryLinks = emptyList()
    )
    assertFailsWith<IllegalArgumentException> {
      ExplorationCatalog(listOf(a1, a2))
    }
  }

  @Test
  fun duplicateLocationIdsAreRejected() {
    val a1 = area(id = ExplorationAreaId("area_a"), locationId = "shared")
    val a2 = area(
      id = ExplorationAreaId("area_b"),
      locationId = "shared",
      elements = listOf(element(ExplorationElementId("other"))),
      initialElementIds = setOf(ExplorationElementId("other")),
      discoveryLinks = emptyList()
    )
    assertFailsWith<IllegalArgumentException> {
      ExplorationCatalog(listOf(a1, a2))
    }
  }

  @Test
  fun duplicateElementIdsAcrossAreasAreRejected() {
    val a1 = area(
      id = ExplorationAreaId("area_a"),
      locationId = "loc_a",
      elements = listOf(element(treeId)),
      initialElementIds = setOf(treeId),
      discoveryLinks = emptyList()
    )
    val a2 = area(
      id = ExplorationAreaId("area_b"),
      locationId = "loc_b",
      elements = listOf(element(treeId, "Same Id Tree")),
      initialElementIds = setOf(treeId),
      discoveryLinks = emptyList()
    )
    assertFailsWith<IllegalArgumentException> {
      ExplorationCatalog(listOf(a1, a2))
    }
  }

  // --- query helpers ---

  @Test
  fun areaQueriesReturnNullForUnknownIds() {
    val catalog = ExplorationCatalog(listOf(area()))
    assertNull(catalog.area(ExplorationAreaId("missing")))
    assertNull(catalog.areaByLocationId("missing"))
    assertNull(catalog.element(ExplorationElementId("missing")))
    assertNull(catalog.areaIdOf(ExplorationElementId("missing")))
    assertFalse(catalog.containsArea(ExplorationAreaId("missing")))
    assertFalse(catalog.containsElement(ExplorationElementId("missing")))
  }

  @Test
  fun areaHelpersExposeElementsAndLinks() {
    val a = area()
    assertEquals(4, a.elements.size)
    assertEquals("Tree", a.element(treeId)?.name)
    assertNull(a.element(ExplorationElementId("missing")))
    val fromTree = a.linksFrom(treeId)
    assertEquals(1, fromTree.size)
    assertEquals(rootsId, fromTree.single().to)
    assertEquals(emptyList(), a.linksFrom(openingId))
  }
}