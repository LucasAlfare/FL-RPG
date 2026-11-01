@file:OptIn(ExperimentalSerializationApi::class)

package com.lucasalfare.flrpg.contentcompiler

import com.github.luben.zstd.Zstd
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class CryptoConfig(
  val keyId: String, val key: ByteArray
) {
  init {
    require(keyId.isNotBlank()) { "keyId cannot be blank." }
    require(key.size == 32) { "AES-256 key must be exactly 32 bytes." }
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is CryptoConfig) return false
    return keyId == other.keyId && key.contentEquals(other.key)
  }

  override fun hashCode(): Int {
    var result = keyId.hashCode()
    result = 31 * result + key.contentHashCode()
    return result
  }
}

object ContentPackCodec {
  private const val MAGIC = "FLCP"
  private const val CONTAINER_VERSION_V1 = 1
  private const val SERIALIZATION_CBOR: Byte = 1
  private const val COMPRESSION_ZSTD: Byte = 1
  private const val ENCRYPTION_AES_256_GCM: Byte = 1

  private const val NONCE_SIZE = 12
  private const val GCM_TAG_BITS = 128
  private const val GCM_TAG_BYTES = 16
  private const val MAX_KEY_ID_LENGTH = 255
  private const val MAX_CONTAINER_SIZE = 64L * 1024 * 1024
  private const val MAX_DECOMPRESSED_SIZE = 32L * 1024 * 1024

  private val secureRandom = SecureRandom()

  private val cbor = Cbor {
    encodeDefaults = true
    ignoreUnknownKeys = false
  }

  fun encode(pack: ContentPack, config: CryptoConfig): ByteArray {
    val cborBytes = cbor.encodeToByteArray(ContentPackSerializer, pack)
    val compressed = Zstd.compress(cborBytes)
    val nonce = ByteArray(NONCE_SIZE).also { secureRandom.nextBytes(it) }

    val keyIdBytes = config.keyId.toByteArray(Charsets.UTF_8)
    if (keyIdBytes.isEmpty() || keyIdBytes.size > MAX_KEY_ID_LENGTH) {
      throw ContentPackException(
        ContentPackError.Format("keyId length must be between 1 and $MAX_KEY_ID_LENGTH bytes.")
      )
    }

    val payloadSize = compressed.size.toLong() + GCM_TAG_BYTES

    val header = buildFullHeader(
      containerVersion = CONTAINER_VERSION_V1,
      keyIdBytes = keyIdBytes,
      nonce = nonce,
      originalCborSize = cborBytes.size.toLong(),
      payloadSize = payloadSize
    )

    if (header.size + payloadSize > MAX_CONTAINER_SIZE) {
      throw ContentPackException(
        ContentPackError.Format("Container exceeds maximum allowed size of $MAX_CONTAINER_SIZE bytes.")
      )
    }

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val keySpec = SecretKeySpec(config.key, "AES")
    val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, nonce)
    cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
    cipher.updateAAD(header)

    val ciphertextWithTag = cipher.doFinal(compressed)
    if (ciphertextWithTag.size.toLong() != payloadSize) {
      throw ContentPackException(
        ContentPackError.Format("Unexpected ciphertext size after encryption.")
      )
    }

