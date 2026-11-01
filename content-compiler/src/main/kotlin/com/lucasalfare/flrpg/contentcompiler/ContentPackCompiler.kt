package com.lucasalfare.flrpg.contentcompiler

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.io.path.*

object ContentPackCompiler {

  private val toml = Toml(
    inputConfig = TomlInputConfig(
      ignoreUnknownNames = false, allowNullValues = false, allowEmptyValues = false
    )
  )

  fun compile(sourceDir: Path, cryptoConfig: CryptoConfig): ContentPack {
    if (!sourceDir.exists() || !sourceDir.isDirectory()) {
      throw ContentPackException(
        ContentPackError.Authorship("Source directory does not exist or is not a directory: $sourceDir")
      )
    }

    val packToml = decodeFile<PackToml>(sourceDir.resolve("pack.toml"), "pack.toml")
    val world = loadWorld(sourceDir)
    val exploration = loadExploration(sourceDir)

    validateCrossReferences(world, exploration)

    val sections = packToml.sections.map { it.toSectionInfo() }
    val features = packToml.features.map { it.toFeatureInfo() }
    val extensions = packToml.extensions.mapValues { (_, v) -> ContentValue.StringValue(v) }

    return ContentPack(
      id = ContentPackId(packToml.id),
      containerVersion = packToml.containerVersion,
      schemaVersion = packToml.schemaVersion,
      sections = sections,
      features = features,
      world = world,
      exploration = exploration,
      extensions = extensions
    )
  }

  fun compileToFile(sourceDir: Path, outputFile: Path, cryptoConfig: CryptoConfig) {
    val pack = compile(sourceDir, cryptoConfig)
    val bytes = ContentPackCodec.encode(pack, cryptoConfig)
    val parent = outputFile.parent
    if (parent != null && !parent.exists()) {
      Files.createDirectories(parent)
    }
    Files.write(outputFile, bytes)
  }

  private fun loadWorld(sourceDir: Path): WorldDefinition? {
    val worldFile = sourceDir.resolve("world.toml")
    if (!worldFile.exists()) return null
    val worldToml = decodeFile<WorldToml>(worldFile, "world.toml")

    val locations = worldToml.locations.map { it.toLocationDefinition() }
    val locationIds = locations.map { it.id }.toSet()
    if (locations.size != locationIds.size) {
      throw authorship("world.toml: duplicate location ids.")
    }

    val connections = normalizeConnections(worldToml.connections, locationIds)
    return WorldDefinition(locations = locations, connections = connections)
  }

  private fun loadExploration(sourceDir: Path): ExplorationDefinition? {
    val areasDir = sourceDir.resolve("exploration/areas")
    if (!areasDir.exists() || !areasDir.isDirectory()) return null

    val areaFiles = areasDir.listDirectoryEntries("*.toml").filter { it.isRegularFile() }.sortedBy { it.name }

    if (areaFiles.isEmpty()) return null

    val areas = areaFiles.map { file ->
      val areaToml = decodeFile<AreaToml>(file, file.name)
      areaToml.toExplorationAreaDefinition(file.name)
    }

    validateUniqueAreaIds(areas)
    validateUniqueElementIds(areas)

    return ExplorationDefinition(areas = areas)
  }

  private fun normalizeConnections(
    raw: List<ConnectionToml>, locationIds: Set<String>
  ): List<ConnectionDefinition> {
    val result = mutableListOf<ConnectionDefinition>()
    val seenIds = mutableSetOf<String>()
    val seenDirected = mutableSetOf<Pair<String, String>>()

    for (conn in raw) {
      if (conn.id.isBlank()) throw authorship("world.toml: connection id cannot be blank.")
      if (!seenIds.add(conn.id)) {
        throw authorship("world.toml: duplicate connection id '${conn.id}'.")
      }
      if (conn.from.isBlank() || conn.to.isBlank()) {
        throw authorship("world.toml: connection '${conn.id}' has blank from/to.")
      }
      if (conn.from !in locationIds) {
        throw authorship("world.toml: connection '${conn.id}' references unknown location '${conn.from}'.")
      }
      if (conn.to !in locationIds) {
        throw authorship("world.toml: connection '${conn.id}' references unknown location '${conn.to}'.")
      }
      if (conn.from == conn.to) {
        throw authorship("world.toml: connection '${conn.id}' has identical from and to.")
      }
      if (conn.distance <= 0L) {
        throw authorship("world.toml: connection '${conn.id}' distance must be positive.")
      }

      fun addDirected(id: String, from: String, to: String) {
        val key = from to to
        if (key in seenDirected) {
          throw authorship("world.toml: duplicate directed connection $from -> $to.")
        }
        seenDirected += key
        if (id != conn.id && !seenIds.add(id)) {
          throw authorship("world.toml: generated reverse id '$id' collides with existing id.")
        }
        result += ConnectionDefinition(id = id, from = from, to = to, distance = conn.distance)
      }

      addDirected(conn.id, conn.from, conn.to)
      if (conn.bidirectional) {
        addDirected("${conn.id}_rev", conn.to, conn.from)
      }
    }
    return result
  }

