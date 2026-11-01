package com.lucasalfare.flrpg.contentcompiler

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.*

class ContentPackCompilerTests {

  private val dummyKey = ByteArray(32) { 1 }
  private val dummyCrypto = CryptoConfig(keyId = "test-key", key = dummyKey)

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun tempDir(): Path = Files.createTempDirectory("flcp-compiler-test-")

  private fun write(dir: Path, relative: String, content: String) {
    val file = dir.resolve(relative)
    file.parent?.createDirectories()
    file.writeText(content.trimIndent())
  }

  private fun minimalPackToml(
    id: String = "test_pack", containerVersion: Int = 1, schemaVersion: Int = 1, sections: String = """
      [[sections]]
      id = "world"
      version = 1
      requirement = "Mandatory"

      [[sections]]
      id = "exploration"
      version = 1
      requirement = "Mandatory"
    """.trimIndent(), features: String = "", extensions: String = ""
  ): String = """
    id = "$id"
    container_version = $containerVersion
    schema_version = $schemaVersion
    $sections
    $features
    $extensions
  """.trimIndent()

  private fun minimalWorldToml(): String = """
    [[locations]]
    id = "spawn"
    display_name = "Starting Point"

    [[locations]]
    id = "path"
    display_name = "Winding Path"

    [[connections]]
    id = "spawn_path"
    from = "spawn"
    to = "path"
    distance = 1
    bidirectional = true
  """.trimIndent()

  private fun minimalSpawnAreaToml(): String = """
    id = "spawn_area"
    name = "Starting Point"
    location_id = "spawn"
    description = "You stand at a quiet clearing."

    [[elements]]
    id = "tree"
    name = "Tree"
    observation_description = "A tall oak stands near the path."
    examination_description = "Looking closer at the base of the tree, you notice thick roots."

    [[elements]]
    id = "stones"
    name = "Stones"
    observation_description = "A cluster of moss-covered stones."
    examination_description = "Between the stones you spot a narrow gap."

    initial_elements = ["tree", "stones"]

    [[discoveries]]
    from = "tree"
    to = "stones"
  """.trimIndent()

  private fun createValidSource(dir: Path) {
    write(dir, "pack.toml", minimalPackToml())
    write(dir, "world.toml", minimalWorldToml())
    write(dir, "exploration/areas/spawn.toml", minimalSpawnAreaToml())
  }

  // ---------------------------------------------------------------------------
  // Happy path – full valid pack
  // ---------------------------------------------------------------------------

  @Test
  fun `compiles valid development-like content pack`() {
    val dir = tempDir()
    createValidSource(dir)

    val pack = ContentPackCompiler.compile(dir, dummyCrypto)

    assertEquals("test_pack", pack.id.value)
    assertEquals(1, pack.containerVersion)
    assertEquals(1, pack.schemaVersion)

    assertEquals(2, pack.sections.size)
    assertTrue(pack.sections.any { it.id == "world" && it.requirement == ContentRequirement.Mandatory })
    assertTrue(pack.sections.any { it.id == "exploration" && it.requirement == ContentRequirement.Mandatory })

    val world = assertNotNull(pack.world)
    assertEquals(2, world.locations.size)
    assertEquals(2, world.connections.size) // bidirectional produces two directed edges

    val exploration = assertNotNull(pack.exploration)
    assertEquals(1, exploration.areas.size)
    val area = exploration.areas.single()
    assertEquals("spawn_area", area.id)
    assertEquals("spawn", area.locationId)
    assertEquals(2, area.elements.size)
    assertEquals(setOf("tree", "stones"), area.initialElementIds)
    assertEquals(1, area.discoveryLinks.size)
  }

  // ---------------------------------------------------------------------------
  // Missing / invalid source directory
  // ---------------------------------------------------------------------------

  @Test
  fun `rejects non-existent source directory`() {
    val missing = Path.of("/tmp/flcp-does-not-exist-${System.nanoTime()}")
    val ex = assertFailsWith<ContentPackException> {
      ContentPackCompiler.compile(missing, dummyCrypto)
    }
    assertTrue(ex.error is ContentPackError.Authorship)
    assertTrue(ex.error.message.contains("does not exist") || ex.error.message.contains("not a directory"))
  }

  @Test
  fun `rejects missing pack_toml`() {
    val dir = tempDir()
    write(dir, "world.toml", minimalWorldToml())

    val ex = assertFailsWith<ContentPackException> {
      ContentPackCompiler.compile(dir, dummyCrypto)
    }
    assertTrue(ex.error is ContentPackError.Authorship)
    assertTrue(ex.error.message.contains("pack.toml"))
  }