    return header + ciphertextWithTag
  }

  fun decode(container: ByteArray, config: CryptoConfig): ContentPack {
    if (container.size > MAX_CONTAINER_SIZE) {
      throw ContentPackException(
        ContentPackError.Format("Container exceeds maximum allowed size of $MAX_CONTAINER_SIZE bytes.")
      )
    }
    if (container.size < minimumHeaderSize()) {
      throw ContentPackException(ContentPackError.Format("Container too short to contain a valid header."))
    }

    val buffer = ByteBuffer.wrap(container).order(ByteOrder.BIG_ENDIAN)

    val magicBytes = ByteArray(4)
    buffer.get(magicBytes)
    val magic = String(magicBytes, Charsets.US_ASCII)
    if (magic != MAGIC) {
      throw ContentPackException(ContentPackError.Format("Invalid magic bytes. Expected $MAGIC."))
    }

    val containerVersion = buffer.short.toInt() and 0xFFFF
    if (containerVersion != CONTAINER_VERSION_V1) {
      throw ContentPackException(
        ContentPackError.Compatibility("Unsupported container version: $containerVersion.")
      )
    }

    val serializationAlgo = buffer.get()
    val compressionAlgo = buffer.get()
    val encryptionAlgo = buffer.get()

    if (serializationAlgo != SERIALIZATION_CBOR) {
      throw ContentPackException(
        ContentPackError.Compatibility("Unsupported serialization algorithm id: $serializationAlgo.")
      )
    }
    if (compressionAlgo != COMPRESSION_ZSTD) {
      throw ContentPackException(
        ContentPackError.Compatibility("Unsupported compression algorithm id: $compressionAlgo.")
      )
    }
    if (encryptionAlgo != ENCRYPTION_AES_256_GCM) {
      throw ContentPackException(
        ContentPackError.Compatibility("Unsupported encryption algorithm id: $encryptionAlgo.")
      )
    }

    val keyIdLength = buffer.short.toInt() and 0xFFFF
    if (keyIdLength < 1 || keyIdLength > MAX_KEY_ID_LENGTH) {
      throw ContentPackException(
        ContentPackError.Format("Invalid keyId length: $keyIdLength.")
      )
    }
    if (buffer.remaining() < keyIdLength + NONCE_SIZE + 8 + 8) {
      throw ContentPackException(ContentPackError.Format("Container truncated while reading keyId or sizes."))
    }

    val keyIdBytes = ByteArray(keyIdLength)
    buffer.get(keyIdBytes)
    val keyId = try {
      String(keyIdBytes, Charsets.UTF_8)
    } catch (e: Exception) {
      throw ContentPackException(ContentPackError.Format("keyId is not valid UTF-8."))
    }
    if (keyId.isBlank()) {
      throw ContentPackException(ContentPackError.Format("keyId cannot be blank."))
    }
    if (keyId != config.keyId) {
      throw ContentPackException(
        ContentPackError.Cryptography("Provided keyId does not match the keyId in the container header.")
      )
    }

    val nonce = ByteArray(NONCE_SIZE)
    buffer.get(nonce)

    val originalCborSize = buffer.long
    if (originalCborSize < 0 || originalCborSize > MAX_DECOMPRESSED_SIZE) {
      throw ContentPackException(
        ContentPackError.Format("Declared original CBOR size out of allowed range: $originalCborSize.")
      )
    }

    val payloadSize = buffer.long
    if (payloadSize < GCM_TAG_BYTES || payloadSize > MAX_CONTAINER_SIZE) {
      throw ContentPackException(
        ContentPackError.Format("Declared payload size out of allowed range: $payloadSize.")
      )
    }

    val headerEnd = buffer.position()
    if (container.size.toLong() != headerEnd + payloadSize) {
      throw ContentPackException(
        ContentPackError.Format("Container size does not match header + declared payload size.")
      )
    }

    val aad = container.copyOfRange(0, headerEnd)
    val ciphertextWithTag = container.copyOfRange(headerEnd, container.size)

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val keySpec = SecretKeySpec(config.key, "AES")
    val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, nonce)
    try {
      cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
      cipher.updateAAD(aad)
      val compressed = cipher.doFinal(ciphertextWithTag)
      val decompressed = Zstd.decompress(compressed, originalCborSize.toInt())
      if (decompressed.size.toLong() != originalCborSize) {
        throw ContentPackException(
          ContentPackError.Format("Decompressed size does not match declared original CBOR size.")
        )
      }
      return cbor.decodeFromByteArray(ContentPackSerializer, decompressed)
    } catch (e: ContentPackException) {
      throw e
    } catch (e: SerializationException) {
      throw ContentPackException(ContentPackError.Format("CBOR decoding failed: ${e.message}"))
    } catch (e: Exception) {
      throw ContentPackException(
        ContentPackError.Cryptography("Decryption or decompression failed: ${e.message}")
      )
    }
  }

  private fun minimumHeaderSize(): Int {
    return 4 + 2 + 3 + 2 + 1 + NONCE_SIZE + 8 + 8
  }

  @Suppress("SameParameterValue")
  private fun buildFullHeader(
    containerVersion: Int, keyIdBytes: ByteArray, nonce: ByteArray, originalCborSize: Long, payloadSize: Long
  ): ByteArray {
    val keyIdLength = keyIdBytes.size
    val size = 4 + 2 + 3 + 2 + keyIdLength + NONCE_SIZE + 8 + 8
    val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
    buffer.put(MAGIC.toByteArray(Charsets.US_ASCII))
    buffer.putShort(containerVersion.toShort())
    buffer.put(SERIALIZATION_CBOR)
    buffer.put(COMPRESSION_ZSTD)
    buffer.put(ENCRYPTION_AES_256_GCM)
    buffer.putShort(keyIdLength.toShort())
    buffer.put(keyIdBytes)
    buffer.put(nonce)
    buffer.putLong(originalCborSize)
    buffer.putLong(payloadSize)
    return buffer.array()
  }
}