  private fun validateUniqueAreaIds(areas: List<ExplorationAreaDefinition>) {
    val ids = areas.map { it.id }
    if (ids.size != ids.toSet().size) {
      val dup = ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
      throw authorship("Duplicate exploration area ids: ${dup.joinToString()}.")
    }
  }

  private fun validateUniqueElementIds(areas: List<ExplorationAreaDefinition>) {
    val all = areas.flatMap { area -> area.elements.map { it.id } }
    if (all.size != all.toSet().size) {
      val dup = all.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
      throw authorship("Duplicate exploration element ids across areas: ${dup.joinToString()}.")
    }
  }

  private fun validateCrossReferences(world: WorldDefinition?, exploration: ExplorationDefinition?) {
    if (world == null || exploration == null) return
    val locationIds = world.locations.map { it.id }.toSet()
    for (area in exploration.areas) {
      if (area.locationId !in locationIds) {
        throw authorship(
          "Exploration area '${area.id}' references unknown location '${area.locationId}'."
        )
      }
    }
    val areaLocationIds = exploration.areas.map { it.locationId }
    if (areaLocationIds.size != areaLocationIds.toSet().size) {
      throw authorship("Multiple exploration areas share the same location_id.")
    }
  }

  private inline fun <reified T> decodeFile(path: Path, label: String): T {
    if (!path.exists() || !path.isRegularFile()) {
      throw ContentPackException(
        ContentPackError.Authorship("Required file missing or not a regular file: $label")
      )
    }
    val text = path.readText()
    return try {
      toml.decodeFromString<T>(text)
    } catch (e: Exception) {
      throw ContentPackException(
        ContentPackError.Authorship("Failed to parse $label: ${e.message}")
      )
    }
  }

  private fun authorship(message: String): ContentPackException {
    return ContentPackException(ContentPackError.Authorship(message))
  }

  // ---------------------------------------------------------------------------
  // Private TOML DTOs (only used for decoding; never exposed)
  // ---------------------------------------------------------------------------

  @Serializable
  private data class PackToml(
    val id: String,
    @SerialName("container_version") val containerVersion: Int,
    @SerialName("schema_version") val schemaVersion: Int,
    val sections: List<SectionToml> = emptyList(),
    val features: List<FeatureToml> = emptyList(),
    val extensions: Map<String, String> = emptyMap()
  )

  @Serializable
  private data class SectionToml(
    val id: String, val version: Int, val requirement: String
  ) {
    fun toSectionInfo(): SectionInfo {
      if (id.isBlank()) throw authorship("Section id cannot be blank.")
      if (version <= 0) throw authorship("Section version must be positive.")
      val req = when (requirement) {
        "Mandatory" -> ContentRequirement.Mandatory
        "Optional" -> ContentRequirement.Optional
        else -> throw authorship("Section requirement must be 'Mandatory' or 'Optional', got '$requirement'.")
      }
      return SectionInfo(id = id, version = version, requirement = req)
    }
  }

  @Serializable
  private data class FeatureToml(
    val id: String, val version: Int, val requirement: String
  ) {
    fun toFeatureInfo(): FeatureInfo {
      if (id.isBlank()) throw authorship("Feature id cannot be blank.")
      if (version <= 0) throw authorship("Feature version must be positive.")
      val req = when (requirement) {
        "Mandatory" -> ContentRequirement.Mandatory
        "Optional" -> ContentRequirement.Optional
        else -> throw authorship("Feature requirement must be 'Mandatory' or 'Optional', got '$requirement'.")
      }
      return FeatureInfo(id = id, version = version, requirement = req)
    }
  }

  @Serializable
  private data class WorldToml(
    val locations: List<LocationToml> = emptyList(), val connections: List<ConnectionToml> = emptyList()
  )

  @Serializable
  private data class LocationToml(
    val id: String, @SerialName("display_name") val displayName: String
  ) {
    fun toLocationDefinition(): LocationDefinition {
      if (id.isBlank()) throw authorship("Location id cannot be blank.")
      if (displayName.isBlank()) throw authorship("Location display_name cannot be blank.")
      return LocationDefinition(id = id, displayName = displayName)
    }
  }

  @Serializable
  private data class ConnectionToml(
    val id: String, val from: String, val to: String, val distance: Long, val bidirectional: Boolean = false
  )

