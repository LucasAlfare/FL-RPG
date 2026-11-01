package com.lucasalfare.flrpg.contentcompiler

data class SupportedSection(
  val id: String, val versions: Set<Int>
) {
  init {
    require(id.isNotBlank()) { "Supported section id cannot be blank." }
    require(versions.isNotEmpty()) { "Supported section versions cannot be empty." }
    require(versions.all { it > 0 }) { "Supported section versions must be positive." }
  }
}

data class SupportedFeature(
  val id: String, val versions: Set<Int>
) {
  init {
    require(id.isNotBlank()) { "Supported feature id cannot be blank." }
    require(versions.isNotEmpty()) { "Supported feature versions cannot be empty." }
    require(versions.all { it > 0 }) { "Supported feature versions must be positive." }
  }
}

data class ApplicationContentSupport(
  val containerVersions: Set<Int>,
  val schemaVersions: Set<Int>,
  val sections: List<SupportedSection>,
  val features: List<SupportedFeature>
) {
  init {
    require(containerVersions.isNotEmpty()) { "Supported container versions cannot be empty." }
    require(schemaVersions.isNotEmpty()) { "Supported schema versions cannot be empty." }
    require(containerVersions.all { it > 0 }) { "Supported container versions must be positive." }
    require(schemaVersions.all { it > 0 }) { "Supported schema versions must be positive." }
  }

  private val sectionById: Map<String, SupportedSection> = sections.associateBy { it.id }
  private val featureById: Map<String, SupportedFeature> = features.associateBy { it.id }

  fun supportsContainerVersion(version: Int): Boolean = version in containerVersions

  fun supportsSchemaVersion(version: Int): Boolean = version in schemaVersions

  fun supportsSection(id: String, version: Int): Boolean {
    val supported = sectionById[id] ?: return false
    return version in supported.versions
  }

  fun isKnownSection(id: String): Boolean = id in sectionById

  fun supportsFeature(id: String, version: Int): Boolean {
    val supported = featureById[id] ?: return false
    return version in supported.versions
  }

  fun isKnownFeature(id: String): Boolean = id in featureById
}

object ContentPackReader {
  private const val SECTION_WORLD = "world"
  private const val SECTION_EXPLORATION = "exploration"

  fun read(
    container: ByteArray, cryptoConfig: CryptoConfig, support: ApplicationContentSupport
  ): ContentPack {
    val pack = try {
      ContentPackCodec.decode(container, cryptoConfig)
    } catch (e: ContentPackException) {
      throw e
    } catch (e: Exception) {
      throw ContentPackException(
        ContentPackError.Reading("Failed to decode content pack: ${e.message}")
      )
    }

    validateCompatibility(pack, support)
    validateStructure(pack)
    return pack
  }

  private fun validateCompatibility(pack: ContentPack, support: ApplicationContentSupport) {
    if (!support.supportsContainerVersion(pack.containerVersion)) {
      throw ContentPackException(
        ContentPackError.Compatibility(
          "Unsupported container version ${pack.containerVersion}. Supported: ${support.containerVersions}."
        )
      )
    }

    if (!support.supportsSchemaVersion(pack.schemaVersion)) {
      throw ContentPackException(
        ContentPackError.Compatibility(
          "Unsupported schema version ${pack.schemaVersion}. Supported: ${support.schemaVersions}."
        )
      )
    }

    val seenSectionIds = mutableSetOf<String>()
    for (section in pack.sections) {
      if (!seenSectionIds.add(section.id)) {
        throw ContentPackException(
          ContentPackError.Format("Duplicate section id: ${section.id}.")
        )
      }

      val known = support.isKnownSection(section.id)
      val versionSupported = support.supportsSection(section.id, section.version)

      when (section.requirement) {
        ContentRequirement.Mandatory -> {
          if (!known || !versionSupported) {
            throw ContentPackException(
              ContentPackError.Compatibility(
                "Mandatory section '${section.id}' version ${section.version} is not supported."
              )
            )
          }
        }

        ContentRequirement.Optional -> {
          // Unknown or unsupported optional sections are allowed; data remains preserved when present.
        }
      }
    }

    val seenFeatureIds = mutableSetOf<String>()
    for (feature in pack.features) {
      if (!seenFeatureIds.add(feature.id)) {
        throw ContentPackException(
          ContentPackError.Format("Duplicate feature id: ${feature.id}.")
        )
      }

      val known = support.isKnownFeature(feature.id)
      val versionSupported = support.supportsFeature(feature.id, feature.version)

      when (feature.requirement) {
        ContentRequirement.Mandatory -> {
          if (!known || !versionSupported) {
            throw ContentPackException(
              ContentPackError.Compatibility(
                "Mandatory feature '${feature.id}' version ${feature.version} is not supported."
              )
            )
          }
        }

        ContentRequirement.Optional -> {
          // Unknown or unsupported optional features are allowed; data remains preserved when present.
        }
      }
    }

    val worldSection = pack.sections.find { it.id == SECTION_WORLD }
    if (worldSection != null && worldSection.requirement == ContentRequirement.Mandatory) {
      if (pack.world == null) {
        throw ContentPackException(
          ContentPackError.Format("Mandatory section 'world' is declared but world data is missing.")
        )
      }
    }

    val explorationSection = pack.sections.find { it.id == SECTION_EXPLORATION }
    if (explorationSection != null && explorationSection.requirement == ContentRequirement.Mandatory) {
      if (pack.exploration == null) {
        throw ContentPackException(
          ContentPackError.Format("Mandatory section 'exploration' is declared but exploration data is missing.")
        )
      }
    }
  }