class ContentPackException(val error: ContentPackError) : Exception(error.message)

// ---------------------------------------------------------------------------
// Explicit serializers – models stay free of @Serializable annotations
// ---------------------------------------------------------------------------

private object ContentPackIdSerializer : KSerializer<ContentPackId> {
  override val descriptor = PrimitiveSerialDescriptor("ContentPackId", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: ContentPackId) {
    encoder.encodeString(value.value)
  }

  override fun deserialize(decoder: Decoder): ContentPackId {
    return ContentPackId(decoder.decodeString())
  }
}

private object ContentRequirementSerializer : KSerializer<ContentRequirement> {
  override val descriptor = PrimitiveSerialDescriptor("ContentRequirement", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: ContentRequirement) {
    encoder.encodeString(value.name)
  }

  override fun deserialize(decoder: Decoder): ContentRequirement {
    val name = decoder.decodeString()
    return try {
      ContentRequirement.valueOf(name)
    } catch (e: Exception) {
      throw SerializationException("Invalid ContentRequirement: $name")
    }
  }
}

private object SectionInfoSerializer : KSerializer<SectionInfo> {
  override val descriptor = buildClassSerialDescriptor("SectionInfo") {
    element<String>("id")
    element<Int>("version")
    element("requirement", ContentRequirementSerializer.descriptor)
  }

  override fun serialize(encoder: Encoder, value: SectionInfo) {
    encoder.encodeStructure(descriptor) {
      encodeStringElement(descriptor, 0, value.id)
      encodeIntElement(descriptor, 1, value.version)
      encodeSerializableElement(descriptor, 2, ContentRequirementSerializer, value.requirement)
    }
  }

  override fun deserialize(decoder: Decoder): SectionInfo {
    return decoder.decodeStructure(descriptor) {
      var id = ""
      var version = 0
      var requirement = ContentRequirement.Optional
      while (true) {
        when (val index = decodeElementIndex(descriptor)) {
          0 -> id = decodeStringElement(descriptor, 0)
          1 -> version = decodeIntElement(descriptor, 1)
          2 -> requirement = decodeSerializableElement(descriptor, 2, ContentRequirementSerializer)
          CompositeDecoder.DECODE_DONE -> break
          else -> throw SerializationException("Unexpected index $index")
        }
      }
      SectionInfo(id, version, requirement)
    }
  }
}

private object FeatureInfoSerializer : KSerializer<FeatureInfo> {
  override val descriptor = buildClassSerialDescriptor("FeatureInfo") {
    element<String>("id")
    element<Int>("version")
    element("requirement", ContentRequirementSerializer.descriptor)
  }

  override fun serialize(encoder: Encoder, value: FeatureInfo) {
    encoder.encodeStructure(descriptor) {
      encodeStringElement(descriptor, 0, value.id)
      encodeIntElement(descriptor, 1, value.version)
      encodeSerializableElement(descriptor, 2, ContentRequirementSerializer, value.requirement)
    }
  }

  override fun deserialize(decoder: Decoder): FeatureInfo {
    return decoder.decodeStructure(descriptor) {
      var id = ""
      var version = 0
      var requirement = ContentRequirement.Optional
      while (true) {
        when (val index = decodeElementIndex(descriptor)) {
          0 -> id = decodeStringElement(descriptor, 0)
          1 -> version = decodeIntElement(descriptor, 1)
          2 -> requirement = decodeSerializableElement(descriptor, 2, ContentRequirementSerializer)
          CompositeDecoder.DECODE_DONE -> break
          else -> throw SerializationException("Unexpected index $index")
        }
      }
      FeatureInfo(id, version, requirement)
    }
  }
}