  @Serializable
  private data class AreaToml(
    val id: String,
    val name: String,
    @SerialName("location_id") val locationId: String,
    val description: String,
    val elements: List<ElementToml> = emptyList(),
    @SerialName("initial_elements") val initialElements: List<String> = emptyList(),
    val discoveries: List<DiscoveryToml> = emptyList()
  ) {
    fun toExplorationAreaDefinition(sourceName: String): ExplorationAreaDefinition {
      if (id.isBlank()) throw authorship("$sourceName: area id cannot be blank.")
      if (name.isBlank()) throw authorship("$sourceName: area name cannot be blank.")
      if (locationId.isBlank()) throw authorship("$sourceName: location_id cannot be blank.")
      if (description.isBlank()) throw authorship("$sourceName: description cannot be blank.")

      val elementDefs = elements.map { it.toExplorationElementDefinition(sourceName) }
      val elementIds = elementDefs.map { it.id }.toSet()
      if (elementDefs.size != elementIds.size) {
        throw authorship("$sourceName: duplicate element ids inside area.")
      }

      val initialSet = initialElements.toSet()
      for (eid in initialSet) {
        if (eid.isBlank()) throw authorship("$sourceName: initial_elements contains blank id.")
        if (eid !in elementIds) {
          throw authorship("$sourceName: initial_elements references unknown element '$eid'.")
        }
      }

      if (elementDefs.isNotEmpty() && initialSet.isEmpty()) {
        throw authorship("$sourceName: area has elements but no initial_elements.")
      }

      val links = discoveries.map { it.toDiscoveryLinkDefinition(sourceName, elementIds) }
      val linkKeys = links.map { it.from to it.to }.toSet()
      if (links.size != linkKeys.size) {
        throw authorship("$sourceName: duplicate discovery links.")
      }

      return ExplorationAreaDefinition(
        id = id,
        name = name,
        locationId = locationId,
        description = description,
        elements = elementDefs,
        initialElementIds = initialSet,
        discoveryLinks = links
      )
    }
  }

  @Serializable
  private data class ElementToml(
    val id: String,
    val name: String,
    @SerialName("observation_description") val observationDescription: String,
    @SerialName("examination_description") val examinationDescription: String
  ) {
    fun toExplorationElementDefinition(sourceName: String): ExplorationElementDefinition {
      if (id.isBlank()) throw authorship("$sourceName: element id cannot be blank.")
      if (name.isBlank()) throw authorship("$sourceName: element name cannot be blank.")
      if (observationDescription.isBlank()) {
        throw authorship("$sourceName: element observation_description cannot be blank.")
      }
      if (examinationDescription.isBlank()) {
        throw authorship("$sourceName: element examination_description cannot be blank.")
      }
      return ExplorationElementDefinition(
        id = id,
        name = name,
        observationDescription = observationDescription,
        examinationDescription = examinationDescription
      )
    }
  }

  @Serializable
  private data class DiscoveryToml(
    val from: String, val to: String
  ) {
    fun toDiscoveryLinkDefinition(sourceName: String, elementIds: Set<String>): DiscoveryLinkDefinition {
      if (from.isBlank() || to.isBlank()) {
        throw authorship("$sourceName: discovery from/to cannot be blank.")
      }
      if (from !in elementIds) {
        throw authorship("$sourceName: discovery from unknown element '$from'.")
      }
      if (to !in elementIds) {
        throw authorship("$sourceName: discovery to unknown element '$to'.")
      }
      if (from == to) {
        throw authorship("$sourceName: discovery cannot point to itself ('$from').")
      }
      return DiscoveryLinkDefinition(from = from, to = to)
    }
  }
}

fun main(args: Array<String>) {
  if (args.size != 2) {
    System.err.println("Usage: ContentPackCompiler <sourceDir> <outputFile>")
    kotlin.system.exitProcess(1)
  }

  val sourceDir = Path.of(args[0])
  val outputFile = Path.of(args[1])

  val keyId = System.getenv("FL_RPG_CONTENT_KEY_ID") ?: run {
    System.err.println("Missing required environment variable FL_RPG_CONTENT_KEY_ID.")
    kotlin.system.exitProcess(1)
  }
  if (keyId.isBlank()) {
    System.err.println("FL_RPG_CONTENT_KEY_ID must not be blank.")
    kotlin.system.exitProcess(1)
  }

  val keyBase64 = System.getenv("FL_RPG_CONTENT_KEY") ?: run {
    System.err.println("Missing required environment variable FL_RPG_CONTENT_KEY.")
    kotlin.system.exitProcess(1)
  }

  val keyBytes = try {
    Base64.getDecoder().decode(keyBase64)
  } catch (e: IllegalArgumentException) {
    System.err.println("FL_RPG_CONTENT_KEY is not valid Base64.")
    kotlin.system.exitProcess(1)
  }

  if (keyBytes.size != 32) {
    System.err.println("FL_RPG_CONTENT_KEY must decode to exactly 32 bytes (AES-256).")
    kotlin.system.exitProcess(1)
  }

  val cryptoConfig = CryptoConfig(keyId = keyId, key = keyBytes)

  try {
    ContentPackCompiler.compileToFile(sourceDir, outputFile, cryptoConfig)
    println("Content pack written to $outputFile")
  } catch (e: ContentPackException) {
    System.err.println("Compilation failed: ${e.error.message}")
    kotlin.system.exitProcess(1)
  } catch (e: Exception) {
    System.err.println("Unexpected error: ${e.message}")
    kotlin.system.exitProcess(1)
  }
}