  private fun validateStructure(pack: ContentPack) {
    val world = pack.world
    if (world != null) {
      validateWorld(world)
    }

    val exploration = pack.exploration
    if (exploration != null) {
      val locationIds = world?.locations?.map { it.id }?.toSet() ?: emptySet()
      validateExploration(exploration, locationIds)
    }
  }

  private fun validateWorld(world: WorldDefinition) {
    if (world.locations.isEmpty()) {
      throw ContentPackException(ContentPackError.Format("World must contain at least one location."))
    }

    val locationIds = mutableSetOf<String>()
    for (location in world.locations) {
      if (!locationIds.add(location.id)) {
        throw ContentPackException(ContentPackError.Format("Duplicate location id: ${location.id}."))
      }
    }

    val connectionIds = mutableSetOf<String>()
    for (connection in world.connections) {
      if (!connectionIds.add(connection.id)) {
        throw ContentPackException(ContentPackError.Format("Duplicate connection id: ${connection.id}."))
      }
      if (connection.from !in locationIds) {
        throw ContentPackException(
          ContentPackError.Format("Connection '${connection.id}' references unknown location '${connection.from}'.")
        )
      }
      if (connection.to !in locationIds) {
        throw ContentPackException(
          ContentPackError.Format("Connection '${connection.id}' references unknown location '${connection.to}'.")
        )
      }
    }
  }

  private fun validateExploration(exploration: ExplorationDefinition, locationIds: Set<String>) {
    if (exploration.areas.isEmpty()) {
      throw ContentPackException(ContentPackError.Format("Exploration must contain at least one area."))
    }

    val areaIds = mutableSetOf<String>()
    for (area in exploration.areas) {
      if (!areaIds.add(area.id)) {
        throw ContentPackException(ContentPackError.Format("Duplicate exploration area id: ${area.id}."))
      }

      if (locationIds.isNotEmpty() && area.locationId !in locationIds) {
        throw ContentPackException(
          ContentPackError.Format(
            "Exploration area '${area.id}' references unknown location '${area.locationId}'."
          )
        )
      }

      val elementIds = mutableSetOf<String>()
      for (element in area.elements) {
        if (!elementIds.add(element.id)) {
          throw ContentPackException(
            ContentPackError.Format("Duplicate element id '${element.id}' in area '${area.id}'.")
          )
        }
      }

      for (initialId in area.initialElementIds) {
        if (initialId !in elementIds) {
          throw ContentPackException(
            ContentPackError.Format(
              "Area '${area.id}' declares initial element '${initialId}' that does not exist in the area."
            )
          )
        }
      }

      for (link in area.discoveryLinks) {
        if (link.from !in elementIds) {
          throw ContentPackException(
            ContentPackError.Format(
              "Discovery link in area '${area.id}' references unknown element '${link.from}'."
            )
          )
        }
        if (link.to !in elementIds) {
          throw ContentPackException(
            ContentPackError.Format(
              "Discovery link in area '${area.id}' references unknown element '${link.to}'."
            )
          )
        }
      }
    }
  }
}