  // ---------------------------------------------------------------------------
  // Sections & features – requirement and version validation
  // ---------------------------------------------------------------------------

  @Test
  fun `rejects invalid section requirement`() {
    val dir = tempDir()
    write(
      dir, "pack.toml", minimalPackToml(
        sections = """
          [[sections]]
          id = "world"
          version = 1
          requirement = "Required"
        """.trimIndent()
      )
    )
    write(dir, "world.toml", minimalWorldToml())

    val ex = assertFailsWith<ContentPackException> {
      ContentPackCompiler.compile(dir, dummyCrypto)
    }
    assertTrue(ex.error is ContentPackError.Authorship)
    assertTrue(ex.error.message.contains("Mandatory") || ex.error.message.contains("Optional"))
  }

  @Test
  fun `rejects non-positive section version`() {
    val dir = tempDir()
    write(
      dir, "pack.toml", minimalPackToml(
        sections = """
          [[sections]]
          id = "world"
          version = 0
          requirement = "Mandatory"
        """.trimIndent()
      )
    )
    write(dir, "world.toml", minimalWorldToml())

    val ex = assertFailsWith<ContentPackException> {
      ContentPackCompiler.compile(dir, dummyCrypto)
    }
    assertTrue(ex.error is ContentPackError.Authorship)
    assertTrue(ex.error.message.contains("positive") || ex.error.message.contains("version"))
  }

  @Test
  fun `accepts optional feature`() {
    val dir = tempDir()
    write(
      dir, "pack.toml", minimalPackToml(
        features = """
          [[features]]
          id = "weather"
          version = 1
          requirement = "Optional"
        """.trimIndent()
      )
    )
    write(dir, "world.toml", minimalWorldToml())

    val pack = ContentPackCompiler.compile(dir, dummyCrypto)
    assertEquals(1, pack.features.size)
    assertEquals("weather", pack.features.single().id)
    assertEquals(ContentRequirement.Optional, pack.features.single().requirement)
  }

  // ---------------------------------------------------------------------------
  // World – locations & connections
  // ---------------------------------------------------------------------------

  @Test
  fun `normalizes bidirectional connection into two directed edges`() {
    val dir = tempDir()
    write(dir, "pack.toml", minimalPackToml())
    write(
      dir, "world.toml", """
        [[locations]]
        id = "a"
        display_name = "A"

        [[locations]]
        id = "b"
        display_name = "B"

        [[connections]]
        id = "ab"
        from = "a"
        to = "b"
        distance = 5
        bidirectional = true
      """.trimIndent()
    )

    val pack = ContentPackCompiler.compile(dir, dummyCrypto)
    val connections = assertNotNull(pack.world).connections
    assertEquals(2, connections.size)

    val forward = connections.single { it.from == "a" && it.to == "b" }
    val reverse = connections.single { it.from == "b" && it.to == "a" }
    assertEquals(5L, forward.distance)
    assertEquals(5L, reverse.distance)
    assertEquals("ab", forward.id)
    assertEquals("ab_rev", reverse.id)
  }

  @Test
  fun `keeps unidirectional connection as single directed edge`() {
    val dir = tempDir()
    write(dir, "pack.toml", minimalPackToml())
    write(
      dir, "world.toml", """
        [[locations]]
        id = "a"
        display_name = "A"

        [[locations]]
        id = "b"
        display_name = "B"

        [[connections]]
        id = "ab"
        from = "a"
        to = "b"
        distance = 3
        bidirectional = false
      """.trimIndent()
    )

    val pack = ContentPackCompiler.compile(dir, dummyCrypto)
    val connections = assertNotNull(pack.world).connections
    assertEquals(1, connections.size)
    assertEquals("a", connections.single().from)
    assertEquals("b", connections.single().to)
  }

  @Test
  fun `rejects duplicate location ids`() {
    val dir = tempDir()
    write(dir, "pack.toml", minimalPackToml())
    write(
      dir, "world.toml", """
        [[locations]]
        id = "spawn"
        display_name = "One"

        [[locations]]
        id = "spawn"
        display_name = "Two"
      """.trimIndent()
    )

    val ex = assertFailsWith<ContentPackException> {
      ContentPackCompiler.compile(dir, dummyCrypto)
    }
    assertTrue(ex.error is ContentPackError.Authorship)
    assertTrue(ex.error.message.contains("duplicate location"))
  }