private object ContentValueSerializer : KSerializer<ContentValue> {
  override val descriptor = buildClassSerialDescriptor("ContentValue") {
    element<String>("type")
    element<Boolean>("boolean", isOptional = true)
    element<Long>("integer", isOptional = true)
    element<Double>("float", isOptional = true)
    element<String>("string", isOptional = true)
    element("array", ListSerializer(ContentValueSerializer).descriptor, isOptional = true)
    element("object", MapSerializer(String.serializer(), ContentValueSerializer).descriptor, isOptional = true)
  }

  override fun serialize(encoder: Encoder, value: ContentValue) {
    encoder.encodeStructure(descriptor) {
      when (value) {
        is ContentValue.NullValue -> {
          encodeStringElement(descriptor, 0, "null")
        }

        is ContentValue.BooleanValue -> {
          encodeStringElement(descriptor, 0, "boolean")
          encodeBooleanElement(descriptor, 1, value.value)
        }

        is ContentValue.IntegerValue -> {
          encodeStringElement(descriptor, 0, "integer")
          encodeLongElement(descriptor, 2, value.value)
        }

        is ContentValue.FloatValue -> {
          encodeStringElement(descriptor, 0, "float")
          encodeDoubleElement(descriptor, 3, value.value)
        }

        is ContentValue.StringValue -> {
          encodeStringElement(descriptor, 0, "string")
          encodeStringElement(descriptor, 4, value.value)
        }

        is ContentValue.ArrayValue -> {
          encodeStringElement(descriptor, 0, "array")
          encodeSerializableElement(descriptor, 5, ListSerializer(ContentValueSerializer), value.value)
        }

        is ContentValue.ObjectValue -> {
          encodeStringElement(descriptor, 0, "object")
          encodeSerializableElement(
            descriptor, 6, MapSerializer(String.serializer(), ContentValueSerializer), value.value
          )
        }
      }
    }
  }

  override fun deserialize(decoder: Decoder): ContentValue {
    return decoder.decodeStructure(descriptor) {
      var type = ""
      var booleanValue: Boolean? = null
      var integerValue: Long? = null
      var floatValue: Double? = null
      var stringValue: String? = null
      var arrayValue: List<ContentValue>? = null
      var objectValue: Map<String, ContentValue>? = null

      while (true) {
        when (val index = decodeElementIndex(descriptor)) {
          0 -> type = decodeStringElement(descriptor, 0)
          1 -> booleanValue = decodeBooleanElement(descriptor, 1)
          2 -> integerValue = decodeLongElement(descriptor, 2)
          3 -> floatValue = decodeDoubleElement(descriptor, 3)
          4 -> stringValue = decodeStringElement(descriptor, 4)
          5 -> arrayValue = decodeSerializableElement(descriptor, 5, ListSerializer(ContentValueSerializer))
          6 -> objectValue = decodeSerializableElement(
            descriptor, 6, MapSerializer(String.serializer(), ContentValueSerializer)
          )

          CompositeDecoder.DECODE_DONE -> break
          else -> throw SerializationException("Unexpected index $index")
        }
      }

      when (type) {
        "null" -> ContentValue.NullValue
        "boolean" -> ContentValue.BooleanValue(booleanValue ?: throw SerializationException("Missing boolean value"))
        "integer" -> ContentValue.IntegerValue(integerValue ?: throw SerializationException("Missing integer value"))
        "float" -> ContentValue.FloatValue(floatValue ?: throw SerializationException("Missing float value"))
        "string" -> ContentValue.StringValue(stringValue ?: throw SerializationException("Missing string value"))
        "array" -> ContentValue.ArrayValue(arrayValue ?: throw SerializationException("Missing array value"))
        "object" -> ContentValue.ObjectValue(objectValue ?: throw SerializationException("Missing object value"))
        else -> throw SerializationException("Unknown ContentValue type: $type")
      }
    }
  }
}

private object LocationDefinitionSerializer : KSerializer<LocationDefinition> {
  override val descriptor = buildClassSerialDescriptor("LocationDefinition") {
    element<String>("id")
    element<String>("displayName")
  }

  override fun serialize(encoder: Encoder, value: LocationDefinition) {
    encoder.encodeStructure(descriptor) {
      encodeStringElement(descriptor, 0, value.id)
      encodeStringElement(descriptor, 1, value.displayName)
    }
  }

