package com.lucasalfare.flrpg.contentcompiler

@JvmInline
value class ContentPackId(val value: String) {
  init {
    require(value.isNotBlank()) { "ContentPackId cannot be blank." }
  }
}

enum class ContentRequirement {
  Mandatory, Optional
}

data class SectionInfo(
  val id: String, val version: Int, val requirement: ContentRequirement
) {
  init {
    require(id.isNotBlank()) { "Section id cannot be blank." }
    require(version > 0) { "Section version must be positive." }
  }
}

data class FeatureInfo(
  val id: String, val version: Int, val requirement: ContentRequirement
) {
  init {
    require(id.isNotBlank()) { "Feature id cannot be blank." }
    require(version > 0) { "Feature version must be positive." }
  }
}

sealed class ContentValue {
  data object NullValue : ContentValue()
  data class BooleanValue(val value: Boolean) : ContentValue()
  data class IntegerValue(val value: Long) : ContentValue()
  data class FloatValue(val value: Double) : ContentValue()
  data class StringValue(val value: String) : ContentValue()
  data class ArrayValue(val value: List<ContentValue>) : ContentValue()
  data class ObjectValue(val value: Map<String, ContentValue>) : ContentValue()
}

data class LocationDefinition(
  val id: String, val displayName: String
) {
  init {
    require(id.isNotBlank()) { "Location id cannot be blank." }
    require(displayName.isNotBlank()) { "Location displayName cannot be blank." }
  }
}

data class ConnectionDefinition(
  val id: String, val from: String, val to: String, val distance: Long
) {
  init {
    require(id.isNotBlank()) { "Connection id cannot be blank." }
    require(from.isNotBlank()) { "Connection from cannot be blank." }
    require(to.isNotBlank()) { "Connection to cannot be blank." }
    require(from != to) { "Connection from and to must be different." }
    require(distance > 0L) { "Connection distance must be positive." }
  }
}

data class ExplorationElementDefinition(
  val id: String, val name: String, val observationDescription: String, val examinationDescription: String
) {
  init {
    require(id.isNotBlank()) { "Exploration element id cannot be blank." }
    require(name.isNotBlank()) { "Exploration element name cannot be blank." }
    require(observationDescription.isNotBlank()) { "Exploration element observationDescription cannot be blank." }
    require(examinationDescription.isNotBlank()) { "Exploration element examinationDescription cannot be blank." }
  }
}

data class DiscoveryLinkDefinition(
  val from: String, val to: String
) {
  init {
    require(from.isNotBlank()) { "Discovery link from cannot be blank." }
    require(to.isNotBlank()) { "Discovery link to cannot be blank." }
    require(from != to) { "Discovery link cannot point to itself." }
  }
}

data class ExplorationAreaDefinition(
  val id: String,
  val name: String,
  val locationId: String,
  val description: String,
  val elements: List<ExplorationElementDefinition>,
  val initialElementIds: Set<String>,
  val discoveryLinks: List<DiscoveryLinkDefinition>
) {
  init {
    require(id.isNotBlank()) { "Exploration area id cannot be blank." }
    require(name.isNotBlank()) { "Exploration area name cannot be blank." }
    require(locationId.isNotBlank()) { "Exploration area locationId cannot be blank." }
    require(description.isNotBlank()) { "Exploration area description cannot be blank." }
  }
}

data class WorldDefinition(
  val locations: List<LocationDefinition>, val connections: List<ConnectionDefinition>
)

data class ExplorationDefinition(
  val areas: List<ExplorationAreaDefinition>
)

data class ContentPack(
  val id: ContentPackId,
  val containerVersion: Int,
  val schemaVersion: Int,
  val sections: List<SectionInfo>,
  val features: List<FeatureInfo>,
  val world: WorldDefinition?,
  val exploration: ExplorationDefinition?,
  val extensions: Map<String, ContentValue> = emptyMap()
) {
  init {
    require(containerVersion > 0) { "Container version must be positive." }
    require(schemaVersion > 0) { "Schema version must be positive." }
  }
}

sealed class ContentPackError {
  abstract val message: String

  data class Authorship(override val message: String) : ContentPackError()
  data class Format(override val message: String) : ContentPackError()
  data class Compatibility(override val message: String) : ContentPackError()
  data class Reading(override val message: String) : ContentPackError()
  data class Cryptography(override val message: String) : ContentPackError()
}