  @Test
  fun `rejects connection referencing unknown location`() {
    val dir = tempDir()
    write(dir, "pack.toml", minimalPackToml())
    write(
      dir, "world.toml", """
        [[locations]]
        id = "spawn"
        display_name = "Spawn"

        [[connections]]
        id = "bad"
        from = "spawn"
        to = "nowhere"
        distance = 1
      """.trimIndent()
    )

    val ex = assertFailsWith<ContentPackException> {
      ContentPackCompiler.compile(dir, dummyCrypto)
    }
    assertTrue(ex.error is ContentPackError.Authorship)
    assertTrue(ex.error.message.contains("unknown location"))
  }

  @Test
  fun `rejects connection with non-positive distance`() {
    val dir = tempDir()
    write(dir, "pack.toml", minimalPackToml())
    write(
      dir, "world.toml", """
        [[locations]]
        id = "a"
        display_name = "A"

        [[locations]]
        id = "b"
        display_name = "B"

        [[connections]]
        id = "ab"
        from = "a"
        to = "b"
        distance = 0
      """.trimIndent()
    )

    val ex = assertFailsWith<ContentPackException> {
      ContentPackCompiler.compile(dir, dummyCrypto)
    }
    assertTrue(ex.error is ContentPackError.Authorship)
    assertTrue(ex.error.message.contains("positive"))
  }

  @Test
  fun `rejects duplicate connection ids`() {
    val dir = tempDir()
    write(dir, "pack.toml", minimalPackToml())
    write(
      dir, "world.toml", """
        [[locations]]
        id = "a"
        display_name = "A"

        [[locations]]
        id = "b"
        display_name = "B"

        [[connections]]
        id = "same"
        from = "a"
        to = "b"
        distance = 1

        [[connections]]
        id = "same"
        from = "b"
        to = "a"
        distance = 1
      """.trimIndent()
    )

    val ex = assertFailsWith<ContentPackException> {
      ContentPackCompiler.compile(dir, dummyCrypto)
    }
    assertTrue(ex.error is ContentPackError.Authorship)
    assertTrue(ex.error.message.contains("duplicate connection id"))
  }

  // ---------------------------------------------------------------------------
  // Exploration – areas, elements, discoveries
  // ---------------------------------------------------------------------------

  @Test
  fun `rejects area referencing unknown location`() {
    val dir = tempDir()
    write(dir, "pack.toml", minimalPackToml())
    write(dir, "world.toml", minimalWorldToml())
    write(
      dir, "exploration/areas/spawn.toml", """
        id = "spawn_area"
        name = "Start"
        location_id = "missing_loc"
        description = "Somewhere"
        initial_elements = []
      """.trimIndent()
    )

    val ex = assertFailsWith<ContentPackException> {
      ContentPackCompiler.compile(dir, dummyCrypto)
    }
    assertTrue(ex.error is ContentPackError.Authorship)
    assertTrue(ex.error.message.contains("unknown location"))
  }

  @Test
  fun `rejects duplicate element ids inside area`() {
    val dir = tempDir()
    write(dir, "pack.toml", minimalPackToml())
    write(dir, "world.toml", minimalWorldToml())
    write(
      dir, "exploration/areas/spawn.toml", """
        id = "spawn_area"
        name = "Start"
        location_id = "spawn"
        description = "Desc"

        [[elements]]
        id = "tree"
        name = "Tree"
        observation_description = "obs"
        examination_description = "exam"

        [[elements]]
        id = "tree"
        name = "Another"
        observation_description = "obs2"
        examination_description = "exam2"

        initial_elements = ["tree"]
      """.trimIndent()
    )

    val ex = assertFailsWith<ContentPackException> {
      ContentPackCompiler.compile(dir, dummyCrypto)
    }
    assertTrue(ex.error is ContentPackError.Authorship)
    assertTrue(ex.error.message.contains("duplicate element"))
  }

  @Test
  fun `rejects initial_elements referencing unknown element`() {
    val dir = tempDir()
    write(dir, "pack.toml", minimalPackToml())
    write(dir, "world.toml", minimalWorldToml())
    write(
      dir, "exploration/areas/spawn.toml", """
        id = "spawn_area"
        name = "Start"
        location_id = "spawn"
        description = "Desc"

        [[elements]]
        id = "tree"
        name = "Tree"
        observation_description = "obs"
        examination_description = "exam"

        initial_elements = ["tree", "ghost"]
      """.trimIndent()
    )

    val ex = assertFailsWith<ContentPackException> {
      ContentPackCompiler.compile(dir, dummyCrypto)
    }
    assertTrue(ex.error is ContentPackError.Authorship)
    assertTrue(ex.error.message.contains("unknown element"))
  }