  override fun deserialize(decoder: Decoder): LocationDefinition {
    return decoder.decodeStructure(descriptor) {
      var id = ""
      var displayName = ""
      while (true) {
        when (val index = decodeElementIndex(descriptor)) {
          0 -> id = decodeStringElement(descriptor, 0)
          1 -> displayName = decodeStringElement(descriptor, 1)
          CompositeDecoder.DECODE_DONE -> break
          else -> throw SerializationException("Unexpected index $index")
        }
      }
      LocationDefinition(id, displayName)
    }
  }
}

private object ConnectionDefinitionSerializer : KSerializer<ConnectionDefinition> {
  override val descriptor = buildClassSerialDescriptor("ConnectionDefinition") {
    element<String>("id")
    element<String>("from")
    element<String>("to")
    element<Long>("distance")
  }

  override fun serialize(encoder: Encoder, value: ConnectionDefinition) {
    encoder.encodeStructure(descriptor) {
      encodeStringElement(descriptor, 0, value.id)
      encodeStringElement(descriptor, 1, value.from)
      encodeStringElement(descriptor, 2, value.to)
      encodeLongElement(descriptor, 3, value.distance)
    }
  }

  override fun deserialize(decoder: Decoder): ConnectionDefinition {
    return decoder.decodeStructure(descriptor) {
      var id = ""
      var from = ""
      var to = ""
      var distance = 0L
      while (true) {
        when (val index = decodeElementIndex(descriptor)) {
          0 -> id = decodeStringElement(descriptor, 0)
          1 -> from = decodeStringElement(descriptor, 1)
          2 -> to = decodeStringElement(descriptor, 2)
          3 -> distance = decodeLongElement(descriptor, 3)
          CompositeDecoder.DECODE_DONE -> break
          else -> throw SerializationException("Unexpected index $index")
        }
      }
      ConnectionDefinition(id, from, to, distance)
    }
  }
}

private object ExplorationElementDefinitionSerializer : KSerializer<ExplorationElementDefinition> {
  override val descriptor = buildClassSerialDescriptor("ExplorationElementDefinition") {
    element<String>("id")
    element<String>("name")
    element<String>("observationDescription")
    element<String>("examinationDescription")
  }

  override fun serialize(encoder: Encoder, value: ExplorationElementDefinition) {
    encoder.encodeStructure(descriptor) {
      encodeStringElement(descriptor, 0, value.id)
      encodeStringElement(descriptor, 1, value.name)
      encodeStringElement(descriptor, 2, value.observationDescription)
      encodeStringElement(descriptor, 3, value.examinationDescription)
    }
  }

  override fun deserialize(decoder: Decoder): ExplorationElementDefinition {
    return decoder.decodeStructure(descriptor) {
      var id = ""
      var name = ""
      var observationDescription = ""
      var examinationDescription = ""
      while (true) {
        when (val index = decodeElementIndex(descriptor)) {
          0 -> id = decodeStringElement(descriptor, 0)
          1 -> name = decodeStringElement(descriptor, 1)
          2 -> observationDescription = decodeStringElement(descriptor, 2)
          3 -> examinationDescription = decodeStringElement(descriptor, 3)
          CompositeDecoder.DECODE_DONE -> break
          else -> throw SerializationException("Unexpected index $index")
        }
      }
      ExplorationElementDefinition(id, name, observationDescription, examinationDescription)
    }
  }
}

private object DiscoveryLinkDefinitionSerializer : KSerializer<DiscoveryLinkDefinition> {
  override val descriptor = buildClassSerialDescriptor("DiscoveryLinkDefinition") {
    element<String>("from")
    element<String>("to")
  }

  override fun serialize(encoder: Encoder, value: DiscoveryLinkDefinition) {
    encoder.encodeStructure(descriptor) {
      encodeStringElement(descriptor, 0, value.from)
      encodeStringElement(descriptor, 1, value.to)
    }
  }

  override fun deserialize(decoder: Decoder): DiscoveryLinkDefinition {
    return decoder.decodeStructure(descriptor) {
      var from = ""
      var to = ""
      while (true) {
        when (val index = decodeElementIndex(descriptor)) {
          0 -> from = decodeStringElement(descriptor, 0)
          1 -> to = decodeStringElement(descriptor, 1)
          CompositeDecoder.DECODE_DONE -> break
          else -> throw SerializationException("Unexpected index $index")
        }
      }
      DiscoveryLinkDefinition(from, to)
    }
  }
}