  @Test
  fun `rejects area with elements but empty initial_elements`() {
    val dir = tempDir()
    write(dir, "pack.toml", minimalPackToml())
    write(dir, "world.toml", minimalWorldToml())
    write(
      dir, "exploration/areas/spawn.toml", """
        id = "spawn_area"
        name = "Start"
        location_id = "spawn"
        description = "Desc"

        [[elements]]
        id = "tree"
        name = "Tree"
        observation_description = "obs"
        examination_description = "exam"

        initial_elements = []
      """.trimIndent()
    )

    val ex = assertFailsWith<ContentPackException> {
      ContentPackCompiler.compile(dir, dummyCrypto)
    }
    assertTrue(ex.error is ContentPackError.Authorship)
    assertTrue(ex.error.message.contains("no initial_elements") || ex.error.message.contains("initial_elements"))
  }

  @Test
  fun `rejects discovery referencing unknown element`() {
    val dir = tempDir()
    write(dir, "pack.toml", minimalPackToml())
    write(dir, "world.toml", minimalWorldToml())
    write(
      dir, "exploration/areas/spawn.toml", """
        id = "spawn_area"
        name = "Start"
        location_id = "spawn"
        description = "Desc"

        [[elements]]
        id = "tree"
        name = "Tree"
        observation_description = "obs"
        examination_description = "exam"

        initial_elements = ["tree"]

        [[discoveries]]
        from = "tree"
        to = "missing"
      """.trimIndent()
    )

    val ex = assertFailsWith<ContentPackException> {
      ContentPackCompiler.compile(dir, dummyCrypto)
    }
    assertTrue(ex.error is ContentPackError.Authorship)
    assertTrue(ex.error.message.contains("unknown element"))
  }

  @Test
  fun `rejects self-referencing discovery`() {
    val dir = tempDir()
    write(dir, "pack.toml", minimalPackToml())
    write(dir, "world.toml", minimalWorldToml())
    write(
      dir, "exploration/areas/spawn.toml", """
        id = "spawn_area"
        name = "Start"
        location_id = "spawn"
        description = "Desc"

        [[elements]]
        id = "tree"
        name = "Tree"
        observation_description = "obs"
        examination_description = "exam"

        initial_elements = ["tree"]

        [[discoveries]]
        from = "tree"
        to = "tree"
      """.trimIndent()
    )

    val ex = assertFailsWith<ContentPackException> {
      ContentPackCompiler.compile(dir, dummyCrypto)
    }
    assertTrue(ex.error is ContentPackError.Authorship)
    assertTrue(ex.error.message.contains("itself") || ex.error.message.contains("point to itself"))
  }

  @Test
  fun `rejects duplicate discovery links`() {
    val dir = tempDir()
    write(dir, "pack.toml", minimalPackToml())
    write(dir, "world.toml", minimalWorldToml())
    write(
      dir, "exploration/areas/spawn.toml", """
        id = "spawn_area"
        name = "Start"
        location_id = "spawn"
        description = "Desc"

        [[elements]]
        id = "tree"
        name = "Tree"
        observation_description = "obs"
        examination_description = "exam"

        [[elements]]
        id = "stones"
        name = "Stones"
        observation_description = "obs"
        examination_description = "exam"

        initial_elements = ["tree"]

        [[discoveries]]
        from = "tree"
        to = "stones"

        [[discoveries]]
        from = "tree"
        to = "stones"
      """.trimIndent()
    )

    val ex = assertFailsWith<ContentPackException> {
      ContentPackCompiler.compile(dir, dummyCrypto)
    }
    assertTrue(ex.error is ContentPackError.Authorship)
    assertTrue(ex.error.message.contains("duplicate discovery"))
  }

  // ---------------------------------------------------------------------------
  // Text handling – commas, quotes, newlines
  // ---------------------------------------------------------------------------

  @Test
  fun `preserves text with commas quotes and newlines`() {
    val dir = tempDir()
    write(dir, "pack.toml", minimalPackToml())
    write(dir, "world.toml", minimalWorldToml())

    // Use TOML basic strings + escaped quotes and a real newline via \n
    // to avoid nested """ conflict with the Kotlin raw string.
    val areaContent = """
    id = "spawn_area"
    name = "Starting Point"
    location_id = "spawn"
    description = "A quiet clearing,\nwith \"quotes\" and\nmultiple lines."

    [[elements]]
    id = "tree"
    name = "Oak, tall"
    observation_description = "A tall oak, \"weathered\" and old."
    examination_description = "Looking closer,\nroots break the soil."

    initial_elements = ["tree"]
  """.trimIndent()

    write(dir, "exploration/areas/spawn.toml", areaContent)

    val pack = ContentPackCompiler.compile(dir, dummyCrypto)
    val area = assertNotNull(pack.exploration).areas.single()

    assertTrue(area.description.contains("clearing,"))
    assertTrue(area.description.contains("\"quotes\""))
    assertTrue(area.description.contains("\n") || area.description.contains("multiple lines"))

    val tree = area.elements.single { it.id == "tree" }
    assertTrue(tree.name.contains(","))
    assertTrue(tree.observationDescription.contains("\"weathered\""))
    assertTrue(tree.examinationDescription.contains("Looking closer"))
  }

  // ---------------------------------------------------------------------------
  // Extensions
  // ---------------------------------------------------------------------------

  @Test
  fun `preserves extensions as string values`() {
    val dir = tempDir()
    write(
      dir, "pack.toml", minimalPackToml(
        extensions = """
          [extensions]
          custom_note = "hello world"
          another = "value with, commas"
        """.trimIndent()
      )
    )
    write(dir, "world.toml", minimalWorldToml())

    val pack = ContentPackCompiler.compile(dir, dummyCrypto)
    assertEquals(2, pack.extensions.size)
    assertEquals(ContentValue.StringValue("hello world"), pack.extensions["custom_note"])
    assertEquals(ContentValue.StringValue("value with, commas"), pack.extensions["another"])
  }

  // ---------------------------------------------------------------------------
  // Unknown fields rejection (ignoreUnknownNames = false)
  // ---------------------------------------------------------------------------

  @Test
  fun `rejects unknown fields in pack_toml`() {
    val dir = tempDir()
    write(
      dir, "pack.toml", """
        id = "test"
        container_version = 1
        schema_version = 1
        unknown_top_level = true
      """.trimIndent()
    )
    write(dir, "world.toml", minimalWorldToml())

    val ex = assertFailsWith<ContentPackException> {
      ContentPackCompiler.compile(dir, dummyCrypto)
    }
    assertTrue(ex.error is ContentPackError.Authorship)
    assertTrue(ex.error.message.contains("Failed to parse") || ex.error.message.contains("unknown"))
  }

  @Test
  fun `rejects unknown fields in world_toml`() {
    val dir = tempDir()
    write(dir, "pack.toml", minimalPackToml())
    write(
      dir, "world.toml", """
        [[locations]]
        id = "spawn"
        display_name = "Start"
        extra_field = 42
      """.trimIndent()
    )

    val ex = assertFailsWith<ContentPackException> {
      ContentPackCompiler.compile(dir, dummyCrypto)
    }
    assertTrue(ex.error is ContentPackError.Authorship)
    assertTrue(ex.error.message.contains("Failed to parse") || ex.error.message.contains("unknown"))
  }

  @Test
  fun `rejects unknown fields in area toml`() {
    val dir = tempDir()
    write(dir, "pack.toml", minimalPackToml())
    write(dir, "world.toml", minimalWorldToml())
    write(
      dir, "exploration/areas/spawn.toml", """
        id = "spawn_area"
        name = "Start"
        location_id = "spawn"
        description = "Desc"
        unexpected = "nope"
        initial_elements = []
      """.trimIndent()
    )

    val ex = assertFailsWith<ContentPackException> {
      ContentPackCompiler.compile(dir, dummyCrypto)
    }
    assertTrue(ex.error is ContentPackError.Authorship)
    assertTrue(ex.error.message.contains("Failed to parse") || ex.error.message.contains("unknown"))
  }

  // ---------------------------------------------------------------------------
  // Optional world / exploration
  // ---------------------------------------------------------------------------

  @Test
  fun `compiles pack with only pack_toml and no world or exploration`() {
    val dir = tempDir()
    write(dir, "pack.toml", minimalPackToml(sections = "", features = ""))

    val pack = ContentPackCompiler.compile(dir, dummyCrypto)
    assertNull(pack.world)
    assertNull(pack.exploration)
    assertEquals("test_pack", pack.id.value)
  }

  @Test
  fun `compiles pack with world but no exploration areas directory`() {
    val dir = tempDir()
    write(dir, "pack.toml", minimalPackToml())
    write(dir, "world.toml", minimalWorldToml())

    val pack = ContentPackCompiler.compile(dir, dummyCrypto)
    assertNotNull(pack.world)
    assertNull(pack.exploration)
  }
}