private object ExplorationAreaDefinitionSerializer : KSerializer<ExplorationAreaDefinition> {
  override val descriptor = buildClassSerialDescriptor("ExplorationAreaDefinition") {
    element<String>("id")
    element<String>("name")
    element<String>("locationId")
    element<String>("description")
    element("elements", ListSerializer(ExplorationElementDefinitionSerializer).descriptor)
    element("initialElementIds", ListSerializer(String.serializer()).descriptor)
    element("discoveryLinks", ListSerializer(DiscoveryLinkDefinitionSerializer).descriptor)
  }

  override fun serialize(encoder: Encoder, value: ExplorationAreaDefinition) {
    encoder.encodeStructure(descriptor) {
      encodeStringElement(descriptor, 0, value.id)
      encodeStringElement(descriptor, 1, value.name)
      encodeStringElement(descriptor, 2, value.locationId)
      encodeStringElement(descriptor, 3, value.description)
      encodeSerializableElement(descriptor, 4, ListSerializer(ExplorationElementDefinitionSerializer), value.elements)
      encodeSerializableElement(descriptor, 5, ListSerializer(String.serializer()), value.initialElementIds.toList())
      encodeSerializableElement(descriptor, 6, ListSerializer(DiscoveryLinkDefinitionSerializer), value.discoveryLinks)
    }
  }

  override fun deserialize(decoder: Decoder): ExplorationAreaDefinition {
    return decoder.decodeStructure(descriptor) {
      var id = ""
      var name = ""
      var locationId = ""
      var description = ""
      var elements = emptyList<ExplorationElementDefinition>()
      var initialElementIds = emptyList<String>()
      var discoveryLinks = emptyList<DiscoveryLinkDefinition>()
      while (true) {
        when (val index = decodeElementIndex(descriptor)) {
          0 -> id = decodeStringElement(descriptor, 0)
          1 -> name = decodeStringElement(descriptor, 1)
          2 -> locationId = decodeStringElement(descriptor, 2)
          3 -> description = decodeStringElement(descriptor, 3)
          4 -> elements =
            decodeSerializableElement(descriptor, 4, ListSerializer(ExplorationElementDefinitionSerializer))

          5 -> initialElementIds = decodeSerializableElement(descriptor, 5, ListSerializer(String.serializer()))
          6 -> discoveryLinks =
            decodeSerializableElement(descriptor, 6, ListSerializer(DiscoveryLinkDefinitionSerializer))

          CompositeDecoder.DECODE_DONE -> break
          else -> throw SerializationException("Unexpected index $index")
        }
      }
      ExplorationAreaDefinition(
        id = id,
        name = name,
        locationId = locationId,
        description = description,
        elements = elements,
        initialElementIds = initialElementIds.toSet(),
        discoveryLinks = discoveryLinks
      )
    }
  }
}

private object WorldDefinitionSerializer : KSerializer<WorldDefinition> {
  override val descriptor = buildClassSerialDescriptor("WorldDefinition") {
    element("locations", ListSerializer(LocationDefinitionSerializer).descriptor)
    element("connections", ListSerializer(ConnectionDefinitionSerializer).descriptor)
  }

  override fun serialize(encoder: Encoder, value: WorldDefinition) {
    encoder.encodeStructure(descriptor) {
      encodeSerializableElement(descriptor, 0, ListSerializer(LocationDefinitionSerializer), value.locations)
      encodeSerializableElement(descriptor, 1, ListSerializer(ConnectionDefinitionSerializer), value.connections)
    }
  }

  override fun deserialize(decoder: Decoder): WorldDefinition {
    return decoder.decodeStructure(descriptor) {
      var locations = emptyList<LocationDefinition>()
      var connections = emptyList<ConnectionDefinition>()
      while (true) {
        when (val index = decodeElementIndex(descriptor)) {
          0 -> locations = decodeSerializableElement(descriptor, 0, ListSerializer(LocationDefinitionSerializer))
          1 -> connections = decodeSerializableElement(descriptor, 1, ListSerializer(ConnectionDefinitionSerializer))
          CompositeDecoder.DECODE_DONE -> break
          else -> throw SerializationException("Unexpected index $index")
        }
      }
      WorldDefinition(locations, connections)
    }
  }
}

private object ExplorationDefinitionSerializer : KSerializer<ExplorationDefinition> {
  override val descriptor = buildClassSerialDescriptor("ExplorationDefinition") {
    element("areas", ListSerializer(ExplorationAreaDefinitionSerializer).descriptor)
  }

  override fun serialize(encoder: Encoder, value: ExplorationDefinition) {
    encoder.encodeStructure(descriptor) {
      encodeSerializableElement(descriptor, 0, ListSerializer(ExplorationAreaDefinitionSerializer), value.areas)
    }
  }

  override fun deserialize(decoder: Decoder): ExplorationDefinition {
    return decoder.decodeStructure(descriptor) {
      var areas = emptyList<ExplorationAreaDefinition>()
      while (true) {
        when (val index = decodeElementIndex(descriptor)) {
          0 -> areas = decodeSerializableElement(descriptor, 0, ListSerializer(ExplorationAreaDefinitionSerializer))
          CompositeDecoder.DECODE_DONE -> break
          else -> throw SerializationException("Unexpected index $index")
        }
      }
      ExplorationDefinition(areas)
    }
  }
}

private object ContentPackSerializer : KSerializer<ContentPack> {
  override val descriptor = buildClassSerialDescriptor("ContentPack") {
    element("id", ContentPackIdSerializer.descriptor)
    element<Int>("containerVersion")
    element<Int>("schemaVersion")
    element("sections", ListSerializer(SectionInfoSerializer).descriptor)
    element("features", ListSerializer(FeatureInfoSerializer).descriptor)
    element("world", WorldDefinitionSerializer.descriptor, isOptional = true)
    element("exploration", ExplorationDefinitionSerializer.descriptor, isOptional = true)
    element("extensions", MapSerializer(String.serializer(), ContentValueSerializer).descriptor)
  }

  override fun serialize(encoder: Encoder, value: ContentPack) {
    encoder.encodeStructure(descriptor) {
      encodeSerializableElement(descriptor, 0, ContentPackIdSerializer, value.id)
      encodeIntElement(descriptor, 1, value.containerVersion)
      encodeIntElement(descriptor, 2, value.schemaVersion)
      encodeSerializableElement(descriptor, 3, ListSerializer(SectionInfoSerializer), value.sections)
      encodeSerializableElement(descriptor, 4, ListSerializer(FeatureInfoSerializer), value.features)
      if (value.world != null) {
        encodeSerializableElement(descriptor, 5, WorldDefinitionSerializer, value.world)
      }
      if (value.exploration != null) {
        encodeSerializableElement(descriptor, 6, ExplorationDefinitionSerializer, value.exploration)
      }
      encodeSerializableElement(
        descriptor, 7, MapSerializer(String.serializer(), ContentValueSerializer), value.extensions
      )
    }
  }

  override fun deserialize(decoder: Decoder): ContentPack {
    return decoder.decodeStructure(descriptor) {
      var id: ContentPackId? = null
      var containerVersion = 0
      var schemaVersion = 0
      var sections = emptyList<SectionInfo>()
      var features = emptyList<FeatureInfo>()
      var world: WorldDefinition? = null
      var exploration: ExplorationDefinition? = null
      var extensions = emptyMap<String, ContentValue>()

      while (true) {
        when (val index = decodeElementIndex(descriptor)) {
          0 -> id = decodeSerializableElement(descriptor, 0, ContentPackIdSerializer)
          1 -> containerVersion = decodeIntElement(descriptor, 1)
          2 -> schemaVersion = decodeIntElement(descriptor, 2)
          3 -> sections = decodeSerializableElement(descriptor, 3, ListSerializer(SectionInfoSerializer))
          4 -> features = decodeSerializableElement(descriptor, 4, ListSerializer(FeatureInfoSerializer))
          5 -> world = decodeSerializableElement(descriptor, 5, WorldDefinitionSerializer)
          6 -> exploration = decodeSerializableElement(descriptor, 6, ExplorationDefinitionSerializer)
          7 -> extensions = decodeSerializableElement(
            descriptor, 7, MapSerializer(String.serializer(), ContentValueSerializer)
          )

          CompositeDecoder.DECODE_DONE -> break
          else -> throw SerializationException("Unexpected index $index")
        }
      }

      ContentPack(
        id = id ?: throw SerializationException("Missing id"),
        containerVersion = containerVersion,
        schemaVersion = schemaVersion,
        sections = sections,
        features = features,
        world = world,
        exploration = exploration,
        extensions = extensions
      )
    }
  }
}